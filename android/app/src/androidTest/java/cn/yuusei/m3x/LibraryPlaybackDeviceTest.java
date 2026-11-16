package cn.yuusei.m3x;

import android.content.Intent;
import android.os.SystemClock;
import android.test.InstrumentationTestCase;
import androidx.media3.common.C;
import androidx.media3.common.MediaItem;
import androidx.media3.common.Player;
import androidx.media3.common.Timeline;
import java.io.File;
import java.io.FileOutputStream;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.json.JSONObject;
import org.json.JSONArray;

/** Exercises the installed WebView and native queue without scanning user music. */
@androidx.media3.common.util.UnstableApi
public final class LibraryPlaybackDeviceTest extends InstrumentationTestCase {
    private MainActivity activity;

    private String js(String code) throws Exception {
        CountDownLatch latch = new CountDownLatch(1);
        String[] value = {""};
        getInstrumentation().runOnMainSync(() -> activity.web.evaluateJavascript(code, result -> {
            value[0] = result; latch.countDown();
        }));
        assertTrue("WebView callback", latch.await(20, TimeUnit.SECONDS));
        return value[0];
    }

    private void waitJs(String code) throws Exception {
        long end = SystemClock.elapsedRealtime() + 30000;
        while (SystemClock.elapsedRealtime() < end) {
            if ("true".equals(js(code))) return;
            Thread.sleep(100);
        }
        fail("Timed out: " + code);
    }

    private void act(String code) throws Exception {
        assertEquals("JS action", "\"ok\"", js("(function(){try{" + code + ";return 'ok'}catch(e){return String(e)}})()"));
    }

    private String click(String selector) {
        return "document.querySelector(" + JSONObject.quote(selector) + ").click()";
    }

    private Field field(String name) throws Exception {
        Field result = PlaybackService.class.getDeclaredField(name);
        result.setAccessible(true);
        return result;
    }

