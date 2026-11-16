package cn.yuusei.m3x;
import org.junit.Test;
import org.json.*;
import java.util.Map;
import static org.junit.Assert.*;

public class NeteaseLogicTest {
    @Test public void weapiEnvelopeHasFixedShapes() throws Exception {
        String[] envelope = NeteaseCrypto.weapi("{\"s\":\"歌\"}", "abcdefghijklmnop");
        assertEquals(256, envelope[1].length());
        assertTrue(envelope[1].matches("[0-9a-f]{256}"));
        assertEquals(envelope[0], NeteaseCrypto.weapi("{\"s\":\"歌\"}", "abcdefghijklmnop")[0]);
        assertEquals(16, NeteaseCrypto.secret().length());
    }
    @Test public void eapiIsUppercaseHexOfWholeBlocks() throws Exception {
        String value = NeteaseCrypto.eapi("/api/song/enhance/player/url/v1", "{\"ids\":\"[1]\"}");
        assertTrue(value.matches("[0-9A-F]+"));
        assertEquals(0, value.length() % 32);
        assertEquals("d41d8cd98f00b204e9800998ecf8427e", NeteaseCrypto.md5(""));
    }
    @Test public void cookieInputAcceptsBrowserCopyOrBareValue() throws Exception {
        Map<String, String> parsed = NeteaseClient.parseCookie(" MUSIC_U=0123456789abcdef0123; __csrf=abc ; bad name=x");
        assertEquals("0123456789abcdef0123", parsed.get("MUSIC_U"));
        assertEquals("abc", parsed.get("__csrf"));
        assertFalse(parsed.containsKey("bad name"));
        assertEquals("0123456789abcdef0123", NeteaseClient.parseCookie("0123456789abcdef0123").get("MUSIC_U"));
        for (String bad : new String[]{"", "__csrf=abc", "MUSIC_U=short", "MUSIC_U=has space inside value"}) {
            try { NeteaseClient.parseCookie(bad); fail(bad); } catch (java.io.IOException expected) {}
        }
    }
    @Test public void lyricSearchReadsCloudsearchRows() throws Exception {
        JSONObject data = new JSONObject("{\"code\":200,\"result\":{\"songs\":[{\"id\":7,\"name\":\"歌\",\"ar\":[{\"name\":\"A\"}],\"al\":{\"name\":\"盤\"},\"dt\":123456}]}}");
        JSONObject row = LyricsProviders.searchResults("NE", data).getJSONObject(0);
        assertEquals("7", row.getString("songId")); assertEquals("A", row.getString("artist"));
        assertEquals("盤", row.getString("album")); assertEquals(123456, row.getLong("durationMs"));
        assertEquals(0, LyricsProviders.searchResults("NE", new JSONObject("{\"code\":200,\"result\":\"35b1748964af\"}")).length());
    }
    @Test public void normalisesSongShapesAndAvailability() throws Exception {
        JSONObject modern = new JSONObject("{\"id\":1,\"name\":\"歌\",\"ar\":[{\"name\":\"A\"},{\"name\":\"B\"}],\"al\":{\"name\":\"盤\",\"picUrl\":\"https://p1.music.126.net/x.jpg\"},\"dt\":200500,\"fee\":1,\"privilege\":{\"st\":0}}");
        JSONObject song = NeteaseClient.song(modern, null);
        assertEquals("1", song.getString("id")); assertEquals("A / B", song.getString("artist"));
        assertEquals(200500, song.getLong("durationMs")); assertTrue(song.getBoolean("vip")); assertTrue(song.getBoolean("available"));
        JSONObject legacy = new JSONObject("{\"id\":2,\"name\":\"旧\",\"artists\":[{\"name\":\"C\"}],\"album\":{\"name\":\"D\"},\"duration\":1000}");
        assertEquals("C", NeteaseClient.song(legacy, null).getString("artist"));
        assertFalse(NeteaseClient.song(legacy, new JSONObject("{\"st\":-200}")).getBoolean("available"));
        assertFalse(NeteaseClient.song(new JSONObject("{\"id\":3,\"noCopyrightRcmd\":{\"type\":1}}"), null).getBoolean("available"));
        assertTrue(NeteaseClient.song(new JSONObject("{\"id\":4,\"noCopyrightRcmd\":null}"), null).getBoolean("available"));
    }
}
