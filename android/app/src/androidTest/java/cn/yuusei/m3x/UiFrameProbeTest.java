package cn.yuusei.m3x;

import android.content.Intent;
import android.os.ParcelFileDescriptor;
import android.os.SystemClock;
import android.test.InstrumentationTestCase;
import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStreamReader;
import java.lang.reflect.Field;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.json.JSONArray;
import org.json.JSONObject;

/**
 * Frame-pacing probe for the redesigned motion system on the physical M3X.
 * Each scenario resets hwui frame statistics (WebView draws through the app's
 * RenderThread), performs one UI action and records both rendered-frame jank
 * (dumpsys gfxinfo) and main-thread rAF intervals. Existing catalog, no scan;
 * playback is muted and the original song, position and settings are restored.
 */
@androidx.media3.common.util.UnstableApi
public final class UiFrameProbeTest extends InstrumentationTestCase {
    private MainActivity activity;
    private final JSONArray samples = new JSONArray();
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
    private String shell(String command) throws Exception {
        ParcelFileDescriptor pfd = getInstrumentation().getUiAutomation().executeShellCommand(command);
        StringBuilder out = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(new FileInputStream(pfd.getFileDescriptor()), "UTF-8"))) {
            String line; while ((line = reader.readLine()) != null) out.append(line).append('\n');
        } finally { pfd.close(); }
        return out.toString();
    }
    private static double number(String text, String pattern) {
        Matcher m = Pattern.compile(pattern).matcher(text); return m.find() ? Double.parseDouble(m.group(1)) : -1;
    }
    /** Run one action, sample rAF for windowMs and read hwui frame statistics. */
    private JSONObject measure(String name, String action, int windowMs) throws Exception {
        String pkg = getInstrumentation().getTargetContext().getPackageName();
        Thread.sleep(250);
        shell("dumpsys gfxinfo " + pkg + " reset");
        act("window.__probe=null;requestAnimationFrame(function(){var last=performance.now(),start=last,frames=[],handler=0,first=null;"
            + "function tick(){var now=performance.now();frames.push(+(now-last).toFixed(2));last=now;if(first===null)first=now-start;"
            + "if(now-start<" + windowMs + ")requestAnimationFrame(tick);else window.__probe={handlerMs:handler,firstFrameMs:first,intervals:frames};}"
            + "requestAnimationFrame(tick);" + action + ";handler=performance.now()-start;})");
        waitJs("window.__probe!==null");
        String gfx = shell("dumpsys gfxinfo " + pkg);
        JSONObject sample = new JSONObject(js("__probe")).put("name", name);
        JSONArray intervals = sample.getJSONArray("intervals");
        int over20 = 0, over34 = 0; double worst = 0;
        for (int i = 1; i < intervals.length(); i++) { double v = intervals.getDouble(i); if (v > 20) over20++; if (v > 34) over34++; worst = Math.max(worst, v); }
        sample.put("rafOver20", over20).put("rafOver34", over34).put("rafWorst", worst).remove("intervals");
        sample.put("rafCount", intervals.length());
        sample.put("frames", number(gfx, "Total frames rendered: (\\d+)"))
            .put("janky", number(gfx, "Janky frames: (\\d+)"))
            .put("p50", number(gfx, "50th percentile: (\\d+)ms"))
            .put("p90", number(gfx, "90th percentile: (\\d+)ms"))
            .put("p95", number(gfx, "95th percentile: (\\d+)ms"))
            .put("p99", number(gfx, "99th percentile: (\\d+)ms"))
            .put("slowUi", number(gfx, "Number Slow UI thread: (\\d+)"))
            .put("slowDraw", number(gfx, "Number Slow issue draw commands: (\\d+)"));
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
        waitJs("!!window.M3X_UI&&!!nativeCommand('poll').stats&&M3X_UI.tracks.length>0");
        JSONObject savedUi = new JSONObject(app.settings("ui").toString()), savedTools = new JSONObject(app.settings("tools").toString()), savedAudio = new JSONObject(app.settings("audio").toString());
        JSONObject scan = new JSONObject(app.library.scanStatus().toString()); int tracks = app.library.all().size();
        JSONObject original = new JSONObject(js("nativeCommand('poll')"));
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
            assertEquals("Catalog unchanged", tracks, app.library.all().size());
            assertEquals("No scan", scan.toString(), app.library.scanStatus().toString());
            evidence.put("passed", true);
        } finally {
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
