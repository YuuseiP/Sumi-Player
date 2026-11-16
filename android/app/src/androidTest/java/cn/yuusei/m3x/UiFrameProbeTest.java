package cn.yuusei.m3x;

import android.content.Context;
import android.content.Intent;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.SystemClock;
import android.test.InstrumentationTestCase;
import android.view.FrameMetrics;
import android.view.Window;
import android.view.accessibility.AccessibilityManager;
import java.io.File;
import java.io.FileOutputStream;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.json.JSONArray;
import org.json.JSONObject;

/**
 * Frame-pacing probe for the redesigned motion system on the physical M3X.
 * Each scenario performs one UI action and records rendered frames (FrameMetrics,
 * WebView draws through the app's RenderThread) and main-thread rAF intervals.
 * Never call getUiAutomation() here: connecting it enables accessibility, WebView
 * then maintains an accessibility tree and first frames become about three times
 * slower until the process ends, which is not what users see.
 * Existing catalog, no scan; playback is muted and the original song, position
 * and settings are restored.
 */
@androidx.media3.common.util.UnstableApi
public final class UiFrameProbeTest extends InstrumentationTestCase {
    private MainActivity activity;
    private final JSONArray samples = new JSONArray();
    private final List<Long> frameNanos = Collections.synchronizedList(new ArrayList<>());
    private String js(String code) throws Exception {
        CountDownLatch latch = new CountDownLatch(1); String[] result = {""};
        getInstrumentation().runOnMainSync(() -> activity.web.evaluateJavascript(code, r -> { result[0] = r; latch.countDown(); }));
        assertTrue("JS callback", latch.await(20, TimeUnit.SECONDS)); return result[0];
    }
    private void act(String code) throws Exception {
        assertEquals("JS action " + code, "\"ok\"", js("(function(){try{" + code + ";return 'ok'}catch(e){return String(e)}})()"));
    }
    private void waitJs(String code) throws Exception {
        long end = SystemClock.elapsedRealtime() + 30000;
        while (SystemClock.elapsedRealtime() < end) { if ("true".equals(js(code))) return; Thread.sleep(100); }
        fail("Timed out: " + code);
    }
    private static String click(String selector) { return "document.querySelector(" + JSONObject.quote(selector) + ").click()"; }
    private Field field(String name) throws Exception { Field f = PlaybackService.class.getDeclaredField(name); f.setAccessible(true); return f; }
    private void setting(String key, String value) throws Exception {
        act("var e=document.querySelector('[data-setting=" + key + "]');e.value=" + JSONObject.quote(value) + ";e.dispatchEvent(new Event('change',{bubbles:true}))");
    }
    private double percentile(List<Long> sorted, double fraction) {
        return sorted.isEmpty() ? -1 : sorted.get(Math.min(sorted.size() - 1, (int) (sorted.size() * fraction))) / 1e6;
    }
    /** Run one action, sample rAF for windowMs and summarize the rendered frames. */
    private JSONObject measure(String name, String action, int windowMs) throws Exception {
        Thread.sleep(250);
        frameNanos.clear();
        act("window.__probe=null;requestAnimationFrame(function(){var last=performance.now(),start=last,frames=[],handler=0,first=null;"
            + "function tick(){var now=performance.now();frames.push(+(now-last).toFixed(2));last=now;if(first===null)first=now-start;"
            + "if(now-start<" + windowMs + ")requestAnimationFrame(tick);else window.__probe={handlerMs:handler,firstFrameMs:first,intervals:frames};}"
            + "requestAnimationFrame(tick);" + action + ";handler=performance.now()-start;})");
        waitJs("window.__probe!==null");
        Thread.sleep(120); // Metrics are delivered asynchronously after a frame is presented.
        List<Long> frames; synchronized (frameNanos) { frames = new ArrayList<>(frameNanos); }
        Collections.sort(frames);
        int framesOver17 = 0, framesOver34 = 0; for (long nanos : frames) { if (nanos > 16_700_000) framesOver17++; if (nanos > 34_000_000) framesOver34++; }
        JSONObject sample = new JSONObject(js("__probe")).put("name", name);
        JSONArray intervals = sample.getJSONArray("intervals");
        int over20 = 0, over34 = 0; double worst = 0;
        for (int i = 1; i < intervals.length(); i++) { double v = intervals.getDouble(i); if (v > 20) over20++; if (v > 34) over34++; worst = Math.max(worst, v); }
        sample.put("rafOver20", over20).put("rafOver34", over34).put("rafWorst", worst).remove("intervals");
        sample.put("rafCount", intervals.length());
        sample.put("frames", frames.size()).put("framesOver17", framesOver17).put("framesOver34", framesOver34)
            .put("p50", percentile(frames, .5)).put("p90", percentile(frames, .9)).put("p95", percentile(frames, .95))
            .put("max", frames.isEmpty() ? -1 : frames.get(frames.size() - 1) / 1e6);
        samples.put(sample);
        return sample;
    }
    public void testFramePacing() throws Exception {
        assertEquals("Physical M3X", "Shanling M3X", android.os.Build.MODEL.replace('_', ' '));
        String label = ((android.test.InstrumentationTestRunner) getInstrumentation()).getArguments().getString("label", "probe");
        assertTrue(label.matches("[a-z0-9-]+"));
        PlayerApp app = PlayerApp.instance; app.awaitLibrary();
        activity = (MainActivity) getInstrumentation().startActivitySync(new Intent(getInstrumentation().getTargetContext(), MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        getInstrumentation().runOnMainSync(() -> activity.getWindow().addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON | android.view.WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON | android.view.WindowManager.LayoutParams.FLAG_DISMISS_KEYGUARD));
        waitJs("!!window.M3X_UI&&!!nativeCommand('poll',{stats:true}).stats&&M3X_UI.tracks.length>0");
        AccessibilityManager accessibility = (AccessibilityManager) app.getSystemService(Context.ACCESSIBILITY_SERVICE);
        assertFalse("Accessibility inflates WebView timing; a screen reader or UiAutomation is connected", accessibility.isEnabled());
        HandlerThread metricsThread = new HandlerThread("frame-metrics"); metricsThread.start();
        Window.OnFrameMetricsAvailableListener metrics = (window, frame, dropped) -> frameNanos.add(frame.getMetric(FrameMetrics.TOTAL_DURATION));
        getInstrumentation().runOnMainSync(() -> activity.getWindow().addOnFrameMetricsAvailableListener(metrics, new Handler(metricsThread.getLooper())));
        JSONObject savedUi = new JSONObject(app.settings("ui").toString()), savedTools = new JSONObject(app.settings("tools").toString()), savedAudio = new JSONObject(app.settings("audio").toString());
        JSONObject scan = new JSONObject(app.library.scanStatus().toString()); int tracks = app.library.all().size();
        JSONObject original = new JSONObject(js("nativeCommand('poll',{stats:true})"));
        File directory = new File(app.getExternalFilesDir(null), "frames-" + label); assertTrue(directory.isDirectory() || directory.mkdirs());
        JSONObject evidence = new JSONObject().put("label", label).put("device", android.os.Build.MODEL).put("tracks", tracks).put("samples", samples);
        Map<String, Object> savedFields = new LinkedHashMap<>();
        for (String name : new String[]{"manualGain", "rampStart", "pauseAfterRamp", "rampFrom", "rampTarget", "fadeTrackStart", "listenedMs", "counted", "previousAlbum", "statsPlaying"}) savedFields.put(name, field(name).get(app.playback));
        JSONObject savedStats = new JSONObject(original.getJSONObject("stats").toString());
        long[] position = {0}; float[] volume = {1};
        getInstrumentation().runOnMainSync(() -> { position[0] = app.playback.player.getCurrentPosition(); volume[0] = app.playback.player.getVolume(); });
        try {
            getInstrumentation().runOnMainSync(() -> { try { app.playback.player.pause(); field("manualGain").setFloat(app.playback, 0); field("rampStart").setLong(app.playback, 0); app.playback.player.setVolume(0); } catch (Exception e) { throw new RuntimeException(e); } });
            evidence.put("userAgent", new JSONArray("[" + js("navigator.userAgent") + "]").getString(0));
            for (String visual : new String[]{"classic", "endfield"}) {
                setting("visualTheme", visual); setting("theme", "light"); setting("layout", "album");
                act("var e=document.querySelector('[data-setting=reduceMotion]');if(e&&e.checked){e.checked=false;e.dispatchEvent(new Event('change',{bubbles:true}))}");
                for (String page : new String[]{"profile", "player", "library"}) { act(click(".m3x-nav [data-page=" + page + "]")); Thread.sleep(600); }
                for (int round = 0; round < 2; round++) for (String page : new String[]{"player", "profile", "library"})
                    measure(visual + "-tab-" + page, click(".m3x-nav [data-page=" + page + "]"), 700);
                act(click(".m3x-nav [data-page=profile]")); Thread.sleep(500);
                measure(visual + "-drill-settings", click("[data-page=settings]"), 700);
                measure(visual + "-back-profile", "M3X_UI.back()", 700);
                act(click(".m3x-nav [data-page=player]")); Thread.sleep(500);
                for (int round = 0; round < 2; round++) {
                    measure(visual + "-sheet-open", click(".m3x-output"), 700);
                    measure(visual + "-sheet-close", click("[data-action=close-output]"), 600);
                }
                measure(visual + "-lyrics-open", click("[data-page=lyrics]"), 700);
                measure(visual + "-lyrics-back", "M3X_UI.back()", 700);
                for (int round = 0; round < 2; round++) {
                    measure(visual + "-next-track", "M3X_UI.next();document.getElementById('audio').pause()", 900);
                }
                act(click(".m3x-nav [data-page=library]")); Thread.sleep(500);
                measure(visual + "-list-scroll", "(function(){var s=document.getElementById('library-browser'),t0=performance.now();function f(n){var p=Math.min(1,(n-t0)/600);s.scrollTop=p*1400;if(p<1)requestAnimationFrame(f)}requestAnimationFrame(f)})()", 800);
                act("document.getElementById('library-browser').scrollTop=0");
                measure(visual + "-theme-dark", "var e=document.querySelector('[data-setting=theme]');e.value='dark';e.dispatchEvent(new Event('change',{bubbles:true}))", 900);
                measure(visual + "-theme-light", "var e=document.querySelector('[data-setting=theme]');e.value='light';e.dispatchEvent(new Event('change',{bubbles:true}))", 900);
            }
            assertFalse("Accessibility stayed off", accessibility.isEnabled());
            assertEquals("Catalog unchanged", tracks, app.library.all().size());
            assertEquals("No scan", scan.toString(), app.library.scanStatus().toString());
            evidence.put("passed", true);
        } finally {
            getInstrumentation().runOnMainSync(() -> activity.getWindow().removeOnFrameMetricsAvailableListener(metrics)); metricsThread.quitSafely();
            try { act("window.nativeCommand=function(){return {}}"); getInstrumentation().runOnMainSync(() -> { activity.web.loadUrl("about:blank"); activity.finish(); }); getInstrumentation().waitForIdleSync(); }
            finally {
                getInstrumentation().runOnMainSync(() -> { try {
                    app.playback.player.pause(); app.playback.load(original.getString("id"), false); app.playback.player.seekTo(position[0]);
                    for (Map.Entry<String, Object> entry : savedFields.entrySet()) field(entry.getKey()).set(app.playback, entry.getValue());
                    field("stats").set(app.playback, new JSONObject(savedStats.toString())); field("lastStats").setLong(app.playback, SystemClock.elapsedRealtime()); field("lastSave").setLong(app.playback, SystemClock.elapsedRealtime());
                    app.playback.player.setVolume(volume[0]); app.save("ui", savedUi); app.save("tools", savedTools); app.playback.player.setPlayWhenReady(!original.getBoolean("paused"));
                } catch (Exception e) { throw new RuntimeException(e); } });
                evidence.put("uiRestored", savedUi.toString().equals(app.settings("ui").toString())).put("toolsRestored", savedTools.toString().equals(app.settings("tools").toString())).put("audioSettingsPreserved", savedAudio.toString().equals(app.settings("audio").toString()));
                try (FileOutputStream out = new FileOutputStream(new File(directory, "results.json"))) { out.write(evidence.toString(2).getBytes("UTF-8")); }
                assertTrue("UI restored", evidence.getBoolean("uiRestored")); assertTrue("Tools restored", evidence.getBoolean("toolsRestored"));
            }
        }
    }
}
