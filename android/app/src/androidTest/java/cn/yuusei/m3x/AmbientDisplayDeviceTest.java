package cn.yuusei.m3x;

import android.content.Intent;
import android.os.SystemClock;
import android.test.InstrumentationTestCase;
import android.view.View;
import android.view.WindowManager;
import java.io.File;
import java.io.FileOutputStream;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.json.JSONArray;
import org.json.JSONObject;

/** Uses the installed real catalog and current playback, without scanning or seeking. */
public final class AmbientDisplayDeviceTest extends InstrumentationTestCase {
    private MainActivity activity;
    private File directory;
    private final JSONArray captures = new JSONArray();
    private String js(String code) throws Exception {
        CountDownLatch latch = new CountDownLatch(1); String[] value = {""};
        getInstrumentation().runOnMainSync(() -> activity.web.evaluateJavascript(code, result -> { value[0] = result; latch.countDown(); }));
        assertTrue(latch.await(20, TimeUnit.SECONDS)); return value[0];
    }
    private void act(String code) throws Exception {
        assertEquals("JS action", "\"ok\"", js("(function(){try{" + code + ";return 'ok'}catch(e){return String(e)}})()"));
    }
    private void waitJs(String code) throws Exception {
        long end = SystemClock.elapsedRealtime() + 30000;
        while (SystemClock.elapsedRealtime() < end) { if ("true".equals(js(code))) return; Thread.sleep(100); }
        fail("Timed out: " + code);
    }
    private void click(String selector) throws Exception { act("document.querySelector(" + JSONObject.quote(selector) + ").click()"); }
    private int flags() {
        int[] flags = {0}; getInstrumentation().runOnMainSync(() -> flags[0] = activity.getWindow().getAttributes().flags); return flags[0];
    }
    private void capture(String id) throws Exception {
        waitJs("!document.fonts||document.fonts.status==='loaded'");
        act("window.__ambientFrame=false;requestAnimationFrame(function(){requestAnimationFrame(function(){__ambientFrame=true})})");
        waitJs("__ambientFrame"); Thread.sleep(500);
        android.graphics.Bitmap bitmap = getInstrumentation().getUiAutomation().takeScreenshot(); assertNotNull(bitmap);
        JSONObject item = new JSONObject().put("id", id).put("file", id + ".png").put("width", bitmap.getWidth()).put("height", bitmap.getHeight());
        try (FileOutputStream out = new FileOutputStream(new File(directory, id + ".png"))) { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, out); }
        finally { bitmap.recycle(); }
        captures.put(item);
    }
    private void assertLayout() throws Exception {
        assertEquals("Portrait stacked; landscape cover left of lyrics", "true", js("(function(){var c=document.getElementById('ambient-cover').getBoundingClientRect(),l=document.getElementById('ambient-lyrics').getBoundingClientRect();return innerWidth>innerHeight?c.right<=l.left&&c.top>=l.top&&c.bottom<=l.bottom+1:c.bottom<=l.top})()"));
        assertEquals("Cover square", "true", js("document.getElementById('ambient-cover').clientWidth===document.getElementById('ambient-cover').clientHeight"));
        assertEquals("Maximize square cover against screen edges", "true", js("(function(){var c=document.getElementById('ambient-cover').getBoundingClientRect(),a=document.getElementById('ambient-content').getBoundingClientRect();return Math.abs(c.left)<1&&Math.abs(c.top)<1&&(innerWidth>innerHeight?Math.abs(c.height-a.height)<1:Math.abs(c.width-a.width)<1)})()"));
        assertEquals("Lyrics retain usable space", "true", js("innerWidth>innerHeight?document.getElementById('ambient-lyrics').clientWidth>=160:document.getElementById('ambient-lyrics').clientHeight>=80"));
        assertEquals("Visible controls fit on screen", "true", js("document.getElementById('ambient-controls').hidden||document.getElementById('ambient-controls').getBoundingClientRect().bottom<=innerHeight+1"));
        assertEquals("Same lyric text and active line", "true", js("Array.from(document.getElementById('lyrics-lines').children).every(function(n,i){var a=document.getElementById('ambient-lines').children[i];return a&&a.textContent===n.textContent&&(a.getAttribute('aria-current')==='true')===(n.getAttribute('aria-current')==='true')})"));
        assertEquals("Black canvas", "\"rgb(0, 0, 0)\"", js("getComputedStyle(document.getElementById('ambient-display')).backgroundColor"));
        assertTrue("Screen stays awake", (flags() & WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) != 0);
    }
    public void testAmbientPortraitLandscapeAndRestore() throws Exception {
        assertEquals("Physical M3X", "Shanling M3X", android.os.Build.MODEL.replace('_', ' '));
        PlayerApp app = PlayerApp.instance;
        JSONObject savedUi = new JSONObject(app.settings("ui").toString()), savedTools = new JSONObject(app.settings("tools").toString());
        directory = new File(app.getExternalFilesDir(null), "ambient-cover-max"); assertTrue(directory.isDirectory() || directory.mkdirs());
        JSONObject report = new JSONObject().put("device", android.os.Build.MODEL).put("screenshots", captures);
        try {
            activity = (MainActivity)getInstrumentation().startActivitySync(new Intent(getInstrumentation().getTargetContext(), MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            waitJs("!!window.M3X_AMBIENT&&M3X_UI.library()!==null&&M3X_UI.tracks.length>0");
            String scan = app.library.scanStatus().toString(), current = js("M3X_UI.currentTrack().id"), queue = js("nativeCommand('queue').sourceIds");
            click(".m3x-nav [data-page=player]");
            act("document.querySelector('[data-action=ambient-mode]').scrollIntoView(false)");
            capture("player-entry");
            int originalOrientation = activity.getRequestedOrientation();
            int[] originalSystemUi = {0}; getInstrumentation().runOnMainSync(() -> originalSystemUi[0] = activity.getWindow().getDecorView().getSystemUiVisibility());
            click("[data-action=ambient-mode]");
            waitJs("M3X_AMBIENT.active()");
            boolean automatic = new JSONObject(js("nativeCommand('ambient-display',{enabled:true})")).getBoolean("automaticRotation");
            report.put("automaticRotationAvailable", automatic);
            if (automatic) {
                assertEquals(android.content.pm.ActivityInfo.SCREEN_ORIENTATION_FULL_SENSOR, activity.getRequestedOrientation());
                // Physical capture orientation only; this is not a sensor event or auto-rotation proof.
                getInstrumentation().runOnMainSync(() -> activity.setRequestedOrientation(android.content.pm.ActivityInfo.SCREEN_ORIENTATION_PORTRAIT));
            } else if ("true".equals(js("innerWidth>innerHeight"))) click("#ambient-rotate");
            waitJs("innerHeight>innerWidth&&document.getElementById('ambient-cover').complete");
            waitJs("document.getElementById('lyrics-lines').textContent!=='正在读取歌词…'");
            assertEquals("Controls initially visible", "false", js("document.getElementById('ambient-controls').hidden"));
            assertLayout(); capture("portrait-controls");
            // Verify routing without changing the user's actual playback, queue or position.
            act("window.__ambientCommand=window.nativeCommand;window.__ambientCommands=[];window.nativeCommand=function(n,d){if(['play','pause','previous','next','seek'].indexOf(n)>=0){__ambientCommands.push(n);return __ambientCommand('poll')}return __ambientCommand(n,d)}");
            try {
                click("[data-ambient-action=previous]"); click("[data-ambient-action=play]"); click("[data-ambient-action=next]");
                act("var e=document.getElementById('ambient-seek');e.value=Math.min(20,Number(e.max));e.dispatchEvent(new Event('input'));e.dispatchEvent(new Event('change'))");
                assertEquals("Playback actions routed", "true", js("['previous','next','seek'].every(function(n){return __ambientCommands.indexOf(n)>=0})&&(__ambientCommands.indexOf('play')>=0||__ambientCommands.indexOf('pause')>=0)"));
                assertEquals("Controls stay visible after playback action", "false", js("document.getElementById('ambient-controls').hidden"));
            } finally { act("window.nativeCommand=window.__ambientCommand"); }
            click("#ambient-display");
            assertEquals("Blank tap hides controls", "true", js("document.getElementById('ambient-controls').hidden"));
            assertLayout(); capture("portrait");
            click("#ambient-display");
            assertEquals("Second blank tap reveals controls", "false", js("document.getElementById('ambient-controls').hidden"));
            if (automatic) getInstrumentation().runOnMainSync(() -> activity.setRequestedOrientation(android.content.pm.ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE));
            else click("#ambient-rotate");
            waitJs("innerWidth>innerHeight"); assertLayout(); capture("landscape-controls");
            assertEquals("Controls below lyrics", "true", js("document.getElementById('ambient-lyrics').getBoundingClientRect().bottom<=document.getElementById('ambient-controls').getBoundingClientRect().top+1"));
            click("#ambient-display");
            assertLayout(); capture("landscape");
            getInstrumentation().runOnMainSync(() -> activity.onBackPressed());
            waitJs("!M3X_AMBIENT.active()&&!document.querySelector('[data-panel=player]').hidden");
            assertEquals("Wake flag restored", 0, flags() & WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
            assertEquals("Orientation restored", originalOrientation, activity.getRequestedOrientation());
            int[] restoredUi = {0}; getInstrumentation().runOnMainSync(() -> restoredUi[0] = activity.getWindow().getDecorView().getSystemUiVisibility());
            assertEquals("System bars restored", originalSystemUi[0], restoredUi[0]);
            // Explicit exit button and the lyrics-menu entry.
            click("[data-action=ambient-mode]"); waitJs("M3X_AMBIENT.active()"); click("#ambient-exit");
            waitJs("!M3X_AMBIENT.active()");
            assertEquals(0, flags() & WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
            click(".m3x-cover-open"); click("#lyrics-more");
            act("Array.from(document.querySelectorAll('dialog[open] button')).filter(function(b){return b.textContent.indexOf('伪息屏')===0})[0].click()");
            waitJs("M3X_AMBIENT.active()");
            getInstrumentation().runOnMainSync(() -> activity.moveTaskToBack(true));
            Thread.sleep(900);
            assertEquals("Background releases wake flag", 0, flags() & WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
            assertEquals("Background exits mode", "false", js("M3X_AMBIENT.active()"));
            getInstrumentation().runOnMainSync(() -> activity.startActivity(new Intent(activity, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)));
            waitJs("!M3X_AMBIENT.active()");
            assertEquals("Song unchanged", current, js("M3X_UI.currentTrack().id"));
            assertEquals("Queue unchanged", queue, js("nativeCommand('queue').sourceIds"));
            assertEquals("No scan", scan, app.library.scanStatus().toString());
            report.put("passed", true).put("lyricLines", Integer.parseInt(js("document.getElementById('ambient-lines').children.length")));
        } finally {
            if (activity != null) {
                try { act("if(window.__ambientCommand)window.nativeCommand=window.__ambientCommand;M3X_AMBIENT.exit();window.nativeCommand=function(){return {}}"); }
                finally { getInstrumentation().runOnMainSync(() -> { activity.web.loadUrl("about:blank"); activity.finish(); }); getInstrumentation().waitForIdleSync(); }
            }
            getInstrumentation().runOnMainSync(() -> { app.save("ui", savedUi); app.save("tools", savedTools); });
            try (FileOutputStream out = new FileOutputStream(new File(directory, "verification.json"))) { out.write(report.toString(2).getBytes("UTF-8")); }
        }
    }
}
