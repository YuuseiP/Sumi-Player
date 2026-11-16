package cn.yuusei.m3x;

import android.content.Intent;
import android.os.SystemClock;
import android.test.InstrumentationTestCase;
import java.io.File;
import java.io.FileOutputStream;
import java.lang.reflect.Field;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.json.JSONArray;
import org.json.JSONObject;

/** Real local timed lyrics, paused seeking, physical screenshots; restores playback and settings. */
@androidx.media3.common.util.UnstableApi
public final class LyricMotionDeviceTest extends InstrumentationTestCase {
    private MainActivity activity;
    private File directory;
    private String js(String code) throws Exception {
        CountDownLatch latch = new CountDownLatch(1); String[] value = {""};
        getInstrumentation().runOnMainSync(() -> activity.web.evaluateJavascript(code, r -> { value[0] = r; latch.countDown(); }));
        assertTrue(latch.await(20, TimeUnit.SECONDS)); return value[0];
    }
    private void act(String code) throws Exception {
        assertEquals("JS action", "\"ok\"", js("(function(){try{" + code + ";return 'ok'}catch(e){return String(e)}})()"));
    }
    private void waitJs(String code) throws Exception {
        long end = SystemClock.elapsedRealtime() + 30000;
        while (SystemClock.elapsedRealtime() < end) { if ("true".equals(js(code))) return; Thread.sleep(80); }
        fail("Timed out: " + code + " / " + js("JSON.stringify({position:document.getElementById('audio').currentTime,offset:document.getElementById('lyric-offset').value,active:Array.from(document.querySelectorAll('#lyrics-lines button')).findIndex(function(n){return n.getAttribute('aria-current')==='true'}),native:nativeCommand('poll').position})"));
    }
    private void click(String selector) throws Exception { act("document.querySelector(" + JSONObject.quote(selector) + ").click()"); }
    private Field field(String name) throws Exception { Field f = PlaybackService.class.getDeclaredField(name); f.setAccessible(true); return f; }
    private void capture(String id) throws Exception {
        Thread.sleep(550);
        android.graphics.Bitmap bitmap = getInstrumentation().getUiAutomation().takeScreenshot(); assertNotNull(bitmap);
        try (FileOutputStream out = new FileOutputStream(new File(directory, id + ".png"))) { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, out); }
        finally { bitmap.recycle(); }
    }
    private void seek(int index) throws Exception {
        // Sample within the line; millisecond truncation at an exact fractional boundary can select its predecessor.
        act("document.querySelectorAll('#lyrics-lines button')[" + index + "].click();document.getElementById('audio').currentTime+=.05");
        waitJs("document.querySelectorAll('#lyrics-lines button')[" + index + "].getAttribute('aria-current')==='true'");
    }
    private void centered() throws Exception {
        waitJs("(function(){var b=document.getElementById('ambient-lyrics').getBoundingClientRect(),l=document.querySelector('#ambient-lines [aria-current=true]').getBoundingClientRect();return Math.abs(l.top+l.height/2-b.top-b.height/2)<3})()");
    }
    public void testTimedLyricMotionAndRestore() throws Exception {
        assertEquals("Physical M3X", "Shanling M3X", android.os.Build.MODEL.replace('_', ' '));
        PlayerApp app = PlayerApp.instance; app.awaitLibrary();
        JSONObject savedUi = new JSONObject(app.settings("ui").toString()), savedTools = new JSONObject(app.settings("tools").toString());
        JSONObject savedAudio = new JSONObject(app.settings("audio").toString());
        activity = (MainActivity)getInstrumentation().startActivitySync(new Intent(getInstrumentation().getTargetContext(), MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        waitJs("!!window.M3X_AMBIENT&&M3X_UI.tracks.length>0&&!!nativeCommand('poll').stats");
        JSONObject original = new JSONObject(js("nativeCommand('poll')"));
        assertEquals("No sleep timer to interrupt", 0, original.optLong("sleepRemaining"));
        String queue = js("nativeCommand('queue').ids"), scan = app.library.scanStatus().toString();
        directory = new File(app.getExternalFilesDir(null), "lyric-motion"); assertTrue(directory.isDirectory() || directory.mkdirs());
        JSONObject report = new JSONObject().put("device", android.os.Build.MODEL).put("source", "real local LRC; paused seeks, no lyric file writes");
        Map<String,Object> savedFields = new LinkedHashMap<>();
        for (String name : new String[]{"manualGain","rampStart","pauseAfterRamp","rampFrom","rampTarget","fadeTrackStart","listenedMs","counted","previousAlbum","statsPlaying","queueLibrary","queueSelection","preferenceCarryId","preferenceOnceId"}) savedFields.put(name, field(name).get(app.playback));
        JSONObject stats = new JSONObject(original.getJSONObject("stats").toString());
        long[] position = {0}; float[] volume = {1}; int[] queueCount = {0};
        getInstrumentation().runOnMainSync(() -> { position[0] = app.playback.player.getCurrentPosition(); volume[0] = app.playback.player.getVolume(); queueCount[0] = app.playback.player.getMediaItemCount(); });
        try {
            String candidate = null;
            for (JSONObject track : app.library.all()) {
                if (!track.optString("title").contains("SNOW FLAKES MEMORIES") || PreferenceRules.settings(savedUi).excludes(track)) continue;
                if (app.library.details(track.getString("id")).optString("lyrics").contains("[")) { candidate = track.getString("id"); break; }
            }
            assertNotNull("Existing local song with timed lyrics", candidate);
            final String song = candidate;
            getInstrumentation().runOnMainSync(() -> { app.playback.player.pause(); app.playback.load(song, false); });
            waitJs("M3X_UI.currentTrack().id===" + JSONObject.quote(song) + "&&document.querySelectorAll('#lyrics-lines button').length>20");
            act("var e=document.querySelector('[data-setting=reduceMotion]');e.checked=false;e.dispatchEvent(new Event('change',{bubbles:true}))");
            click(".m3x-nav [data-page=player]"); seek(8);
            report.put("trackId", song).put("lyricLines", Integer.parseInt(js("document.querySelectorAll('#lyrics-lines button').length")));
            click("[data-action=ambient-mode]"); waitJs("M3X_AMBIENT.active()&&document.querySelector('#ambient-lines [aria-current=true]')!==null");
            if ("true".equals(js("innerWidth>innerHeight"))) click("#ambient-rotate");
            waitJs("innerHeight>innerWidth"); click("#ambient-display"); centered();
            capture("portrait");
            // Observe the actual timestamp update path across a distant seek. No synthetic lyrics or aria-current writes.
            act("window.__samples=[];window.__sampleDone=false;var start=performance.now();function sample(now){__samples.push({t:now-start,y:document.getElementById('ambient-lyrics').scrollTop});if(now-start<1400)requestAnimationFrame(sample);else __sampleDone=true}requestAnimationFrame(sample);document.querySelectorAll('#lyrics-lines button')[16].click();document.getElementById('audio').currentTime+=.05");
            waitJs("__sampleDone"); centered();
            assertEquals("Multiple intermediate scroll positions", "true", js("new Set(__samples.map(function(s){return Math.round(s.y)})).size>=4"));
            assertEquals("Current lyric glow", "true", js("getComputedStyle(document.querySelector('#ambient-lines [aria-current=true]')).textShadow!=='none'"));
            assertEquals("Active lyric readable", "\"1\"", js("getComputedStyle(document.querySelector('#ambient-lines [aria-current=true]')).opacity"));
            assertEquals("Cover remains full width", "true", js("document.getElementById('ambient-cover').clientWidth===innerWidth"));
            report.put("scrollSamples", new JSONArray(js("__samples")));
            click("#ambient-display"); click("#ambient-rotate"); waitJs("innerWidth>innerHeight");
            capture("landscape-controls"); click("#ambient-display"); centered(); capture("landscape");
            assertEquals("Cover remains full height", "true", js("document.getElementById('ambient-cover').clientHeight===innerHeight"));
            // User scrolling must immediately stop automatic movement, then resume follow after the reading hold.
            seek(18);
            act("document.getElementById('ambient-lyrics').dispatchEvent(new Event('touchstart'));window.__held=document.getElementById('ambient-lyrics').scrollTop");
            Thread.sleep(550);
            assertEquals("Touch stops follow", js("__held"), js("document.getElementById('ambient-lyrics').scrollTop"));
            Thread.sleep(2600); centered();
            act("M3X_MOTION.configure(true)"); seek(20); centered();
            assertEquals("Reduced transforms", "\"none\"", js("getComputedStyle(document.querySelector('#ambient-lines [aria-current=true]')).transform"));
            click("#ambient-display"); click("#ambient-exit");
            act("M3X_MOTION.configure(false)"); click(".m3x-cover-open"); seek(22); Thread.sleep(550);
            capture("lyrics-page");
            assertEquals("Normal lyrics glow", "true", js("getComputedStyle(document.querySelector('#lyrics-lines [aria-current=true]')).textShadow!=='none'"));
            assertEquals("No scan", scan, app.library.scanStatus().toString());
            assertEquals("No automatic playback", "true", js("document.getElementById('audio').paused"));
            report.put("passed", true);
        } finally {
            try { act("M3X_AMBIENT.exit();window.nativeCommand=function(){return {}}"); }
            finally {
                getInstrumentation().runOnMainSync(() -> { activity.web.loadUrl("about:blank"); activity.finish(); }); getInstrumentation().waitForIdleSync();
                getInstrumentation().runOnMainSync(() -> { try {
                    app.playback.player.pause(); app.playback.load(original.getString("id"), false); app.playback.player.seekTo(position[0]);
                    if (app.playback.player.getMediaItemCount() > queueCount[0]) app.playback.player.removeMediaItems(queueCount[0], app.playback.player.getMediaItemCount());
                    JSONArray restoredQueue = new JSONArray(); for (int i = 0; i < app.playback.player.getMediaItemCount(); i++) restoredQueue.put(app.playback.player.getMediaItemAt(i).mediaId);
                    report.put("queueRestored", queue.equals(restoredQueue.toString())).put("restoredTrackId", app.playback.currentTrack().getString("id")).put("restoredPositionMs", app.playback.player.getCurrentPosition());
                    for (Map.Entry<String,Object> entry : savedFields.entrySet()) field(entry.getKey()).set(app.playback, entry.getValue());
                    field("stats").set(app.playback, new JSONObject(stats.toString())); field("lastStats").setLong(app.playback, SystemClock.elapsedRealtime()); field("lastSave").setLong(app.playback, SystemClock.elapsedRealtime());
                    app.playback.player.setVolume(volume[0]); app.save("ui", savedUi); app.save("tools", savedTools); app.playback.player.setPlayWhenReady(!original.getBoolean("paused"));
                } catch (Exception e) { throw new RuntimeException(e); } });
                report.put("uiRestored", savedUi.toString().equals(app.settings("ui").toString())).put("toolsRestored", savedTools.toString().equals(app.settings("tools").toString())).put("audioPreserved", savedAudio.toString().equals(app.settings("audio").toString()));
                try (FileOutputStream out = new FileOutputStream(new File(directory, "verification.json"))) { out.write(report.toString(2).getBytes("UTF-8")); }
                assertTrue("Physical playback queue restored", report.getBoolean("queueRestored"));
                assertTrue("Settings restored", report.getBoolean("uiRestored") && report.getBoolean("toolsRestored") && report.getBoolean("audioPreserved"));
                assertEquals("Original track restored", original.getString("id"), report.getString("restoredTrackId"));
                assertTrue("Original position restored", Math.abs(position[0] - report.getLong("restoredPositionMs")) < 100);
            }
        }
    }
}
