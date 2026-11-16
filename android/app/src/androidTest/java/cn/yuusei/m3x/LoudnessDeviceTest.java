package cn.yuusei.m3x;

import android.content.Intent;
import android.os.SystemClock;
import android.test.InstrumentationTestCase;
import androidx.media3.common.MediaItem;
import java.io.File;
import java.io.FileOutputStream;
import java.lang.reflect.Field;
import java.util.*;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.json.JSONArray;
import org.json.JSONObject;

/**
 * Loudness normalisation on the physical M3X. Stays paused throughout (no sound), never scans
 * or edits tags, and restores queue, position, UI history and audio settings afterwards.
 */
@androidx.media3.common.util.UnstableApi
public final class LoudnessDeviceTest extends InstrumentationTestCase {
    private MainActivity activity;
    private PlayerApp app;

    private String js(String code) throws Exception {
        CountDownLatch latch = new CountDownLatch(1);
        String[] value = {""};
        getInstrumentation().runOnMainSync(() -> activity.web.evaluateJavascript(code, result -> { value[0] = result; latch.countDown(); }));
        assertTrue("WebView callback", latch.await(20, TimeUnit.SECONDS));
        return value[0];
    }

    private void waitJs(String code) throws Exception {
        long end = SystemClock.elapsedRealtime() + 30000;
        while (SystemClock.elapsedRealtime() < end) { if ("true".equals(js(code))) return; Thread.sleep(100); }
        fail("Timed out: " + code);
    }

    private JSONObject loudness() throws Exception {
        JSONObject[] state = {null};
        getInstrumentation().runOnMainSync(() -> state[0] = app.playback.snapshot().optJSONObject("loudness"));
        return state[0];
    }

    private float volume() {
        float[] value = {0};
        getInstrumentation().runOnMainSync(() -> value[0] = app.playback.player.getVolume());
        return value[0];
    }

    private JSONObject settle(long timeoutMs) throws Exception {
        long end = SystemClock.elapsedRealtime() + timeoutMs;
        JSONObject state = loudness();
        while (SystemClock.elapsedRealtime() < end) {
            state = loudness();
            String source = state.optString("source");
            if (source.equals("measured") || source.equals("tag") || source.equals("replaygain") || source.equals("off")
                    || source.equals("unavailable")) { Thread.sleep(150); return loudness(); }
            Thread.sleep(50);
        }
        return state;
    }

    private static String extension(JSONObject track) {
        if (track.has("cueStart")) return "cue";
        String name = track.optString("filename", track.optString("uri")).toLowerCase(Locale.ROOT);
        int dot = name.lastIndexOf('.');
        return dot < 0 ? "" : name.substring(dot + 1);
    }