    private void capture(File directory, String name) throws Exception {
        android.graphics.Bitmap bitmap = getInstrumentation().getUiAutomation().takeScreenshot();
        assertNotNull("Physical screenshot", bitmap);
        try (FileOutputStream out = new FileOutputStream(new File(directory, name + ".png"))) {
            bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, out);
        } finally { bitmap.recycle(); }
    }

    private JSONObject autoLoad(String list, String rows, String scroller) throws Exception {
        String count = "document.querySelectorAll(" + JSONObject.quote(list + " " + rows) + ").length";
        int before = Integer.parseInt(js(count));
        act("window.__firstRow=document.querySelector(" + JSONObject.quote(list + " " + rows) + ");"
            + "var e=document.querySelector(" + JSONObject.quote(scroller) + ");"
            + "e.scrollTop=e.scrollHeight-e.clientHeight;window.__listScroll=e.scrollTop;window.__listStart=performance.now()");
        waitJs(count + ">" + before);
        assertEquals("Existing rows are retained", "true", js("window.__firstRow===document.querySelector(" + JSONObject.quote(list + " " + rows) + ")"));
        assertEquals("Scroll position is retained", "true", js("Math.abs(document.querySelector(" + JSONObject.quote(scroller) + ").scrollTop-window.__listScroll)<2"));
        int after = Integer.parseInt(js(count));
        assertTrue("Append a bounded batch", after > before && after <= before + 40);
        return new JSONObject().put("list", list).put("before", before).put("after", after)
            .put("observedMs", Double.parseDouble(js("performance.now()-window.__listStart")));
    }

    private void swipeLibrary() throws Exception {
        JSONArray points = new JSONArray(js("(function(){var r=document.getElementById('library-browser').getBoundingClientRect();"
            + "return [(r.left+r.width/2)*devicePixelRatio,(r.top+r.height*.85)*devicePixelRatio,(r.top+r.height*.15)*devicePixelRatio]})()"));
        int[] origin = new int[2];
        getInstrumentation().runOnMainSync(() -> activity.web.getLocationOnScreen(origin));
        long down = SystemClock.uptimeMillis();
        for (int i = 0; i <= 13; i++) {
            int action = i == 0 ? android.view.MotionEvent.ACTION_DOWN : i == 13 ? android.view.MotionEvent.ACTION_UP : android.view.MotionEvent.ACTION_MOVE;
            float x = (float) points.getDouble(0) + origin[0];
            float y = (float) (points.getDouble(1) + (points.getDouble(2) - points.getDouble(1)) * Math.min(i, 12) / 12) + origin[1];
            android.view.MotionEvent event = android.view.MotionEvent.obtain(down, SystemClock.uptimeMillis(), action, x, y, 0);
            event.setSource(android.view.InputDevice.SOURCE_TOUCHSCREEN);
            try { assertTrue("Physical touch injection", getInstrumentation().getUiAutomation().injectInputEvent(event, true)); }
            finally { event.recycle(); }
            Thread.sleep(20);
        }
    }

    public void testFullLibraryShuffleAndAutomaticLoading() throws Exception {
        assertEquals("Physical M3X", "Shanling M3X", android.os.Build.MODEL.replace('_', ' '));
        PlayerApp app = PlayerApp.instance;
        app.awaitLibrary();
        activity = (MainActivity) getInstrumentation().startActivitySync(new Intent(
            getInstrumentation().getTargetContext(), MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        waitJs("!!window.M3X_UI && !!nativeCommand('poll',{stats:true}).stats && M3X_UI.tracks.length>4000");
        JSONObject savedUi = new JSONObject(app.settings("ui").toString());
        JSONObject savedTools = new JSONObject(app.settings("tools").toString());
        JSONObject savedAudio = new JSONObject(app.settings("audio").toString());
        String scan = app.library.scanStatus().toString();
        int trackCount = app.library.all().size();
        File directory = new File(app.getExternalFilesDir(null), "library-playback-verification");
        assertTrue(directory.isDirectory() || directory.mkdirs());
        JSONObject evidence = new JSONObject().put("tracks", trackCount).put("device", android.os.Build.MODEL);
        JSONArray batches = new JSONArray();
        Map<String, Object> savedFields = new LinkedHashMap<>();
        for (String name : new String[]{"manualGain", "rampStart", "pauseAfterRamp", "rampFrom", "rampTarget",
                "fadeTrackStart", "listenedMs", "counted", "previousAlbum", "statsPlaying", "settings"}) {
            savedFields.put(name, field(name).get(app.playback));
        }
        JSONObject savedStats = new JSONObject(((JSONObject) field("stats").get(app.playback)).toString());
        List<MediaItem> savedQueue = new ArrayList<>();
        long[] position = {0}; int[] current = {0}; float[] volume = {1}; boolean[] playing = {false};
        getInstrumentation().runOnMainSync(() -> {
            for (int i = 0; i < app.playback.player.getMediaItemCount(); i++) savedQueue.add(app.playback.player.getMediaItemAt(i));
            current[0] = app.playback.player.getCurrentMediaItemIndex(); position[0] = app.playback.player.getCurrentPosition();
            volume[0] = app.playback.player.getVolume(); playing[0] = app.playback.player.getPlayWhenReady();
            app.playback.player.pause(); app.playback.player.setVolume(0);
            try {
                JSONObject quiet = new JSONObject(((JSONObject) savedFields.get("settings")).toString());
                quiet.put("fades", false).put("albumTransitions", false);
                field("settings").set(app.playback, quiet); field("manualGain").setFloat(app.playback, 0);
                field("rampStart").setLong(app.playback, 0); field("pauseAfterRamp").setBoolean(app.playback, false);
            } catch (Exception e) { throw new RuntimeException(e); }
        });
        try {
            getInstrumentation().runOnMainSync(() -> activity.getWindow().addFlags(
                android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON | android.view.WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON));
            waitJs("!!window.M3X_UI && !!nativeCommand('poll',{stats:true}).stats && M3X_UI.tracks.length>4000");
            act("var e=document.querySelector('[data-setting=reduceMotion]');e.checked=true;e.dispatchEvent(new Event('change',{bubbles:true}));"
                + click(".m3x-nav [data-page=library]") + ";" + click("[data-library-view=list]") + ";" + click("[data-filter=all]"));
            assertEquals("Only first batch initially", "40", js("document.querySelectorAll('[data-list=library] [data-track]').length"));
            String queueBeforeScroll = js("nativeCommand('queue').ids");
            for (int i = 0; i < 3; i++) batches.put(autoLoad("[data-list=library]", "[data-track]", "#library-browser"));
            String currentBeforeSwipe = js("M3X_UI.currentTrack().id");
            act("var e=document.getElementById('library-browser');e.scrollTop=e.scrollHeight-e.clientHeight-420");
            Thread.sleep(250);
            int swipeBefore = Integer.parseInt(js("document.querySelectorAll('[data-list=library] [data-track]').length"));
            swipeLibrary();
            waitJs("document.querySelectorAll('[data-list=library] [data-track]').length>" + swipeBefore);
            assertEquals("Dragging never selects a song", currentBeforeSwipe, js("M3X_UI.currentTrack().id"));
            evidence.put("physicalSwipeAutoLoaded", true);
            assertEquals("Scrolling does not alter playback queue", queueBeforeScroll, js("nativeCommand('queue').ids"));
            capture(directory, "list-auto-loaded");
            act("var e=document.querySelector('.m3x-search');e.value=' ';e.dispatchEvent(new Event('input',{bubbles:true}))");
            assertEquals("New query resets batching", "40", js("document.querySelectorAll('[data-list=library] [data-track]').length"));
            assertEquals("Query returns to top", "0", js("document.getElementById('library-browser').scrollTop"));
            act(click("[data-library-view=grid]"));
            batches.put(autoLoad("[data-list=library]", "[data-track]", "#library-browser"));
            capture(directory, "grid-auto-loaded");
            act(click("[data-filter=characters]"));
            batches.put(autoLoad("#library-groups", "[data-group]", "#library-browser"));
            act(click("[data-filter=albums]"));
            batches.put(autoLoad("#library-groups", "[data-group]", "#library-browser"));
            act(click("[data-library-view=list]") + ";" + click(".m3x-nav [data-page=player]") + ";" + click("[data-page=queue]"));
            batches.put(autoLoad("[data-list=queue]", "[data-track]", "[data-list=queue]"));

            // Reproduce a small saved selection, then use the existing random mode button.
            // Reset to 列表循环 without a click loop: a NetEase song in 单曲循环 turns the button into the async heartbeat entry.
            act(click(".m3x-nav [data-page=player]") + ";M3X_UI.setMode(0)");
            assertEquals("Sequential mode before the random-button check", "\"播放模式：列表循环\"", js("document.querySelector('[data-action=mode]').getAttribute('aria-label')"));
            JSONObject narrow = new JSONObject(app.settings("ui").toString());
            JSONArray narrowIds = new JSONArray();
            Set<String> hidden = new HashSet<>();
            JSONArray hiddenIds = savedUi.optJSONArray("hidden");
            if (hiddenIds != null) for (int i = 0; i < hiddenIds.length(); i++) hidden.add(hiddenIds.getString(i));
            Set<String> expected = new HashSet<>();
            // Online NetEase entries never join a local-catalog shuffle.
            // Listening-preference exclusions also stay out of automatic queues.
            PreferenceRules.Settings rules = PreferenceRules.settings(narrow);
            for (JSONObject track : app.library.all()) if (!track.has("neteaseId") && !hidden.contains(track.getString("id")) && !rules.excludes(track)) {
                expected.add(track.getString("id"));
                if (narrowIds.length() < 3) narrowIds.put(track.getString("id"));
            }
            narrow.put("queue", narrowIds).put("queueCleared", false).put("mode", 0).put("queueScope", "selection");
            JSONObject legacy = new JSONObject(narrow.toString());
            legacy.put("mode", 1); legacy.remove("queueScope");
            getInstrumentation().runOnMainSync(() -> app.playback.syncQueue(legacy));
            assertEquals("Old random-mode preferences restore the complete catalog", Integer.toString(expected.size()), js("nativeCommand('queue').ids.length"));
            JSONArray testHidden = new JSONArray(hiddenIds == null ? "[]" : hiddenIds.toString());
            testHidden.put(narrowIds.getString(0)); legacy.put("hidden", testHidden);
            getInstrumentation().runOnMainSync(() -> app.playback.syncQueue(legacy));
            assertEquals("Random playback excludes hidden songs", Integer.toString(expected.size() - 1), js("nativeCommand('queue').ids.length"));
            evidence.put("legacyRandomQueueExpanded", true).put("hiddenSongsExcluded", true);
            getInstrumentation().runOnMainSync(() -> { app.save("ui", narrow); app.playback.syncQueue(narrow); });
            act("M3X_UI.previewLibrary(M3X_UI.library());" + click(".m3x-nav [data-page=player]"));
            assertEquals("Small saved selection reproduced", "3", js("nativeCommand('queue').ids.length"));
            act(click("[data-action=mode]") + ";document.getElementById('audio').pause()");
            assertEquals("Switching to random keeps the current selection as the shuffle range", "3", js("nativeCommand('queue').ids.length"));
            evidence.put("shuffleKeepsSelection", true);
            // Picking a song from the unfiltered "all songs" list shuffles the whole local catalog.
            act(click(".m3x-nav [data-page=library]") + ";" + click("[data-filter=all]") + ";" + click("[data-list=library] [data-track]")
                + ";document.getElementById('audio').pause()");
            JSONArray queue = new JSONArray(js("nativeCommand('queue').ids"));
            Set<String> actual = new HashSet<>();
            for (int i = 0; i < queue.length(); i++) actual.add(queue.getString(i));
            assertEquals("All unhidden library tracks, including newly added songs", expected, actual);
            assertEquals("No duplicate queue entries", expected.size(), queue.length());
            assertEquals("Random playback enabled", "1", js("JSON.parse(localStorage.getItem('m3x-shiny-preview-v1')).mode"));
            getInstrumentation().runOnMainSync(() -> {
                assertTrue("Native shuffle enabled", app.playback.player.getShuffleModeEnabled());
                Timeline timeline = app.playback.player.getCurrentTimeline();
                Set<Integer> visited = new HashSet<>();
                int index = app.playback.player.getCurrentMediaItemIndex();
                for (int i = 0; i < expected.size(); i++) {
                    assertTrue("Shuffle visits each song once per cycle", visited.add(index));
                    index = timeline.getNextWindowIndex(index, Player.REPEAT_MODE_ALL, true);
                }
                assertEquals("Whole library covered", expected.size(), visited.size());
                assertEquals("Cycle returns to start", app.playback.player.getCurrentMediaItemIndex(), index);
            });
            Set<String> manualIds = new HashSet<>();
            manualIds.add(new JSONArray("[" + js("nativeCommand('poll').id") + "]").getString(0));
            for (int i = 0; i < 12; i++) {
                String[] next = {""};
                getInstrumentation().runOnMainSync(() -> next[0] = app.playback.player.getMediaItemAt(app.playback.player.getNextMediaItemIndex()).mediaId);
                act((i % 2 == 0 ? click("[data-action=next]") : "M3X_UI.next()") + ";document.getElementById('audio').pause()");
                assertEquals("Manual next uses the native automatic/hardware shuffle order", JSONObject.quote(next[0]), js("M3X_UI.currentTrack().id"));
                assertTrue("Manual shuffle does not repeat early", manualIds.add(next[0]));
            }
            String[] previous = {""};
            getInstrumentation().runOnMainSync(() -> previous[0] = app.playback.player.getMediaItemAt(app.playback.player.getPreviousMediaItemIndex()).mediaId);
            act(click("[data-action=previous]") + ";document.getElementById('audio').pause()");
            assertEquals("Previous follows the same shuffle order", JSONObject.quote(previous[0]), js("M3X_UI.currentTrack().id"));
            capture(directory, "full-shuffle-queue");
            act(click(".m3x-nav [data-page=library]") + ";" + click("[data-filter=characters]") + ";" + click("#library-groups [data-group]")
                + ";" + click("[data-list=library] [data-track]") + ";document.getElementById('audio').pause()");
            int categoryCount = Integer.parseInt(js("nativeCommand('queue').ids.length"));
            assertEquals("Native and UI agree on the category shuffle range", js("M3X_UI.queueIds().length"), Integer.toString(categoryCount));
            assertTrue("Selecting a category song during random mode shuffles inside that category", categoryCount > 0 && categoryCount < queue.length());
            assertEquals("Current song is in the category range", "true", js("nativeCommand('queue').ids.indexOf(M3X_UI.currentTrack().id)>=0"));
            getInstrumentation().runOnMainSync(() -> assertTrue("Native shuffle still enabled", app.playback.player.getShuffleModeEnabled()));
            evidence.put("categorySelectionShufflesInsideCategory", categoryCount);
            act(click("[data-page=queue]"));
            int fullCount = categoryCount;
            String removedId = new JSONArray("[" + js("document.querySelector('[data-list=queue] [data-remove-queue]').dataset.removeQueue") + "]").getString(0);
            act(click("[data-list=queue] [data-remove-queue]"));
            assertEquals("Explicit queue removal remains effective during random playback", Integer.toString(fullCount - 1), js("nativeCommand('queue').ids.length"));
            assertEquals("Removed song stays removed", "false", js("nativeCommand('queue').ids.indexOf(" + JSONObject.quote(removedId) + ")>=0"));
            act(click("[data-action=clear-queue]"));
            assertEquals("Cleared queue stays empty", "0", js("nativeCommand('queue').ids.length"));
            assertEquals("No catalog changes", trackCount, app.library.all().size());
            assertEquals("No scan", scan, app.library.scanStatus().toString());
            evidence.put("queueCount", queue.length()).put("hiddenCount", hidden.size()).put("shuffleCycleCoverage", expected.size())
                .put("manualUniqueSongs", manualIds.size()).put("batches", batches).put("passed", true);
        } finally {
            try {
                if (activity != null) {
                    act("window.nativeCommand=function(){return {}}");
                    getInstrumentation().runOnMainSync(() -> { activity.web.loadUrl("about:blank"); activity.finish(); });
                    getInstrumentation().waitForIdleSync();
                }
            } finally {
                getInstrumentation().runOnMainSync(() -> {
                    try {
                        app.playback.player.pause(); app.save("ui", savedUi); app.playback.syncQueue(savedUi);
                        // Retain the exact original native queue, even if it differed from saved preferences.
                        app.playback.player.setMediaItems(savedQueue, Math.max(0, current[0]), position[0]);
                        for (Map.Entry<String, Object> item : savedFields.entrySet()) field(item.getKey()).set(app.playback, item.getValue());
                        field("stats").set(app.playback, new JSONObject(savedStats.toString()));
                        field("lastStats").setLong(app.playback, SystemClock.elapsedRealtime());
                        field("lastSave").setLong(app.playback, SystemClock.elapsedRealtime());
                        app.playback.player.setVolume(volume[0]); app.save("ui", savedUi); app.save("tools", savedTools);
                        if (playing[0]) app.playback.player.prepare();
                        app.playback.player.setPlayWhenReady(playing[0]);
                    } catch (Exception e) { throw new RuntimeException(e); }
                });
                evidence.put("uiRestored", savedUi.toString().equals(app.settings("ui").toString()))
                    .put("toolsRestored", savedTools.toString().equals(app.settings("tools").toString()))
                    .put("audioPreserved", savedAudio.toString().equals(app.settings("audio").toString()))
                    .put("scanPreserved", scan.equals(app.library.scanStatus().toString()));
                try (FileOutputStream out = new FileOutputStream(new File(directory, "results.json"))) {
                    out.write(evidence.toString(2).getBytes("UTF-8"));
                }
                assertTrue("UI restored", evidence.getBoolean("uiRestored"));
                assertTrue("Tools restored", evidence.getBoolean("toolsRestored"));
                assertTrue("Audio settings retained", evidence.getBoolean("audioPreserved"));
                assertTrue("Scan retained", evidence.getBoolean("scanPreserved"));
            }
        }
    }

    public void testInspectInstalledQueue() throws Exception {
        activity = (MainActivity) getInstrumentation().startActivitySync(new Intent(
            getInstrumentation().getTargetContext(), MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        try {
            waitJs("!!window.M3X_UI && !!nativeCommand('poll',{stats:true}).stats");
            JSONObject ui = PlayerApp.instance.settings("ui");
            android.util.Log.i("M3X.LibraryPlayback", "baseline=" + js(
                "({tracks:M3X_UI.tracks.length,queue:nativeCommand('queue').ids.length,"
                + "rendered:document.querySelectorAll('[data-list=library] [data-track]').length,"
                + "mode:JSON.parse(localStorage.getItem('m3x-shiny-preview-v1')).mode,"
                + "userAgent:navigator.userAgent})"));
            android.util.Log.i("M3X.LibraryPlayback", "savedQueue=" +
                (ui.optJSONArray("queue") == null ? 0 : ui.getJSONArray("queue").length()));
            getInstrumentation().runOnMainSync(() -> {
                androidx.media3.common.Timeline timeline = PlayerApp.instance.playback.player.getCurrentTimeline();
                java.util.Set<Integer> visited = new java.util.HashSet<>();
                int index = timeline.getFirstWindowIndex(true);
                while (index != androidx.media3.common.C.INDEX_UNSET && visited.add(index)) {
                    index = timeline.getNextWindowIndex(index, androidx.media3.common.Player.REPEAT_MODE_OFF, true);
                }
                android.util.Log.i("M3X.LibraryPlayback", "shuffleCoverage=" + visited.size());
            });
        } finally {
            getInstrumentation().runOnMainSync(() -> activity.finish());
        }
    }
}
