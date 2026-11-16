package cn.yuusei.m3x;

import android.content.Intent;
import android.os.SystemClock;
import android.test.InstrumentationTestCase;
import androidx.media3.common.MediaItem;
import androidx.media3.common.Player;
import java.io.*;
import java.lang.reflect.Field;
import java.util.*;
import java.util.concurrent.*;
import org.json.*;

/** Physical WebView + native/background queue acceptance, with all user state restored. */
@androidx.media3.common.util.UnstableApi
public final class PreferenceDeviceTest extends InstrumentationTestCase {
    private MainActivity activity;
    private String js(String code) throws Exception {
        CountDownLatch latch = new CountDownLatch(1); String[] result = {""};
        getInstrumentation().runOnMainSync(() -> activity.web.evaluateJavascript(code, value -> { result[0] = value; latch.countDown(); }));
        assertTrue("WebView callback", latch.await(20, TimeUnit.SECONDS)); return result[0];
    }
    private void act(String code) throws Exception {
        assertEquals("JS action: " + code, "\"ok\"", js("(function(){try{" + code + ";return 'ok'}catch(e){return String(e)}})()"));
    }
    private void waitJs(String code) throws Exception {
        long deadline = SystemClock.elapsedRealtime() + 45000;
        while (SystemClock.elapsedRealtime() < deadline) { if ("true".equals(js(code))) return; Thread.sleep(150); }
        fail("Timed out: " + code);
    }
    private String click(String selector) { return "document.querySelector(" + JSONObject.quote(selector) + ").click()"; }
    private void tap(String selector) throws Exception {
        assertTrue("Wake the physical display before touch verification", ((android.os.PowerManager)activity.getSystemService(android.content.Context.POWER_SERVICE)).isInteractive());
        assertFalse("Unlock the physical display before touch verification", ((android.app.KeyguardManager)activity.getSystemService(android.content.Context.KEYGUARD_SERVICE)).isKeyguardLocked());
        act("var e=document.querySelector(" + JSONObject.quote(selector) + "),p=e.closest('.m3x-scroll');if(p){var r=e.getBoundingClientRect(),v=p.getBoundingClientRect();p.scrollTop+=r.top+r.height/2-v.top-v.height/2}");
        Thread.sleep(300);
        assertEquals("Touch target must be unobscured: " + selector, "true", js("(function(){var e=document.querySelector(" + JSONObject.quote(selector) + "),r=e.getBoundingClientRect(),hit=document.elementFromPoint(r.left+r.width/2,r.top+r.height/2);return hit===e||e.contains(hit)})()"));
        JSONArray point = new JSONArray(js("(function(){var r=document.querySelector(" + JSONObject.quote(selector) + ").getBoundingClientRect();return [(r.left+r.width/2)*devicePixelRatio,(r.top+r.height/2)*devicePixelRatio]})()"));
        act("window.preferenceTouchLog=[];if(!window.preferenceTouchLogging){window.preferenceTouchLogging=true;['touchstart','touchend','click','change'].forEach(function(type){document.addEventListener(type,function(e){window.preferenceTouchLog.push({type:type,tag:e.target.tagName,category:e.target.dataset.preferenceCategory,checked:e.target.checked,x:e.changedTouches?e.changedTouches[0].clientX:e.clientX,y:e.changedTouches?e.changedTouches[0].clientY:e.clientY})},true)})}");
        int[] origin = new int[2]; getInstrumentation().runOnMainSync(() -> activity.web.getLocationOnScreen(origin));
        long down = SystemClock.uptimeMillis();
        for (int action : new int[]{android.view.MotionEvent.ACTION_DOWN, android.view.MotionEvent.ACTION_UP}) {
            android.view.MotionEvent event = android.view.MotionEvent.obtain(down, SystemClock.uptimeMillis(), action, (float)point.getDouble(0)+origin[0], (float)point.getDouble(1)+origin[1], 0);
            event.setSource(android.view.InputDevice.SOURCE_TOUCHSCREEN);
            try { assertTrue("Physical touch injection", getInstrumentation().getUiAutomation().injectInputEvent(event, true)); } finally { event.recycle(); }
            Thread.sleep(80);
        }
        Thread.sleep(700);
        System.out.println("Touch " + selector + " at " + point + " origin=" + Arrays.toString(origin) + " events=" + js("window.preferenceTouchLog") + " native=" + js("JSON.parse(localStorage.getItem('m3x-shiny-preview-v1')).preferences"));
    }
    private Field field(String name) throws Exception { Field field = PlaybackService.class.getDeclaredField(name); field.setAccessible(true); return field; }
    private void capture(File directory, String name) throws Exception {
        Thread.sleep(300);
        android.graphics.Bitmap image = getInstrumentation().getUiAutomation().takeScreenshot(); assertNotNull(image);
        boolean varied = false; int first = image.getPixel(0, 0);
        for (int y = 0; y < image.getHeight(); y += 32) for (int x = 0; x < image.getWidth(); x += 32) if (image.getPixel(x, y) != first) varied = true;
        assertTrue("Physical display must be awake and show content", varied);
        try (FileOutputStream out = new FileOutputStream(new File(directory, name + ".png"))) { image.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, out); }
        finally { image.recycle(); }
    }
    private void configure(PlayerApp app, JSONObject ui) throws Exception {
        act("window.nativeCommand=function(){return {}}");
        getInstrumentation().runOnMainSync(() -> {
            try {
                app.playback.player.pause(); field("preferenceCarryId").set(app.playback, ""); field("preferenceOnceId").set(app.playback, "");
                app.save("ui", ui); app.playback.syncQueue(ui); activity.web.reload();
            } catch (Exception e) { throw new RuntimeException(e); }
        });
        waitJs("!!window.M3X_UI && !!nativeCommand('poll').stats && !!M3X_UI.library().preferenceRules");
    }
    private Set<String> queue(PlayerApp app) {
        Set<String> result = new HashSet<>();
        getInstrumentation().runOnMainSync(() -> { for (int i = 0; i < app.playback.player.getMediaItemCount(); i++) result.add(app.playback.player.getMediaItemAt(i).mediaId); });
        return result;
    }
    public void testInspectUserPreferences() throws Exception {
        PlayerApp app = PlayerApp.instance; app.awaitLibrary();
        activity = (MainActivity) getInstrumentation().startActivitySync(new Intent(getInstrumentation().getTargetContext(), MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        waitJs("!!window.M3X_UI && !!nativeCommand('poll').stats");
        JSONObject ui = new JSONObject(app.settings("ui").toString());
        PreferenceRules.Settings settings = PreferenceRules.settings(ui);
        JSONObject result = new JSONObject().put("preferences", settings.jsonValue()).put("trackId", ui.optString("trackId"));
        JSONObject counts = new JSONObject(); JSONArray unexpected = new JSONArray(); int excluded = 0;
        for (String category : PreferenceRules.CATEGORIES) counts.put(category, 0);
        Set<String> queue = queue(app);
        for (JSONObject track : app.library.all()) {
            JSONArray found = track.getJSONObject("preference").getJSONArray("categories");
            for (int i = 0; i < found.length(); i++) counts.put(found.getString(i), counts.getInt(found.getString(i)) + 1);
            if (settings.excludes(track)) { excluded++; if (queue.contains(track.getString("id"))) unexpected.put(new JSONObject().put("id", track.getString("id")).put("title", track.getString("title"))); }
        }
        result.put("categories", counts).put("excluded", excluded).put("queueCount", queue.size()).put("excludedInQueue", unexpected);
        JSONArray unmatched = new JSONArray();
        for (JSONObject track : app.library.all()) if (!settings.excludes(track) &&
                (track.optString("title") + " " + track.optString("filename")).toLowerCase(Locale.ROOT).matches(".*(?:instrumental|off[ ._-]*vocal|solo|ソロ|カラオケ|ver[ ._-]|version).*")) {
            unmatched.put(new JSONObject().put("id", track.optString("id")).put("title", track.optString("title")).put("filename", track.optString("filename")).put("preference", track.getJSONObject("preference")));
        }
        result.put("unexcludedVersionNames", unmatched);
        File directory = new File(app.getExternalFilesDir(null), "preference-touch-diagnosis"); assertTrue(directory.isDirectory() || directory.mkdirs());
        result.put("webPreferences", new JSONObject(new JSONTokener(js("JSON.parse(localStorage.getItem('m3x-shiny-preview-v1')).preferences")).nextValue().toString()));
        result.put("webExcluded", Integer.parseInt(js("M3X_UI.tracks.filter(function(t){return M3X_PREFERENCES.excludes(t,JSON.parse(localStorage.getItem('m3x-shiny-preview-v1')).preferences)}).length")));
        result.put("webLibraryCount", new JSONTokener(js("document.querySelector('[data-text=trackCount]').textContent")).nextValue());
        try (FileOutputStream out = new FileOutputStream(new File(directory, "before.json"))) { out.write(result.toString(2).getBytes("UTF-8")); }
        getInstrumentation().runOnMainSync(() -> activity.finish());
    }
    public void testSettingsAndBackgroundExclusion() throws Exception {
        assertEquals("Shanling M3X", android.os.Build.MODEL.replace('_', ' '));
        PlayerApp app = PlayerApp.instance; app.awaitLibrary();
        activity = (MainActivity) getInstrumentation().startActivitySync(new Intent(getInstrumentation().getTargetContext(), MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        waitJs("!!window.M3X_UI && !!nativeCommand('poll').stats && M3X_UI.tracks.length>4000");
        JSONObject savedUi = new JSONObject(app.settings("ui").toString()), savedAudio = new JSONObject(app.settings("audio").toString()), savedTools = new JSONObject(app.settings("tools").toString());
        String scan = app.library.scanStatus().toString(); int count = app.library.all().size();
        Map<String,Object> savedFields = new LinkedHashMap<>();
        for (String name : new String[]{"manualGain", "rampStart", "pauseAfterRamp", "rampFrom", "rampTarget", "fadeTrackStart", "listenedMs", "counted", "previousAlbum", "statsPlaying", "settings", "preferenceCarryId", "preferenceOnceId"}) savedFields.put(name, field(name).get(app.playback));
        JSONObject savedStats = new JSONObject(((JSONObject)field("stats").get(app.playback)).toString());
        List<MediaItem> originalQueue = new ArrayList<>(); long[] position = {0}; int[] index = {0}; float[] volume = {1}; boolean[] playing = {false};
        getInstrumentation().runOnMainSync(() -> {
            for (int i = 0; i < app.playback.player.getMediaItemCount(); i++) originalQueue.add(app.playback.player.getMediaItemAt(i));
            index[0] = app.playback.player.getCurrentMediaItemIndex(); position[0] = app.playback.player.getCurrentPosition(); volume[0] = app.playback.player.getVolume(); playing[0] = app.playback.player.getPlayWhenReady();
            app.playback.player.pause(); app.playback.player.setVolume(0);
            try { JSONObject quiet = new JSONObject(savedAudio.toString()).put("fades", false).put("albumTransitions", false); field("settings").set(app.playback, quiet); field("manualGain").setFloat(app.playback, 0); field("rampStart").setLong(app.playback, 0); field("pauseAfterRamp").setBoolean(app.playback, false); }
            catch (Exception e) { throw new RuntimeException(e); }
            activity.getWindow().addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON | android.view.WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON);
        });
        File directory = new File(app.getExternalFilesDir(null), "preference-verification"); assertTrue(directory.isDirectory() || directory.mkdirs());
        JSONObject evidence = new JSONObject().put("device", android.os.Build.MODEL).put("tracks", count);
        try {
            JSONObject base = new JSONObject(savedUi.toString()).put("preferences", new PreferenceRules.Settings(null).jsonValue()).put("hidden", new JSONArray()).put("queue", new JSONArray()).put("queueCleared", false).put("queueScope", "library").put("mode", 0).put("reduceMotion", true);
            configure(app, base);
            assertEquals(count, queue(app).size());
            act(click(".m3x-nav [data-page=profile]") + ";" + click("[data-page=settings]") + ";" + click("[data-settings-tab=preferences]"));
            assertEquals("6", js("document.querySelectorAll('[data-preference-category]').length"));
            assertEquals("0", js("document.querySelectorAll('[data-preference-category]:checked').length"));
            // Reproduce the user's exact song with real touchscreen events, not DOM .click().
            JSONObject reported = null;
            for (JSONObject track : app.library.all()) if (track.optString("title").equals("Spread the Wings!! (イルミネーションスターズ Ver.)")) { reported = track; break; }
            assertNotNull("Reported song exists in the real library", reported);
            String reportedId = reported.getString("id");
            assertTrue(reported.getJSONObject("preference").getJSONArray("categories").toString().contains("group-named"));
            assertTrue(queue(app).contains(reportedId));
            assertEquals("true", js("M3X_PREFERENCES.normalize({enabledCategories:['group-named']}).enabledCategories.indexOf('group-named')>=0"));
            tap("[data-preference-category=group-named]");
            assertTrue(PreferenceRules.settings(app.settings("ui")).enabled.contains("group-named"));
            assertFalse("Reported song removed from native queue immediately", queue(app).contains(reportedId));
            act(click("[data-preference-preview=group-named]"));
            act("var e=document.getElementById('preference-search');e.value='Spread the Wings!!';e.dispatchEvent(new Event('input',{bubbles:true}))");
            assertEquals("true", js("!!document.querySelector(" + JSONObject.quote("[data-preference-id='" + reportedId + "']") + ")"));
            capture(directory, "reported-group-preview");
            act(click("#preference-preview-dialog [data-close]"));
            tap("#preference-hide");
            act(click(".m3x-nav [data-page=library]") + ";" + click("[data-filter=all]"));
            String reportedRow = "Array.prototype.some.call(document.querySelectorAll('[data-list=library] [data-track]'),function(e){return M3X_UI.tracks[Number(e.dataset.track)].id===" + JSONObject.quote(reportedId) + "})";
            assertEquals("Reported song hidden from actual library rows", "false", js(reportedRow));
            int groupQueueCount = queue(app).size();
            assertEquals(JSONObject.quote(groupQueueCount + " 首 · 已隐藏 " + (count - groupQueueCount) + " 首"), js("document.querySelector('[data-text=trackCount]').textContent"));
            capture(directory, "reported-group-hidden-library");
            JSONObject groupUi = new JSONObject(app.settings("ui").toString()).put("mode", 1);
            configure(app, groupUi);
            assertFalse("Group exclusion survives reload and full-library random mode", queue(app).contains(reportedId));
            for (int attempt = 0; attempt < 24; attempt++) {
                getInstrumentation().runOnMainSync(() -> {
                    app.playback.next(false);
                    assertNotNull(app.playback.currentTrack());
                    assertFalse("Random never selects a group version", PreferenceRules.settings(app.settings("ui")).excludes(app.playback.currentTrack()));
                });
            }
            assertEquals("true", js("JSON.parse(localStorage.getItem('m3x-shiny-preview-v1')).preferences.enabledCategories.indexOf('group-named')>=0"));
            evidence.put("reportedSong", reported.getString("title")).put("groupFilteredQueue", groupQueueCount).put("physicalTouchGroupRule", true).put("reportedSongHiddenAndShuffleExcluded", true);
            configure(app, base);
            act(click(".m3x-nav [data-page=profile]") + ";" + click("[data-page=settings]") + ";" + click("[data-settings-tab=preferences]"));
            for (String theme : new String[]{"classic", "endfield"}) for (String mode : new String[]{"light", "dark"}) {
                act("var e=document.querySelector('[data-setting=visualTheme]');e.value='" + theme + "';e.dispatchEvent(new Event('change',{bubbles:true}));var m=document.querySelector('[data-setting=theme]');m.value='" + mode + "';m.dispatchEvent(new Event('change',{bubbles:true}))");
                capture(directory, theme + "-" + mode + "-preferences");
            }
            act(click("[data-preference-category=backing]"));
            assertTrue(app.settings("ui").getJSONObject("preferences").getJSONArray("enabledCategories").toString().contains("backing"));
            PreferenceRules.Settings preferences = PreferenceRules.settings(app.settings("ui"));
            Set<String> expected = new HashSet<>(); JSONObject backing = null, normal = null, second = null;
            JSONObject categories = new JSONObject();
            for (String category : PreferenceRules.CATEGORIES) categories.put(category, 0);
            for (JSONObject track : app.library.all()) {
                JSONArray found = track.getJSONObject("preference").getJSONArray("categories");
                for (int i = 0; i < found.length(); i++) categories.put(found.getString(i), categories.getInt(found.getString(i)) + 1);
                if (preferences.excludes(track)) { if (backing == null && !track.has("cueStart") && track.optString("codec").equals("FLAC")) backing = track; }
                else { expected.add(track.getString("id")); if (track.optString("codec").equals("FLAC")) { if (normal == null) normal = track; else if (second == null) second = track; } }
            }
            assertNotNull(backing); assertNotNull(normal); assertNotNull(second);
            assertEquals("Native queue matches UI classifier", expected, queue(app)); evidence.put("categoryCounts", categories).put("backingFilteredQueue", expected.size());
            act("document.getElementById('preferences-panel').scrollTop=9999"); capture(directory, "preferences-exceptions-and-visibility");
            String backingId = backing.getString("id"), normalId = normal.getString("id"), secondId = second.getString("id");
            act(click("[data-preference-preview=backing]"));
            waitJs("document.querySelectorAll('.m3x-preference-song').length===30"); capture(directory, "backing-preview");
            act("var e=document.getElementById('preference-search');e.value=" + JSONObject.quote(backing.optString("title")) + ";e.dispatchEvent(new Event('input',{bubbles:true}))");
            act(click("[data-preference-mode=keep][data-preference-id='" + backingId + "']"));
            assertTrue(queue(app).contains(backingId));
            act(click("[data-preference-mode=default][data-preference-id='" + backingId + "']"));
            assertFalse(queue(app).contains(backingId));
            act(click("#preference-preview-dialog [data-close]"));
            act(click(".m3x-nav [data-page=library]") + ";" + click("[data-filter=all]") + ";var e=document.querySelector('.m3x-search');e.value=" + JSONObject.quote(backing.optString("title")) + ";e.dispatchEvent(new Event('input',{bubbles:true}))");
            // Locate by stable track ID rather than relying on the order of the catalog.
            String backingRow = "Array.prototype.some.call(document.querySelectorAll('[data-list=library] [data-track]'),function(e){return M3X_UI.tracks[Number(e.dataset.track)].id===" + JSONObject.quote(backingId) + "})";
            assertEquals("Excluded songs stay discoverable by default", "true", js(backingRow));
            act(click(".m3x-nav [data-page=profile]") + ";" + click("[data-page=settings]") + ";" + click("[data-settings-tab=preferences]") + ";" + click("#preference-hide") + ";" + click(".m3x-nav [data-page=library]"));
            assertEquals("Optional library hiding", "false", js(backingRow));
            act(click(".m3x-nav [data-page=profile]") + ";" + click("[data-page=settings]") + ";" + click("[data-settings-tab=preferences]") + ";" + click("#preference-hide") + ";" + click(".m3x-nav [data-page=library]"));
            assertEquals("Disabling hiding restores the same song", "true", js(backingRow));
            evidence.put("optionalLibraryHiding", true);
            // Direct clicks must ask before loading, and one-time permission must disappear after transition.
            act("M3X_UI.showTrack(" + JSONObject.quote(backingId) + ")"); waitJs("document.getElementById('preference-play-dialog').open"); capture(directory, "excluded-manual-play");
            act(click("#preference-play-once"));
            waitJs("nativeCommand('poll').id===" + JSONObject.quote(backingId) + " && nativeCommand('poll').preferenceContinuation===true");
            getInstrumentation().runOnMainSync(() -> app.playback.next(false));
            waitJs("nativeCommand('poll').id!==" + JSONObject.quote(backingId) + " && nativeCommand('queue').ids.indexOf(" + JSONObject.quote(backingId) + ")<0");
            evidence.put("manualOnceRemovedAfterNext", true);
            // Raw playlist selection survives filtering, background next/previous, random and single repeat.
            JSONObject selection = new JSONObject(base.toString()).put("preferences", new JSONObject().put("enabledCategories", new JSONArray().put("backing"))).put("queueScope", "selection").put("queue", new JSONArray().put(normalId).put(backingId).put(secondId)).put("trackId", normalId);
            configure(app, selection);
            assertEquals(2, queue(app).size());
            getInstrumentation().runOnMainSync(() -> { app.playback.load(normalId, false); app.playback.next(false); });
            waitJs("nativeCommand('poll').id===" + JSONObject.quote(secondId));
            assertEquals(selection.getJSONArray("queue").toString(), app.settings("ui").getJSONArray("queue").toString());
            getInstrumentation().runOnMainSync(() -> app.playback.next(true)); waitJs("nativeCommand('poll').id===" + JSONObject.quote(normalId));
            for (int mode : new int[]{1, 2}) {
                final int selectedMode = mode;
                getInstrumentation().runOnMainSync(() -> { try { JSONObject ui = app.settings("ui"); ui.put("mode", selectedMode); app.save("ui", ui); app.playback.syncQueue(ui); } catch (Exception e) { throw new RuntimeException(e); } });
                assertFalse(queue(app).contains(backingId));
            }
            // Enabling while currently playing keeps only that song until it is left, even in repeat-one.
            JSONObject grace = new JSONObject(selection.toString()).put("preferences", new PreferenceRules.Settings(null).jsonValue()).put("mode", 2);
            configure(app, grace);
            getInstrumentation().runOnMainSync(() -> app.playback.load(backingId, true));
            waitJs("nativeCommand('poll').id===" + JSONObject.quote(backingId) + " && !nativeCommand('poll').paused");
            getInstrumentation().runOnMainSync(() -> { try { JSONObject ui = app.settings("ui"); ui.put("preferences", selection.getJSONObject("preferences")); app.save("ui", ui); app.playback.syncQueue(ui); } catch (Exception e) { throw new RuntimeException(e); } });
            assertEquals("true", js("nativeCommand('poll').preferenceContinuation"));
            getInstrumentation().runOnMainSync(() -> { assertEquals(Player.REPEAT_MODE_ALL, app.playback.player.getRepeatMode()); app.playback.next(false); });
            waitJs("nativeCommand('queue').ids.indexOf(" + JSONObject.quote(backingId) + ")<0");
            // Entirely excluded selection remains empty, never falls back to the full library.
            JSONObject empty = new JSONObject(selection.toString()).put("queue", new JSONArray().put(backingId));
            configure(app, empty); assertEquals(0, queue(app).size()); assertEquals("true", js("nativeCommand('poll').paused"));
            JSONObject onceOnly = new JSONObject(empty.toString()).put("preferences", new PreferenceRules.Settings(null).jsonValue()).put("mode", 2);
            configure(app, onceOnly);
            getInstrumentation().runOnMainSync(() -> app.playback.load(backingId, true));
            waitJs("nativeCommand('poll').id===" + JSONObject.quote(backingId) + " && !nativeCommand('poll').paused && nativeCommand('poll').ready");
            getInstrumentation().runOnMainSync(() -> {
                try {
                    JSONObject ui = app.settings("ui"); ui.put("preferences", selection.getJSONObject("preferences")); app.save("ui", ui); app.playback.syncQueue(ui);
                    assertEquals(Player.REPEAT_MODE_OFF, app.playback.player.getRepeatMode());
                    app.playback.player.seekTo(Math.max(0, app.playback.player.getDuration() - 100));
                } catch (Exception e) { throw new RuntimeException(e); }
            });
            waitJs("nativeCommand('queue').ids.length===0 && nativeCommand('poll').paused");
            evidence.put("lastExcludedSongFinishesWithoutRepeat", true);
            JSONObject restore = new JSONObject(empty.toString()).put("preferences", new PreferenceRules.Settings(null).jsonValue());
            configure(app, restore); assertEquals(Collections.singleton(backingId), queue(app));
            configure(app, selection); // Reload verifies persisted native settings are supplied to the WebView.
            assertEquals("true", js("JSON.parse(localStorage.getItem('m3x-shiny-preview-v1')).preferences.enabledCategories.indexOf('backing')>=0"));
            // All categories together, full-library shuffle, and exact keep override.
            JSONObject all = new JSONObject(base.toString()).put("mode", 1).put("preferences", new JSONObject().put("enabledCategories", new JSONArray(PreferenceRules.CATEGORIES)).put("keepTrackIds", new JSONArray().put(backingId)));
            configure(app, all); PreferenceRules.Settings allSettings = PreferenceRules.settings(all); expected.clear();
            for (JSONObject track : app.library.all()) if (!allSettings.excludes(track)) expected.add(track.getString("id"));
            assertEquals(expected, queue(app)); assertTrue(queue(app).contains(backingId));
            evidence.put("allRulesShuffleQueue", expected.size()).put("rawSelectionPreserved", true).put("emptySelectionNoFallback", true).put("persistenceReload", true).put("passed", true);
            assertEquals(count, app.library.all().size()); assertEquals(scan, app.library.scanStatus().toString());
        } finally {
            try { if (activity != null) { act("window.nativeCommand=function(){return {}}"); getInstrumentation().runOnMainSync(() -> { activity.web.loadUrl("about:blank"); activity.finish(); }); getInstrumentation().waitForIdleSync(); } }
            finally {
                getInstrumentation().runOnMainSync(() -> {
                    try {
                        app.playback.player.pause(); field("preferenceCarryId").set(app.playback, ""); field("preferenceOnceId").set(app.playback, ""); app.save("ui", savedUi); app.playback.syncQueue(savedUi);
                        app.playback.player.setMediaItems(originalQueue, Math.max(0, index[0]), position[0]);
                        for (Map.Entry<String,Object> entry : savedFields.entrySet()) field(entry.getKey()).set(app.playback, entry.getValue());
                        field("stats").set(app.playback, new JSONObject(savedStats.toString())); field("lastStats").setLong(app.playback, SystemClock.elapsedRealtime()); field("lastSave").setLong(app.playback, SystemClock.elapsedRealtime());
                        app.playback.player.setVolume(volume[0]); app.save("ui", savedUi); app.save("tools", savedTools);
                        if (playing[0]) app.playback.player.prepare(); app.playback.player.setPlayWhenReady(playing[0]);
                    } catch (Exception e) { throw new RuntimeException(e); }
                });
                evidence.put("uiRestored", savedUi.toString().equals(app.settings("ui").toString())).put("toolsRestored", savedTools.toString().equals(app.settings("tools").toString())).put("audioPreserved", savedAudio.toString().equals(app.settings("audio").toString())).put("scanPreserved", scan.equals(app.library.scanStatus().toString()));
                try (FileOutputStream out = new FileOutputStream(new File(directory, "results.json"))) { out.write(evidence.toString(2).getBytes("UTF-8")); }
                assertTrue(evidence.getBoolean("uiRestored")); assertTrue(evidence.getBoolean("toolsRestored")); assertTrue(evidence.getBoolean("audioPreserved")); assertTrue(evidence.getBoolean("scanPreserved"));
            }
        }
    }
    /** Apply the specific preference requested by the user after regression restores their settings. */
    public void testEnableReportedGroupPreference() throws Exception {
        PlayerApp app = PlayerApp.instance; app.awaitLibrary();
        activity = (MainActivity) getInstrumentation().startActivitySync(new Intent(getInstrumentation().getTargetContext(), MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        waitJs("!!window.M3X_UI && !!nativeCommand('poll').stats");
        act(click(".m3x-nav [data-page=profile]") + ";" + click("[data-page=settings]") + ";" + click("[data-settings-tab=preferences]"));
        if (!PreferenceRules.settings(app.settings("ui")).enabled.contains("group-named")) tap("[data-preference-category=group-named]");
        assertTrue(PreferenceRules.settings(app.settings("ui")).enabled.contains("group-named"));
        for (JSONObject track : app.library.all()) if (track.optString("title").equals("Spread the Wings!! (イルミネーションスターズ Ver.)")) {
            assertTrue(PreferenceRules.settings(app.settings("ui")).excludes(track));
            assertFalse(queue(app).contains(track.getString("id")));
        }
        File directory = new File(app.getExternalFilesDir(null), "preference-group-applied"); assertTrue(directory.isDirectory() || directory.mkdirs());
        capture(directory, "group-enabled");
        act(click(".m3x-nav [data-page=library]") + ";" + click("[data-filter=all]"));
        capture(directory, "library-after");
        JSONObject preferences = PreferenceRules.settings(app.settings("ui")).jsonValue();
        getInstrumentation().runOnMainSync(() -> activity.web.reload());
        waitJs("!!window.M3X_UI && !!nativeCommand('poll').stats");
        assertEquals("true", js("JSON.parse(localStorage.getItem('m3x-shiny-preview-v1')).preferences.enabledCategories.indexOf('group-named')>=0"));
        JSONObject result = new JSONObject().put("preferences", preferences).put("queueCount", queue(app).size()).put("reloaded", true);
        try (FileOutputStream out = new FileOutputStream(new File(directory, "applied.json"))) { out.write(result.toString(2).getBytes("UTF-8")); }
    }
}
