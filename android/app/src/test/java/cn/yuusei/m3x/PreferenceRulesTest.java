package cn.yuusei.m3x;

import org.junit.Test;
import org.json.*;
import java.nio.file.*;
import static org.junit.Assert.*;

public class PreferenceRulesTest {
    private PreferenceRules rules() throws Exception {
        return new PreferenceRules(new JSONObject(new String(Files.readAllBytes(Paths.get("src/main/assets/player/preference-rules.json")), "UTF-8")));
    }
    private JSONObject track(String title, String filename) throws Exception {
        return new JSONObject().put("id", "stable-id").put("title", title).put("filename", filename).put("artist", "original");
    }
    private boolean has(String title, String category) throws Exception {
        return rules().annotate(track(title, title + ".flac")).getJSONObject("preference").getJSONArray("categories").toString().contains("\"" + category + "\"");
    }
    @Test public void explicitVersionMarkersAndUnicodeAreRecognized() throws Exception {
        for (String title : new String[]{"Song (Instrumental)", "Song（ＩＮＳＴＲＵＭＥＮＴＡＬ）", "Song [OFF VOCAL]", "Song - カラオケ", "Song (Inst.)"}) assertTrue(title, has(title, "backing"));
        for (String title : new String[]{"Song (SOLO ver.)", "Song ソロ・リミックス", "Song [Solo Remix]"}) assertTrue(title, has(title, "solo-explicit"));
        assertTrue(has("Song (Game Size)", "short"));
        assertTrue(has("Song (Piano Arrange Version)", "piano"));
    }
    @Test public void ordinaryTitlesAndGroupVersionsStayPlayable() throws Exception {
        for (String title : new String[]{"Instrumental Journey", "OFF VOCAL COLLECTION", "Solo Journey", "Song (M@STER VERSION)", "Song (2026 ver.)", "Song (Tokyo ver.)", "Song (Remix)", "Song (天海春香・如月千早 ver.)"})
            assertEquals(title, 0, rules().annotate(track(title, title + ".flac")).getJSONObject("preference").getJSONArray("categories").length());
        assertTrue(has("Song (天海春香 Ver.)", "solo-named"));
        assertTrue(has("Song（天海春香 Ｖｅｒ．）", "solo-named"));
    }
    @Test public void reportedGroupVersionHasAnIndependentOptInRule() throws Exception {
        String title = "Spread the Wings!! (イルミネーションスターズ Ver.)";
        JSONObject reported = rules().annotate(track(title, title + ".flac"));
        assertTrue(has(title, "group-named"));
        assertTrue(has("Song（イルミネーションスターズ Ｖｅｒ．）", "group-named"));
        assertTrue(has("Song (放課後クライマックスガールズ Ver.)", "group-named"));
        assertTrue(has("Song (765PRO ALLSTARS ver.)", "group-named"));
        JSONObject settings = new JSONObject().put("enabledCategories", new JSONArray().put("solo-explicit").put("solo-named"));
        assertFalse(new PreferenceRules.Settings(settings).excludes(reported));
        settings.getJSONArray("enabledCategories").put("group-named");
        assertTrue(new PreferenceRules.Settings(settings).excludes(reported));
        assertFalse(new PreferenceRules.Settings(null).excludes(reported));
        assertFalse(has("Song (M@STER VERSION)", "group-named"));
        assertFalse(has("Song (Unknown Group ver.)", "group-named"));
    }
    @Test public void fileConflictNeedsReviewAndCueContainerIsNotASongMarker() throws Exception {
        JSONObject value = rules().annotate(track("Song", "OFF VOCAL COLLECTION - Song (Instrumental).flac"));
        assertEquals(0, value.getJSONObject("preference").getJSONArray("categories").length());
        assertTrue(value.getJSONObject("preference").getBoolean("needsReview"));
        assertTrue(value.getJSONObject("preference").getJSONArray("reasons").length() > 0);
        value = rules().annotate(track("", "OFF VOCAL COLLECTION - Song (Instrumental).flac"));
        assertTrue(value.getJSONObject("preference").getJSONArray("categories").toString().contains("backing"));
        value = rules().annotate(track("Song", "Disc (Instrumental).wav").put("cueStart", 0));
        assertEquals(0, value.getJSONObject("preference").getJSONArray("reasons").length());
        assertFalse(value.getJSONObject("preference").getBoolean("needsReview"));
        assertEquals("stable-id", value.getString("id")); assertEquals("original", value.getString("artist"));
    }
    @Test public void disabledByDefaultAndKeepWinsOverEveryExclusion() throws Exception {
        JSONObject value = rules().annotate(track("Song (Instrumental)", "same.flac"));
        assertFalse(new PreferenceRules.Settings(null).excludes(value));
        JSONObject settings = new JSONObject().put("enabledCategories", new JSONArray().put("backing"));
        assertTrue(new PreferenceRules.Settings(settings).excludes(value));
        settings.put("excludeTrackIds", new JSONArray().put("stable-id")).put("keepTrackIds", new JSONArray().put("stable-id"));
        assertFalse(new PreferenceRules.Settings(settings).excludes(value));
        assertEquals(settings.getJSONArray("keepTrackIds").toString(), new PreferenceRules.Settings(settings).jsonValue().getJSONArray("keepTrackIds").toString());
    }
    @Test public void invalidBackupPreferencesAreRejected() throws Exception {
        for (String bad : new String[]{"{\"schema\":2}", "{\"enabledCategories\":[\"ver\"]}", "{\"hideInLibrary\":\"true\"}", "{\"keepTrackIds\":[1]}", "{\"excludeTrackIds\":true}"}) {
            try { PreferenceRules.validateUi(new JSONObject().put("preferences", new JSONObject(bad))); fail(bad); }
            catch (IllegalArgumentException expected) { /* reject before import mutations */ }
        }
        PreferenceRules.validateUi(new JSONObject());
    }
}
