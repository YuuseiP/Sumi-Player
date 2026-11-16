package cn.yuusei.m3x;

import android.content.Intent;
import android.os.SystemClock;
import android.test.InstrumentationTestCase;
import java.io.File;
import java.io.FileOutputStream;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.json.JSONArray;
import org.json.JSONObject;

/** Real catalog navigation only: no scan, playback commands, tag writes or synthetic library. */
public final class SongNavigationDeviceTest extends InstrumentationTestCase {
    private MainActivity activity;
    private File directory;
    private final JSONArray screenshots = new JSONArray();
    private String js(String code) throws Exception {
        CountDownLatch latch = new CountDownLatch(1); String[] result = {""};
        getInstrumentation().runOnMainSync(() -> activity.web.evaluateJavascript(code, value -> { result[0] = value; latch.countDown(); }));
        assertTrue("JS callback", latch.await(20, TimeUnit.SECONDS)); return result[0];
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
    private String value(String expression) throws Exception { return new JSONArray("[" + js(expression) + "]").getString(0); }
    private void setting(String key, String value) throws Exception {
        act("var e=document.querySelector('[data-setting=" + key + "]');e.value=" + JSONObject.quote(value) + ";e.dispatchEvent(new Event('change',{bubbles:true}))");
    }
    private void capture(String id) throws Exception {
        if (id.endsWith("-player")) waitJs("!document.querySelector('[data-panel=player]').hidden");
        waitJs("!document.fonts||document.fonts.status==='loaded'");
        act("window.__navigationFrame=false;requestAnimationFrame(function(){requestAnimationFrame(function(){window.__navigationFrame=true})})");
        waitJs("__navigationFrame");
        Thread.sleep(500);
        android.graphics.Bitmap bitmap = getInstrumentation().getUiAutomation().takeScreenshot(); assertNotNull(bitmap);
        try (FileOutputStream out = new FileOutputStream(new File(directory, id + ".png"))) {
            bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, out);
        } finally { bitmap.recycle(); }
        screenshots.put(new JSONObject().put("id", id).put("file", id + ".png").put("capturedAt", System.currentTimeMillis()));
    }
    private void search(String query) throws Exception {
        act("var e=document.querySelector('.m3x-search');e.value=" + JSONObject.quote(query) + ";e.dispatchEvent(new Event('input',{bubbles:true}))");
    }
    private void assertDestination(String filter, String key) throws Exception {
        assertEquals("Library opened", "false", js("document.querySelector('[data-panel=library]').hidden"));
        assertEquals("Correct category", "true", js("document.querySelector('[data-filter=" + filter + "]').getAttribute('aria-pressed')==='true'"));
        assertEquals("Correct destination", key, value("document.getElementById('group-label').textContent"));
        assertEquals("Stale search cleared", "\"\"", js("document.querySelector('.m3x-search').value"));
        assertEquals("Destination has songs", "true", js("!!document.querySelector('[data-list=library] [data-track]')"));
    }
    public void testSongAlbumAndArtistNavigation() throws Exception {
        assertEquals("Physical M3X", "Shanling M3X", android.os.Build.MODEL.replace('_', ' '));
        PlayerApp app = PlayerApp.instance; app.awaitLibrary();
        JSONObject savedUi = new JSONObject(app.settings("ui").toString()), savedTools = new JSONObject(app.settings("tools").toString());
        directory = new File(app.getExternalFilesDir(null), "song-navigation"); assertTrue(directory.isDirectory() || directory.mkdirs());
        JSONObject report = new JSONObject().put("device", android.os.Build.MODEL).put("screenshots", screenshots);
        try {
            activity = (MainActivity) getInstrumentation().startActivitySync(new Intent(getInstrumentation().getTargetContext(), MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            getInstrumentation().runOnMainSync(() -> activity.getWindow().addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON | android.view.WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON | android.view.WindowManager.LayoutParams.FLAG_DISMISS_KEYGUARD));
            waitJs("!!window.M3X_UI&&M3X_UI.library()!==null&&M3X_UI.tracks.length>0");
            String scan = app.library.scanStatus().toString();
            int count = app.library.all().size();
            String current = js("M3X_UI.currentTrack().id"), queue = js("nativeCommand('queue').sourceIds");
            for (String theme : new String[]{"light", "dark"}) {
                setting("theme", theme);
                click(".m3x-nav [data-page=library]"); click("[data-filter=all]"); search("");
                // Pick a visible row that is not the current song: menu targets must not use the player index.
                act("window.__navIndex=Array.from(document.querySelectorAll('[data-list=library] [data-track]')).map(function(e){return Number(e.dataset.track)}).filter(function(i){return M3X_UI.tracks[i].id!==M3X_UI.currentTrack().id})[0]");
                assertEquals("Another real song available", "true", js("Number.isInteger(__navIndex)"));
                JSONObject target = new JSONObject(js("M3X_UI.tracks[__navIndex]"));
                search(target.getString("title"));
                act("document.querySelector('[data-menu-track=\"'+__navIndex+'\"]').click()");
                capture(theme + "-song-menu"); click("[data-action=menu-open-album]");
                assertDestination("albums", target.getString("album"));
                assertEquals("Selected song belongs to album", "true", js("!!document.querySelector('[data-list=library] [data-track=\"'+__navIndex+'\"]')"));
                capture(theme + "-album"); click("[data-action=group-back]");
                assertEquals("Original search restored", target.getString("title"), value("document.querySelector('.m3x-search').value"));
                act("document.querySelector('[data-menu-track=\"'+__navIndex+'\"]').click()"); click("[data-action=menu-open-artist]");
                JSONArray credits = target.getJSONArray("credits");
                if (credits.length() > 1) {
                    assertEquals("Multiple performers require selection", "true", js("document.getElementById('song-artist-dialog').open"));
                    capture(theme + "-artist-choices"); click("[data-song-artist='0']");
                }
                JSONObject credit = credits.getJSONObject(0);
                String filter = credit.getString("kind").equals("artist") ? "artists" : credit.getString("kind").equals("character") ? "characters" : "groups";
                assertDestination(filter, credit.getString("name")); capture(theme + "-artist");
                assertEquals("Native back handled", "true", js("M3X_UI.back()"));
                assertEquals("Search restored on native back", target.getString("title"), value("document.querySelector('.m3x-search').value"));
                click(".m3x-nav [data-page=player]"); capture(theme + "-player");
                click("[data-action=open-song-album]");
                assertDestination("albums", new JSONObject(js("M3X_UI.currentTrack()")).getString("album"));
                assertEquals("Return from album", "true", js("M3X_UI.back()"));
                assertEquals("Returns to player", "false", js("document.querySelector('[data-panel=player]').hidden"));
                click("[data-action=open-song-artist]");
                if ("true".equals(js("document.getElementById('song-artist-dialog').open"))) click("[data-song-artist='0']");
                assertEquals("Artist page opened", "false", js("document.querySelector('[data-panel=library]').hidden"));
                assertEquals("Artist return", "true", js("M3X_UI.back()"));
                assertEquals("Returns to player again", "false", js("document.querySelector('[data-panel=player]').hidden"));
            }
            // Exercise every category of a real multi-credit song, without starting it.
            click(".m3x-nav [data-page=library]"); click("[data-filter=all]"); search("");
            act("window.__multi=M3X_UI.tracks.map(function(t,i){return {t:t,i:i}}).filter(function(x){return (x.t.credits||[]).length>1})");
            int multiCount = Integer.parseInt(js("__multi.length")); boolean checkedMulti = false;
            for (int i = 0; i < Math.min(multiCount, 30) && !checkedMulti; i++) {
                JSONObject candidate = new JSONObject(js("__multi[" + i + "].t")); search(candidate.getString("title"));
                int index = Integer.parseInt(js("__multi[" + i + "].i"));
                if (!"true".equals(js("!!document.querySelector('[data-menu-track=\"" + index + "\"]')"))) continue;
                JSONArray credits = candidate.getJSONArray("credits");
                for (int choice = 0; choice < credits.length(); choice++) {
                    click("[data-menu-track='" + index + "']"); click("[data-action=menu-open-artist]");
                    if (choice == 0) capture("multiple-performer-choices");
                    click("[data-song-artist='" + choice + "']");
                    JSONObject credit = credits.getJSONObject(choice);
                    assertEquals("Correct performer", credit.getString("name"), value("document.getElementById('group-label').textContent"));
                    assertEquals("Performer includes selected song", "true", js("!!document.querySelector('[data-list=library] [data-track=\"" + index + "\"]')"));
                    assertEquals("Return from performer", "true", js("M3X_UI.back()"));
                }
                checkedMulti = true; report.put("multiCreditChoices", credits.length());
            }
            assertTrue("Real multiple-performer song checked", checkedMulti);
            assertEquals("Current song unchanged", current, js("M3X_UI.currentTrack().id"));
            assertEquals("Playback queue unchanged", queue, js("nativeCommand('queue').sourceIds"));
            assertEquals("No scan", scan, app.library.scanStatus().toString()); assertEquals("Catalog unchanged", count, app.library.all().size());
            report.put("passed", true).put("trackCount", count);
        } finally {
            if (activity != null) {
                try { act("window.nativeCommand=function(){return {}}"); }
                finally { getInstrumentation().runOnMainSync(() -> { activity.web.loadUrl("about:blank"); activity.finish(); }); getInstrumentation().waitForIdleSync(); }
            }
            getInstrumentation().runOnMainSync(() -> { app.save("ui", savedUi); app.save("tools", savedTools); });
            try (FileOutputStream out = new FileOutputStream(new File(directory, "verification.json"))) { out.write(report.toString(2).getBytes("UTF-8")); }
            assertEquals("UI settings restored", savedUi.toString(), app.settings("ui").toString());
        }
    }
}