    public void testNormalisesLocalFormatsWhilePaused() throws Exception {
        assertEquals("Physical M3X", "Shanling M3X", android.os.Build.MODEL.replace('_', ' '));
        app = PlayerApp.instance;
        app.awaitLibrary();
        activity = (MainActivity) getInstrumentation().startActivitySync(new Intent(
            getInstrumentation().getTargetContext(), MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        waitJs("!!window.M3X_UI && !!nativeCommand('poll').settings && M3X_UI.tracks.length>0");
        Field restoring = PlaybackService.class.getDeclaredField("restoring"); restoring.setAccessible(true);
        for (long end = SystemClock.elapsedRealtime() + 30000; restoring.getBoolean(app.playback) && SystemClock.elapsedRealtime() < end; ) Thread.sleep(100);
        assertFalse("Playback restored", restoring.getBoolean(app.playback));
        JSONObject savedUi = new JSONObject(app.settings("ui").toString());
        JSONObject savedAudio = new JSONObject(app.settings("audio").toString());
        Field settingsField = PlaybackService.class.getDeclaredField("settings"); settingsField.setAccessible(true);
        JSONObject savedSettings = (JSONObject) settingsField.get(app.playback);
        List<MediaItem> savedQueue = new ArrayList<>();
        long[] position = {0}; int[] current = {0}; boolean[] playing = {false};
        getInstrumentation().runOnMainSync(() -> {
            for (int i = 0; i < app.playback.player.getMediaItemCount(); i++) savedQueue.add(app.playback.player.getMediaItemAt(i));
            current[0] = app.playback.player.getCurrentMediaItemIndex(); position[0] = app.playback.player.getCurrentPosition();
            playing[0] = app.playback.player.getPlayWhenReady();
            app.playback.player.pause();
        });
        File directory = new File(app.getExternalFilesDir(null), "loudness-verification");
        assertTrue(directory.isDirectory() || directory.mkdirs());
        JSONObject evidence = new JSONObject().put("device", android.os.Build.MODEL).put("defaultNormalize", savedAudio.optBoolean("normalize", true));
        JSONArray results = new JSONArray();
        try {
            assertTrue("Loudness normalisation defaults to on", savedAudio.optBoolean("normalize", true));
            // Up to three online songs from the current queue, plus one local song per format (and a CUE
            // sub-track) from the library. DSD is skipped because loading it would start a PCM conversion.
            PreferenceRules.Settings preferences = PreferenceRules.settings(savedUi);
            Map<String, String> picks = new LinkedHashMap<>();
            int online = 0;
            for (MediaItem item : savedQueue) {
                JSONObject track = app.library.track(item.mediaId);
                if (track != null && track.optString("uri").startsWith(NeteaseClient.SCHEME + ":") && online < 3) picks.put("netease#" + ++online, track.optString("id"));
            }
            for (JSONObject track : app.library.all()) {
                String uri = track.optString("uri");
                if ((!uri.startsWith("file:") && !uri.startsWith("content:")) || track.optString("codec").equals("DSD") || preferences.excludes(track)) continue;
                JSONObject tags = track.optJSONObject("replayGain");
                String kind = extension(track) + (tags != null && tags.has("replaygain_track_gain") ? "+tag" : "");
                if (!picks.containsKey(kind) && picks.size() < 12) picks.put(kind, track.optString("id"));
            }
            assertFalse("Songs available", picks.isEmpty());
            evidence.put("kinds", new JSONArray(picks.keySet()));
            android.util.Log.i("M3X.LoudnessTest", "picks " + picks);
            Map<String, Integer> codecs = new TreeMap<>();
            for (JSONObject track : app.library.all()) codecs.merge(extension(track) + ":" + track.optString("codec"), 1, Integer::sum);
            evidence.put("libraryFormats", new JSONObject(codecs));
            android.util.Log.i("M3X.LoudnessTest", "library formats " + codecs);
            for (Map.Entry<String, String> pick : picks.entrySet()) {
                JSONObject track = app.library.track(pick.getValue());
                long started = SystemClock.elapsedRealtime();
                getInstrumentation().runOnMainSync(() -> { app.playback.load(pick.getValue(), false); app.playback.player.pause(); });
                JSONObject first = loudness();
                JSONObject state = settle(60000);
                long elapsed = SystemClock.elapsedRealtime() - started;
                float level = volume();
                JSONObject row = new JSONObject().put("kind", pick.getKey()).put("codec", track.optString("codec"))
                    .put("sampleRate", track.optInt("sampleRate")).put("bits", track.optInt("bits")).put("duration", track.optDouble("duration"))
                    .put("initialSource", first.optString("source")).put("loudness", state).put("elapsedMs", elapsed).put("volume", level);
                results.put(row);
                android.util.Log.i("M3X.LoudnessTest", row.toString());
                String source = state.optString("source");
                assertTrue(pick.getKey() + " resolved: " + state, source.equals("measured") || source.equals("tag") || source.equals("replaygain"));
                if (!source.equals("replaygain")) {
                    double expected = Math.pow(10, state.getDouble("gainDb") / 20);
                    assertEquals(pick.getKey() + " volume follows normalisation", expected, level, .015);
                    if (state.getDouble("lufs") > state.getDouble("target") + .2) assertTrue("Loud song is attenuated", level < .99);
                }
            }
            // Cached values apply instantly on revisit.
            String firstId = picks.values().iterator().next();
            getInstrumentation().runOnMainSync(() -> { app.playback.load(firstId, false); app.playback.player.pause(); });
            String revisit = loudness().optString("source");
            assertTrue("Revisit uses cache immediately: " + revisit, revisit.equals("measured") || revisit.equals("tag") || revisit.equals("replaygain"));
            evidence.put("revisitSource", revisit);
            // Switching off restores full volume; switching on re-applies.
            getInstrumentation().runOnMainSync(() -> { try { app.playback.configure(new JSONObject().put("normalize", false)); } catch (Exception e) { throw new RuntimeException(e); } });
            Thread.sleep(150);
            assertEquals("off", loudness().optString("source"));
            assertEquals("Disabled normalisation keeps original level", 1, volume(), .001);
            getInstrumentation().runOnMainSync(() -> { try { app.playback.configure(new JSONObject().put("normalize", true).put("normalizeTarget", -14)); } catch (Exception e) { throw new RuntimeException(e); } });
            Thread.sleep(150);
            JSONObject louder = loudness();
            assertEquals(-14, louder.getDouble("target"), 0);
            assertEquals(Math.pow(10, louder.getDouble("gainDb") / 20), volume(), .015);
            evidence.put("target14", louder);
            boolean rejected = false;
            try { app.playback.configure(new JSONObject().put("normalizeTarget", -30)); } catch (IllegalArgumentException expected) { rejected = true; }
            assertTrue("Unsupported targets are rejected", rejected);
            getInstrumentation().runOnMainSync(() -> { try { app.playback.configure(new JSONObject().put("normalizeTarget", -16)); } catch (Exception e) { throw new RuntimeException(e); } });
            // Physical screenshot of the setting for appearance review.
            js("(function(){document.querySelector('[data-action=output]').click();document.getElementById('processing-tab').click();return 1})()");
            waitJs("document.getElementById('output-dialog').open && !document.getElementById('processing-panel').hidden");
            Thread.sleep(800);
            android.graphics.Bitmap bitmap = getInstrumentation().getUiAutomation().takeScreenshot();
            if (bitmap != null) {
                try (FileOutputStream out = new FileOutputStream(new File(directory, "processing-panel.png"))) { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, out); }
                finally { bitmap.recycle(); }
            }
            js("(function(){var b=document.querySelector('[data-action=close-output]');if(b)b.click();return 1})()");
        } finally {
            evidence.put("results", results);
            try (FileOutputStream out = new FileOutputStream(new File(directory, "results.json"))) { out.write(evidence.toString(2).getBytes("UTF-8")); }
            getInstrumentation().runOnMainSync(() -> {
                try { settingsField.set(app.playback, savedSettings); } catch (Exception e) { throw new RuntimeException(e); }
                app.save("audio", savedAudio);
                app.playback.player.setMediaItems(savedQueue, Math.max(0, current[0]), position[0]);
                app.playback.player.prepare();
                app.playback.player.setPlayWhenReady(playing[0]);
            });
            Thread.sleep(300);
            getInstrumentation().runOnMainSync(() -> { app.save("ui", savedUi); app.playback.syncQueue(savedUi); });
        }
    }
}
