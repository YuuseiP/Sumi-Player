package cn.yuusei.m3x;
import org.junit.Test;
import org.json.*;
import java.util.List;
import static org.junit.Assert.*;

public class LyricsAutoMatchTest {
    static JSONObject track() throws Exception {
        return new JSONObject().put("title", "Shiny Smile").put("artist", "天海春香").put("originalArtist", "天海春香 (CV.中村繪里子)")
            .put("album", "THE IDOLM@STER").put("duration", 240.4);
    }
    static JSONObject row(String provider, String id, String title, String artist, long ms) throws Exception {
        return new JSONObject().put("provider", provider).put("songId", id).put("title", title).put("artist", artist).put("album", "").put("durationMs", ms);
    }
    @Test public void acceptsOnlyCandidatesWithinOneSecond() throws Exception {
        assertTrue(LyricsAutoMatch.score(track(), row("NE", "1", "Shiny Smile", "x", 241400)) >= 0);
        assertTrue(LyricsAutoMatch.score(track(), row("NE", "1", "Shiny Smile", "x", 239400)) >= 0);
        assertEquals(-1, LyricsAutoMatch.score(track(), row("NE", "1", "Shiny Smile", "x", 241401)));
        assertEquals(-1, LyricsAutoMatch.score(track(), row("NE", "1", "Shiny Smile", "x", 0)));
        assertEquals(-1, LyricsAutoMatch.score(track().put("duration", 0), row("NE", "1", "Shiny Smile", "x", 240400)));
    }
    @Test public void rejectsDifferentTitlesAndUnstatedVersions() throws Exception {
        assertEquals(-1, LyricsAutoMatch.score(track(), row("QQ", "1", "Other Song", "天海春香", 240400)));
        assertEquals(-1, LyricsAutoMatch.score(track(), row("QQ", "1", "Shiny Smile (Live)", "天海春香", 240400)));
        assertEquals(-1, LyricsAutoMatch.score(track(), row("QQ", "1", "Shiny Smile (Off Vocal)", "天海春香", 240400)));
        assertTrue(LyricsAutoMatch.score(track().put("title", "Shiny Smile (M@STER VERSION)"), row("QQ", "1", "Shiny Smile", "x", 240400)) >= 0);
        assertTrue(LyricsAutoMatch.score(track(), row("QQ", "1", "ＳＨＩＮＹ　ＳＭＩＬＥ", "x", 240400)) >= 0);
    }
    @Test public void ranksExactTitleArtistAndSmallerDeltaFirst() throws Exception {
        JSONArray qq = new JSONArray().put(row("QQ", "1", "Shiny Smile (シャイニースマイル)", "天海春香", 240400));
        JSONArray ne = new JSONArray().put(row("NE", "2", "Shiny Smile", "别人", 240900)).put(row("NE", "3", "Shiny Smile", "天海春香", 241300))
            .put(row("NE", "3", "Shiny Smile", "天海春香", 241300)).put(row("NE", "4", "Shiny Smile", "天海春香", 245000));
        List<JSONObject> ranked = LyricsAutoMatch.rank(track(), qq, ne);
        assertEquals(3, ranked.size());
        assertEquals("3", ranked.get(0).getString("songId"));
        assertEquals(900, ranked.get(0).getLong("durationDeltaMs"));
        assertEquals("2", ranked.get(1).getString("songId"));
        assertEquals("1", ranked.get(2).getString("songId"));
    }
    @Test public void detectsPlatformPlaceholders() throws Exception {
        assertTrue(LyricsAutoMatch.placeholder("[00:00.00]纯音乐，请欣赏"));
        assertTrue(LyricsAutoMatch.placeholder("[00:00.00]作词 : A\n[00:01.00]作曲 : B"));
        assertFalse(LyricsAutoMatch.placeholder("[00:01.00]一\n[00:02.00]二\n[00:03.00]三"));
    }
}
