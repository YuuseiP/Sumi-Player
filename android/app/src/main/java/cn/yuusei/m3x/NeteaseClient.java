package cn.yuusei.m3x;

import android.graphics.Bitmap;
import android.net.Uri;
import com.google.zxing.BarcodeFormat;
import com.google.zxing.EncodeHintType;
import com.google.zxing.common.BitMatrix;
import com.google.zxing.qrcode.QRCodeWriter;
import okhttp3.*;
import org.json.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import static cn.yuusei.m3x.PlayerApp.json;

/**
 * The user's own NetEase Cloud Music account: QR or cookie sign-in, VIP state, search, playlists and
 * on-demand playback addresses. Cookies stay encrypted with the Android keystore; no password is handled.
 */
@androidx.annotation.OptIn(markerClass = androidx.media3.common.util.UnstableApi.class)
final class NeteaseClient {
    static final String SCHEME = "m3xnetease";
    private static final String WEB = "https://music.163.com", EAPI = "https://interface3.music.163.com";
    private static final String UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36";
    private static final String PC_UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Safari/537.36 Chrome/91.0.4472.164 NeteaseMusicDesktop/3.0.18.203152";
    private static final List<String> LEVELS = Arrays.asList("standard", "higher", "exhigh", "lossless", "hires");
    private static NeteaseClient instance;

    /** A playback refusal whose message is shown to the user as-is. */
    static final class Denied extends IOException { Denied(String message) { super(message); } }

    private final OkHttpClient http = new OkHttpClient.Builder().connectTimeout(10, TimeUnit.SECONDS).readTimeout(15, TimeUnit.SECONDS)
        .callTimeout(25, TimeUnit.SECONDS).followRedirects(false).build();
    // Reads (catalog, account) run three at a time so a playlist never waits behind the home page;
    // state changes (import, login, likes) keep a single ordered lane.
    private final ExecutorService reads = new ThreadPoolExecutor(3, 3, 30, TimeUnit.SECONDS, new ArrayBlockingQueue<>(48));
    private final ExecutorService writes = new ThreadPoolExecutor(1, 1, 30, TimeUnit.SECONDS, new ArrayBlockingQueue<>(24));
    private static final Set<String> WRITES = new HashSet<>(Arrays.asList("import", "remove-imported", "cookie-login", "logout",
        "level", "like", "playlist-add", "fm-trash", "qr-create", "qr-check"));
    private final Object uidLock = new Object();
    private final Map<String, String[]> urls = new HashMap<>();
    private Map<String, String> cookies;
    private volatile String uid = "";

    static synchronized NeteaseClient get() { if (instance == null) instance = new NeteaseClient(); return instance; }

    // ---- cookies -------------------------------------------------------------------------------------------
    private synchronized Map<String, String> cookies() {
        if (cookies != null) return cookies;
        cookies = new LinkedHashMap<>();
        try {
            String stored = PlayerApp.instance.settings("netease").optString("cookie");
            if (!stored.isEmpty()) {
                JSONObject values = new JSONObject(NativeNetwork.decrypt(stored));
                for (Iterator<String> keys = values.keys(); keys.hasNext();) { String key = keys.next(); cookies.put(key, values.getString(key)); }
            }
        } catch (Exception error) { android.util.Log.w("M3X.Netease", "cookie unreadable", error); cookies.clear(); }
        if (!cookies.containsKey("_ntes_nuid")) cookies.put("_ntes_nuid", UUID.randomUUID().toString().replace("-", ""));
        if (!cookies.containsKey("NMTID")) cookies.put("NMTID", UUID.randomUUID().toString().replace("-", ""));
        return cookies;
    }
    private synchronized void saveCookies() throws Exception {
        JSONObject settings = PlayerApp.instance.settings("netease");
        settings.put("cookie", NativeNetwork.encrypt(new JSONObject(cookies()).toString()));
        PlayerApp.instance.save("netease", settings);
    }
    synchronized boolean loggedIn() { return !cookies().getOrDefault("MUSIC_U", "").isEmpty(); }
    private synchronized String csrf() { return cookies().getOrDefault("__csrf", ""); }
    private synchronized String cookieHeader(Map<String, String> extra) {
        Map<String, String> all = new LinkedHashMap<>(cookies()); all.put("__remember_me", "true"); all.putAll(extra);
        StringBuilder out = new StringBuilder();
        for (Map.Entry<String, String> entry : all.entrySet()) {
            if (out.length() > 0) out.append("; ");
            out.append(entry.getKey()).append('=').append(entry.getValue().replaceAll("[;\\r\\n]", ""));
        }
        return out.toString();
    }
    private synchronized boolean absorb(Response response) {
        boolean changed = false;
        for (String header : response.headers("Set-Cookie")) {
            String pair = header.split(";", 2)[0]; int at = pair.indexOf('=');
            if (at <= 0) continue;
            String name = pair.substring(0, at).trim(), value = pair.substring(at + 1).trim();
            if (!name.matches("[A-Za-z0-9_\\-]{1,64}") || value.length() > 4096) continue;
            if (value.isEmpty() || value.equalsIgnoreCase("deleted")) changed |= cookies().remove(name) != null;
            else if (!value.equals(cookies().get(name))) { cookies().put(name, value); changed = true; }
        }
        return changed;
    }
    /** Accepts "MUSIC_U=…; __csrf=…" copied from a browser, or the bare MUSIC_U value. */
    static Map<String, String> parseCookie(String input) throws IOException {
        Map<String, String> result = new LinkedHashMap<>();
        String text = input == null ? "" : input.trim();
        if (text.length() > 16384) throw new IOException("Cookie 过长");
        if (!text.contains("=")) { if (!text.isEmpty()) result.put("MUSIC_U", text); }
        else for (String part : text.split(";")) {
            int at = part.indexOf('='); if (at <= 0) continue;
            String name = part.substring(0, at).trim(), value = part.substring(at + 1).trim();
            if (name.matches("[A-Za-z0-9_\\-]{1,64}") && !value.isEmpty()) result.put(name, value);
        }
        if (result.getOrDefault("MUSIC_U", "").isEmpty()) throw new IOException("未找到 MUSIC_U，请粘贴登录后的 Cookie");
        if (!result.get("MUSIC_U").matches("[A-Za-z0-9+/=_\\-%.]{16,4096}")) throw new IOException("MUSIC_U 格式无效");
        return result;
    }

    // ---- transport -----------------------------------------------------------------------------------------
    private JSONObject execute(Request request) throws Exception {
        try (Response response = http.newCall(request).execute()) {
            if (absorb(response)) saveCookies();
            if (response.code() == 429) throw new IOException("网易云请求过多，请稍后再试");
            if (!response.isSuccessful()) throw new IOException("网易云返回 HTTP " + response.code());
            if (response.body() == null) throw new IOException("网易云返回空内容");
            String text = new String(MusicLibrary.read(response.body().byteStream(), 8 * 1024 * 1024), StandardCharsets.UTF_8);
            JSONObject data;
            try { data = new JSONObject(text); } catch (JSONException error) { throw new IOException("网易云返回了无法识别的内容"); }
            int code = data.optInt("code", 200);
            if (code == 301 || code == 302) throw new IOException("网易云登录已失效，请重新登录");
            if (code == -460 || code == 8821) throw new IOException("网易云触发了风控验证，请稍后重试或改用网页登录");
            return data;
        }
    }
    JSONObject weapi(String path, JSONObject data) throws Exception {
        data.put("csrf_token", csrf());
        String[] envelope = NeteaseCrypto.weapi(data.toString());
        RequestBody body = new FormBody.Builder().add("params", envelope[0]).add("encSecKey", envelope[1]).build();
        HttpUrl url = Objects.requireNonNull(HttpUrl.parse(WEB + "/weapi" + path)).newBuilder().addQueryParameter("csrf_token", csrf()).build();
        return execute(new Request.Builder().url(url).post(body).header("User-Agent", UA).header("Referer", WEB + "/")
            .header("Origin", WEB).header("Cookie", cookieHeader(Collections.emptyMap())).build());
    }
    JSONObject eapi(String path, JSONObject data) throws Exception {
        Map<String, String> header = new LinkedHashMap<>();
        header.put("os", "pc"); header.put("appver", "3.0.18.203152"); header.put("osver", "Microsoft-Windows-10-Professional-build-19045-64bit");
        header.put("deviceId", cookies().get("_ntes_nuid")); header.put("versioncode", "140"); header.put("buildver", String.valueOf(System.currentTimeMillis() / 1000));
        header.put("resolution", "1920x1080"); header.put("channel", "netease"); header.put("__csrf", csrf());
        header.put("requestId", System.currentTimeMillis() + "_" + String.format(Locale.ROOT, "%04d", new Random().nextInt(1000)));
        String music = cookies().getOrDefault("MUSIC_U", ""); if (!music.isEmpty()) header.put("MUSIC_U", music);
        data.put("header", new JSONObject(header));
        RequestBody body = new FormBody.Builder().add("params", NeteaseCrypto.eapi(path, data.toString())).build();
        return execute(new Request.Builder().url(EAPI + "/eapi" + path.substring("/api".length())).post(body).header("User-Agent", PC_UA)
            .header("Referer", WEB + "/").header("Cookie", cookieHeader(header)).build());
    }

    // ---- account -------------------------------------------------------------------------------------------
    JSONObject status() throws Exception {
        JSONObject settings = PlayerApp.instance.settings("netease");
        String level = LEVELS.contains(settings.optString("level")) ? settings.optString("level") : "lossless";
        JSONObject result = json("loggedIn", false, "level", level);
        if (!loggedIn()) return result;
        JSONObject account = weapi("/w/nuser/account/get", new JSONObject());
        JSONObject profile = account.optJSONObject("profile");
        if (profile == null) { result.put("expired", true); return result; }
        uid = String.valueOf(profile.optLong("userId"));
        result.put("loggedIn", true).put("userId", uid).put("nickname", profile.optString("nickname"))
            .put("avatar", coverPath(profile.optString("avatarUrl"))).put("signature", text(profile, "signature"));
        try {
            JSONObject detail = weapi("/v1/user/detail/" + uid, new JSONObject());
            result.put("userLevel", detail.optInt("level")).put("listenSongs", detail.optInt("listenSongs"));
            JSONObject more = detail.optJSONObject("profile");
            if (more != null) result.put("follows", more.optInt("follows")).put("followeds", more.optInt("followeds"));
        } catch (Exception error) { android.util.Log.w("M3X.Netease", "user detail", error); }
        JSONObject info = account.optJSONObject("account");
        int vipType = info == null ? profile.optInt("vipType") : info.optInt("vipType", profile.optInt("vipType"));
        JSONObject vip = json("type", vipType, "label", vipType == 11 ? "黑胶 VIP" : vipType == 10 ? "音乐包" : vipType > 0 ? "会员" : "非会员", "active", vipType > 0);
        try {
            JSONObject data = weapi("/music-vip-membership/front/vip/info", new JSONObject()).optJSONObject("data");
            if (data != null) {
                long now = System.currentTimeMillis(), expire = 0; String label = "";
                for (String[] kind : new String[][] {{"redplus", "黑胶 SVIP"}, {"associator", "黑胶 VIP"}, {"musicPackage", "音乐包"}}) {
                    JSONObject item = data.optJSONObject(kind[0]);
                    if (item != null && item.optLong("expireTime") > now && label.isEmpty()) { label = kind[1]; expire = item.optLong("expireTime"); }
                }
                if (!label.isEmpty()) vip.put("label", label).put("active", true).put("expireTime", expire);
                else if (data.has("associator") || data.has("musicPackage")) vip.put("active", false).put("label", "非会员");
                if (data.optInt("redVipLevel") > 0) vip.put("level", data.optInt("redVipLevel"));
            }
        } catch (Exception error) { android.util.Log.w("M3X.Netease", "vip info", error); }
        return result.put("vip", vip);
    }
    private JSONObject qrCreate() throws Exception {
        JSONObject data = weapi("/login/qrcode/unikey", json("type", 1));
        String key = data.optString("unikey");
        if (data.optInt("code") != 200 || key.isEmpty()) throw new IOException("无法创建登录二维码");
        return json("key", key, "qr", qr("https://music.163.com/login?codekey=" + key));
    }
    private JSONObject qrCheck(String key) throws Exception {
        if (!key.matches("[A-Za-z0-9\\-]{8,80}")) throw new IOException("二维码已失效");
        JSONObject data = weapi("/login/qrcode/client/login", json("key", key, "type", 1));
        int code = data.optInt("code");
        JSONObject result = json("code", code, "message", code == 800 ? "二维码已过期，请刷新" : code == 801 ? "请使用网易云音乐 App 扫码"
            : code == 802 ? "已扫码，请在手机上确认登录" : code == 803 ? "登录成功" : data.optString("message", "登录状态未知"));
        if (code == 802) result.put("nickname", data.optString("nickname"));
        if (code == 803) {
            if (!loggedIn()) throw new IOException("登录成功但未收到账号凭据，请改用网页登录");
            urls.clear(); result.put("status", status());
        }
        return result;
    }
    private JSONObject cookieLogin(String input) throws Exception {
        Map<String, String> parsed = parseCookie(input);
        synchronized (this) { cookies().putAll(parsed); }
        JSONObject state = status();
        if (!state.optBoolean("loggedIn")) { logout(); throw new IOException("登录未完成或凭据已过期，请重新登录"); }
        saveCookies(); urls.clear(); return state;
    }
    private synchronized void logout() throws Exception {
        String nuid = cookies().get("_ntes_nuid"), nmtid = cookies().get("NMTID");
        cookies().clear(); cookies.put("_ntes_nuid", nuid); cookies.put("NMTID", nmtid); urls.clear(); uid = ""; likedPid = "";
        JSONObject settings = PlayerApp.instance.settings("netease"); settings.remove("cookie"); PlayerApp.instance.save("netease", settings);
    }

    // ---- catalog -------------------------------------------------------------------------------------------
    static String coverPath(String url) {
        if (url == null || url.isEmpty()) return "cover-placeholder.svg";
        return "netease-cover/?u=" + Uri.encode(url.replaceFirst("^http://", "https://") + (url.contains("?") ? "" : "?param=300y300"));
    }
    static String artists(JSONArray list) {
        List<String> names = new ArrayList<>();
        if (list != null) for (int i = 0; i < list.length(); i++) { JSONObject item = list.optJSONObject(i); if (item != null && !item.optString("name").isEmpty()) names.add(item.optString("name")); }
        return String.join(" / ", names);
    }
    /** Normalises old and new NetEase song shapes; privilege may come inline or from a separate list. */
    static JSONObject song(JSONObject row, JSONObject privilege) throws JSONException {
        JSONObject album = row.has("al") ? row.optJSONObject("al") : row.optJSONObject("album");
        JSONArray artists = row.has("ar") ? row.optJSONArray("ar") : row.optJSONArray("artists");
        if (privilege == null) privilege = row.optJSONObject("privilege");
        int fee = row.optInt("fee", privilege == null ? 0 : privilege.optInt("fee"));
        boolean licensed = privilege == null || privilege.optInt("st", 0) >= 0;
        boolean available = licensed && (!row.has("noCopyrightRcmd") || row.isNull("noCopyrightRcmd"));
        return json("id", String.valueOf(row.getLong("id")), "title", row.optString("name"), "artist", artists(artists),
            "album", album == null ? "" : album.optString("name"), "cover", album == null ? "" : album.optString("picUrl"),
            "durationMs", row.has("dt") ? row.optLong("dt") : row.optLong("duration"), "fee", fee, "vip", fee == 1, "available", available,
            "artistId", artists != null && artists.optJSONObject(0) != null ? String.valueOf(artists.optJSONObject(0).optLong("id")) : "",
            "albumId", album == null ? "" : String.valueOf(album.optLong("id")));
    }
    private static JSONArray songs(JSONArray rows, JSONArray privileges) throws JSONException {
        Map<Long, JSONObject> byId = new HashMap<>();
        if (privileges != null) for (int i = 0; i < privileges.length(); i++) { JSONObject p = privileges.optJSONObject(i); if (p != null) byId.put(p.optLong("id"), p); }
        JSONArray result = new JSONArray();
        if (rows != null) for (int i = 0; i < rows.length(); i++) {
            JSONObject row = rows.optJSONObject(i);
            if (row != null && row.has("id")) result.put(song(row, byId.get(row.optLong("id"))));
        }
        return result;
    }
    private String requireUid() throws Exception {
        if (!loggedIn()) throw new IOException("请先登录网易云音乐");
        synchronized (uidLock) { if (uid.isEmpty()) status(); } // concurrent callers share one account lookup
        if (uid.isEmpty()) throw new IOException("网易云登录已失效，请重新登录");
        return uid;
    }
    /** optString turns JSON null into the text "null". */
    private static String text(JSONObject row, String key) { return row == null || row.isNull(key) ? "" : row.optString(key); }
    private static String id(String value, String label) throws IOException {
        if (value == null || !value.matches("[0-9]{1,20}")) throw new IOException(label + "编号无效");
        return value;
    }
    private static JSONObject list(JSONObject row, String coverKey) throws JSONException {
        JSONObject creator = row.optJSONObject("creator");
        return json("id", String.valueOf(row.optLong("id")), "name", row.optString("name"), "count", row.optInt("trackCount"),
            "cover", coverPath(row.optString(coverKey)), "creator", creator == null ? "" : creator.optString("nickname"),
            "plays", row.optLong("playCount", row.optLong("playcount")), "note", text(row, "copywriter").isEmpty() ? text(row, "updateFrequency") : text(row, "copywriter"));
    }
    private JSONObject search(String query, int offset, int type) throws Exception {
        query = query.trim(); if (query.isEmpty() || query.length() > 200) throw new IOException("请输入 1～200 字的搜索词");
        if (type != 1 && type != 1000) type = 1;
        JSONObject request = json("s", query, "type", type, "limit", type == 1 ? 30 : 12, "offset", Math.max(0, Math.min(offset, 1000)), "total", true);
        JSONObject data;
        // Anonymous weapi cloudsearch is answered with 50000005; the desktop eapi endpoint is not.
        try { data = eapi("/api/cloudsearch/pc", new JSONObject(request.toString())); if (data.optInt("code") != 200) throw new IOException("eapi " + data.optInt("code")); }
        catch (IOException error) { data = weapi("/cloudsearch/get/web", request); }
        JSONObject result = data.optJSONObject("result");
        if (data.optInt("code") != 200) throw new IOException("网易云搜索失败（" + data.optInt("code") + "）");
        if (type == 1000) {
            JSONArray rows = result == null ? null : result.optJSONArray("playlists"), lists = new JSONArray();
            if (rows != null) for (int i = 0; i < rows.length(); i++) lists.put(list(rows.getJSONObject(i), "coverImgUrl"));
            return json("playlists", lists);
        }
        return json("songs", songs(result == null ? null : result.optJSONArray("songs"), null), "total", result == null ? 0 : result.optInt("songCount"), "offset", offset);
    }
    private JSONObject home() throws Exception {
        JSONObject result = new JSONObject();
        JSONArray recommended = new JSONArray();
        try {
            if (loggedIn()) {
                JSONArray rows = weapi("/v1/discovery/recommend/resource", new JSONObject()).optJSONArray("recommend");
                if (rows != null) for (int i = 0; i < rows.length() && recommended.length() < 9; i++) recommended.put(list(rows.getJSONObject(i), "picUrl"));
            }
        } catch (Exception error) { android.util.Log.w("M3X.Netease", "recommend resource", error); }
        if (recommended.length() == 0) {
            JSONArray rows = weapi("/personalized/playlist", json("limit", 9, "total", true, "n", 1000)).optJSONArray("result");
            if (rows != null) for (int i = 0; i < rows.length(); i++) recommended.put(list(rows.getJSONObject(i), "picUrl"));
        }
        result.put("playlists", recommended);
        JSONArray tops = new JSONArray();
        try {
            JSONArray rows = weapi("/toplist", new JSONObject()).optJSONArray("list");
            if (rows != null) for (int i = 0; i < rows.length() && tops.length() < 8; i++) tops.put(list(rows.getJSONObject(i), "coverImgUrl"));
        } catch (Exception error) { android.util.Log.w("M3X.Netease", "toplist", error); }
        result.put("toplists", tops);
        JSONArray hot = new JSONArray();
        try {
            JSONObject data = weapi("/search/hot", json("type", 1111)).optJSONObject("result");
            JSONArray rows = data == null ? null : data.optJSONArray("hots");
            if (rows != null) for (int i = 0; i < rows.length() && hot.length() < 10; i++) hot.put(rows.getJSONObject(i).optString("first"));
        } catch (Exception error) { android.util.Log.w("M3X.Netease", "hot search", error); }
        return result.put("hot", hot);
    }
    private JSONObject userPlaylists() throws Exception {
        String user = requireUid();
        JSONObject data = weapi("/user/playlist", json("uid", user, "limit", 1000, "offset", 0, "includeVideo", true));
        JSONArray rows = data.optJSONArray("playlist"), result = new JSONArray();
        if (rows != null) for (int i = 0; i < rows.length(); i++) {
            JSONObject row = rows.getJSONObject(i), creator = row.optJSONObject("creator");
            result.put(list(row, "coverImgUrl").put("mine", creator != null && String.valueOf(creator.optLong("userId")).equals(user))
                .put("liked", row.optInt("specialType") == 5));
            if (row.optInt("specialType") == 5) likedPid = String.valueOf(row.optLong("id"));
        }
        return json("playlists", result);
    }
    private JSONArray details(JSONArray ids, int limit) throws Exception { return details(ids, 0, limit); }
    private JSONArray details(JSONArray ids, int from, int limit) throws Exception {
        JSONArray songs = new JSONArray();
        int total = ids == null ? 0 : Math.min(ids.length(), limit);
        for (int start = from; start < total; start += 400) {
            JSONArray batch = new JSONArray();
            for (int i = start; i < Math.min(total, start + 400); i++) {
                Object item = ids.get(i);
                batch.put(json("id", item instanceof JSONObject ? ((JSONObject) item).getLong("id") : Long.parseLong(item.toString())));
            }
            JSONObject detail = weapi("/v3/song/detail", json("c", batch.toString()));
            JSONArray rows = songs(detail.optJSONArray("songs"), detail.optJSONArray("privileges"));
            for (int i = 0; i < rows.length(); i++) songs.put(rows.get(i));
        }
        return songs;
    }
    private JSONObject playlist(String value) throws Exception {
        String pid = id(value, "歌单");
        JSONObject data = weapi("/v6/playlist/detail", json("id", pid, "n", 100000, "s", 8));
        JSONObject list = data.optJSONObject("playlist");
        if (list == null) throw new IOException("无法读取歌单");
        JSONArray ids = list.optJSONArray("trackIds");
        JSONObject creator = list.optJSONObject("creator");
        return json("kind", "playlist", "id", pid, "name", list.optString("name"), "cover", coverPath(list.optString("coverImgUrl")),
            "subtitle", (creator == null ? "" : creator.optString("nickname") + " · ") + list.optInt("trackCount") + " 首",
            "description", text(list, "description"), "songs", playlistSongs(list, data.optJSONArray("privileges")), "truncated", ids != null && ids.length() > 3000,
            "mine", creator != null && String.valueOf(creator.optLong("userId")).equals(uid));
    }
    /** The detail response already embeds full rows (and privileges) for most lists; only the remainder is fetched. */
    private JSONArray playlistSongs(JSONObject list, JSONArray privileges) throws Exception {
        JSONArray ids = list.optJSONArray("trackIds"), embedded = list.optJSONArray("tracks");
        int total = ids == null ? 0 : Math.min(ids.length(), 3000);
        if (embedded == null || embedded.length() == 0) return details(ids, total);
        JSONArray songs = songs(embedded, privileges), result = new JSONArray();
        for (int i = 0; i < Math.min(songs.length(), total); i++) result.put(songs.get(i));
        if (embedded.length() < total) { JSONArray rest = details(ids, embedded.length(), total); for (int i = 0; i < rest.length(); i++) result.put(rest.get(i)); }
        return result;
    }
    private JSONObject daily() throws Exception {
        requireUid();
        JSONObject data = weapi("/v3/discovery/recommend/songs", new JSONObject()).optJSONObject("data");
        JSONArray songs = songs(data == null ? null : data.optJSONArray("dailySongs"), null);
        return json("kind", "daily", "name", "每日推荐", "subtitle", "根据你的音乐口味生成 · 每天 6:00 更新", "songs", songs,
            "cover", songs.length() > 0 ? coverPath(songs.getJSONObject(0).optString("cover")) : "cover-placeholder.svg");
    }
    private JSONObject fm() throws Exception {
        requireUid();
        return json("songs", songs(weapi("/v1/radio/get", new JSONObject()).optJSONArray("data"), null));
    }
    private void fmTrash(String song) throws Exception {
        requireUid();
        weapi("/radio/trash/add?alg=RT&songId=" + id(song, "歌曲") + "&time=25", json("songId", song));
    }
    private JSONObject heartbeat(String song, String playlistId) throws Exception {
        requireUid();
        JSONObject data = weapi("/playmode/intelligence/list", json("songId", id(song, "歌曲"), "type", "fromPlayOne",
            "playlistId", id(playlistId, "歌单"), "startMusicId", song, "count", 1));
        if (data.optInt("code") != 200) throw new IOException("心动模式暂不可用（" + data.optInt("code") + "）");
        JSONArray rows = data.optJSONArray("data"), raw = new JSONArray();
        if (rows != null) for (int i = 0; i < rows.length(); i++) { JSONObject info = rows.getJSONObject(i).optJSONObject("songInfo"); if (info != null) raw.put(info); }
        return json("songs", songs(raw, null));
    }
    private JSONObject likes() throws Exception {
        JSONObject data = weapi("/song/like/get", json("uid", requireUid()));
        JSONArray ids = data.optJSONArray("ids"), result = new JSONArray();
        if (ids != null) for (int i = 0; i < ids.length(); i++) result.put(String.valueOf(ids.optLong(i)));
        return json("ids", result);
    }
    /**
     * Red heart. The web FM endpoint (weapi radio/like) is answered with -460 risk control for every caller,
     * so this uses the client endpoint song/like (eapi, then weapi) and, as a last resort, edits the
     * "我喜欢的音乐" playlist directly. Each refusal is logged with its code.
     */
    private JSONObject like(String song, boolean value) throws Exception {
        String user = requireUid(); id(song, "歌曲");
        JSONObject request = json("trackId", song, "userid", user, "like", String.valueOf(value));
        List<String> refusals = new ArrayList<>();
        for (int attempt = 0; attempt < 3; attempt++) {
            String route = attempt == 0 ? "eapi song/like" : attempt == 1 ? "weapi song/like" : "eapi playlist/manipulate";
            try {
                JSONObject data;
                if (attempt == 0) data = eapi("/api/song/like", new JSONObject(request.toString()));
                else if (attempt == 1) data = weapi("/song/like", new JSONObject(request.toString()));
                else data = eapi("/api/playlist/manipulate/tracks", json("op", value ? "add" : "del", "pid", likedPlaylistId(),
                    "trackIds", "[" + song + "]", "imme", "true"));
                int code = data.optInt("code", data.optJSONObject("body") == null ? 0 : data.getJSONObject("body").optInt("code"));
                // 502 from the playlist route: already in (or already out of) the list, which is the wanted state.
                if (code == 200 || attempt == 2 && code == 502) {
                    if (attempt > 0) android.util.Log.i("M3X.Netease", "like via " + route + " after " + refusals);
                    return json("id", song, "liked", value, "message", value ? "已添加到我喜欢的音乐" : "已取消喜欢");
                }
                refusals.add(route + " " + code + " " + text(data, "message"));
            } catch (IOException error) {
                if (String.valueOf(error.getMessage()).contains("登录已失效")) throw error;
                refusals.add(route + " " + error.getMessage());
            }
        }
        android.util.Log.w("M3X.Netease", "like refused: " + refusals);
        throw new IOException((value ? "喜欢失败：" : "取消喜欢失败：") + refusals.get(refusals.size() - 1).replaceFirst("^\\S+ \\S+ ", ""));
    }
    private volatile String likedPid = "";
    private String likedPlaylistId() throws Exception {
        if (likedPid.isEmpty()) userPlaylists();
        if (likedPid.isEmpty()) throw new IOException("没有找到“我喜欢的音乐”歌单");
        return likedPid;
    }
    private JSONObject addToPlaylist(String pid, String song) throws Exception {
        requireUid();
        JSONObject data = weapi("/playlist/manipulate/tracks", json("op", "add", "pid", id(pid, "歌单"), "trackIds", "[" + id(song, "歌曲") + "]", "imme", "true"));
        int code = data.optInt("code", data.optJSONObject("body") == null ? 0 : data.getJSONObject("body").optInt("code"));
        if (code == 502) throw new IOException("歌曲已在歌单中");
        if (code != 200) throw new IOException("收藏到歌单失败（" + code + "）");
        return json("message", "已收藏到歌单");
    }
    private JSONObject comments(String song) throws Exception {
        String rid = "R_SO_4_" + id(song, "歌曲");
        JSONObject data = weapi("/v1/resource/comments/" + rid, json("rid", rid, "limit", 30, "offset", 0));
        JSONObject result = json("total", data.optInt("total"));
        for (String key : new String[] {"hotComments", "comments"}) {
            JSONArray rows = data.optJSONArray(key), out = new JSONArray();
            if (rows != null) for (int i = 0; i < rows.length(); i++) {
                JSONObject row = rows.getJSONObject(i), user = row.optJSONObject("user");
                out.put(json("user", user == null ? "" : user.optString("nickname"), "avatar", user == null ? "" : coverPath(user.optString("avatarUrl")),
                    "content", text(row, "content"), "liked", row.optInt("likedCount"), "time", row.optLong("time")));
            }
            result.put(key.equals("hotComments") ? "hot" : "latest", out);
        }
        return result;
    }
    private JSONObject simi(String song) throws Exception {
        JSONObject data = weapi("/v1/discovery/simiSong", json("songid", id(song, "歌曲"), "limit", 50, "offset", 0));
        JSONArray songs = songs(data.optJSONArray("songs"), null);
        return json("kind", "simi", "name", "相似歌曲", "subtitle", songs.length() + " 首", "songs", songs,
            "cover", songs.length() > 0 ? coverPath(songs.getJSONObject(0).optString("cover")) : "cover-placeholder.svg");
    }
    private JSONObject artist(String value) throws Exception {
        String aid = id(value, "歌手");
        JSONObject data = weapi("/v1/artist/" + aid, new JSONObject()), info = data.optJSONObject("artist");
        return json("kind", "artist", "id", aid, "name", info == null ? "歌手" : info.optString("name"),
            "cover", coverPath(info == null ? "" : info.optString("picUrl")), "subtitle", "热门 50 首",
            "description", text(info, "briefDesc"), "songs", songs(data.optJSONArray("hotSongs"), null));
    }
    private JSONObject album(String value) throws Exception {
        String aid = id(value, "专辑");
        JSONObject data = weapi("/v1/album/" + aid, new JSONObject()), info = data.optJSONObject("album");
        JSONObject artistInfo = info == null ? null : info.optJSONObject("artist");
        return json("kind", "album", "id", aid, "name", info == null ? "专辑" : info.optString("name"),
            "cover", coverPath(info == null ? "" : info.optString("picUrl")),
            "subtitle", (artistInfo == null ? "" : artistInfo.optString("name") + " · ") + (info == null ? "" : new java.text.SimpleDateFormat("yyyy-MM-dd", Locale.ROOT).format(new Date(info.optLong("publishTime")))),
            "description", text(info, "description"), "songs", songs(data.optJSONArray("songs"), null));
    }
    private JSONObject recent() throws Exception {
        JSONObject data = weapi("/v1/play/record", json("uid", requireUid(), "type", 1));
        JSONArray rows = data.optJSONArray("weekData"), raw = new JSONArray();
        if (rows != null) for (int i = 0; i < rows.length(); i++) { JSONObject song = rows.getJSONObject(i).optJSONObject("song"); if (song != null) raw.put(song); }
        if (data.optInt("code") == 406 || rows == null && data.has("message")) throw new IOException("听歌排行未公开或暂不可用");
        JSONArray songs = songs(raw, null);
        return json("kind", "recent", "name", "最近一周常听", "subtitle", songs.length() + " 首", "songs", songs,
            "cover", songs.length() > 0 ? coverPath(songs.getJSONObject(0).optString("cover")) : "cover-placeholder.svg");
    }
    // ---- playback ------------------------------------------------------------------------------------------
    /** Called on ExoPlayer's loading thread. Returns a fresh CDN address for the configured quality. */
    String resolve(String songId) throws IOException {
        if (songId == null || !songId.matches("[0-9]{1,20}")) throw new Denied("网易云歌曲编号无效");
        String level = PlayerApp.instance.settings("netease").optString("level", "lossless");
        if (!LEVELS.contains(level)) level = "lossless";
        synchronized (urls) {
            String[] cached = urls.get(songId + ":" + level);
            if (cached != null && Long.parseLong(cached[1]) > System.currentTimeMillis()) return cached[0];
        }
        JSONObject row;
        try {
            JSONObject request = json("ids", "[" + songId + "]", "level", level, "encodeType", "flac");
            JSONObject data;
            try { data = eapi("/api/song/enhance/player/url/v1", request); }
            catch (IOException error) { data = weapi("/song/enhance/player/url/v1", json("ids", "[" + songId + "]", "level", level, "encodeType", "flac")); }
            JSONArray rows = data.optJSONArray("data");
            row = rows == null || rows.length() == 0 ? null : rows.getJSONObject(0);
        } catch (IOException error) { throw new Denied(error.getMessage().startsWith("网易云") ? error.getMessage() : "网易云连接失败：" + error.getMessage()); }
        catch (Exception error) { throw new Denied("网易云返回了无法识别的播放地址"); }
        String url = row == null ? "" : row.optString("url");
        if (url.isEmpty() || "null".equals(url)) throw new Denied(row != null && row.optInt("fee") == 1 ? "该歌曲需要网易云 VIP" : "该歌曲暂无版权或需要购买");
        if (row.optJSONObject("freeTrialInfo") != null) throw new Denied("该歌曲需要网易云 VIP，当前账号仅能试听片段");
        url = url.replaceFirst("^http://", "https://");
        long life = Math.max(60, Math.min(900, row.optLong("expi", 1200) - 120)) * 1000L;
        synchronized (urls) { urls.put(songId + ":" + level, new String[] { url, String.valueOf(System.currentTimeMillis() + life) }); }
        return url;
    }

    static InputStream cover(String url) throws Exception {
        HttpUrl parsed = HttpUrl.parse(url == null ? "" : url);
        if (parsed == null || !parsed.isHttps() || !(parsed.host().endsWith(".music.126.net") || parsed.host().endsWith(".126.net"))) throw new IOException("封面地址无效");
        File directory = new File(PlayerApp.instance.getCacheDir(), "netease-covers"); directory.mkdirs();
        File file = new File(directory, MusicLibrary.hash(parsed.toString()) + ".img");
        if (!file.isFile()) {
            try (Response response = get().http.newCall(new Request.Builder().url(parsed).header("User-Agent", UA).build()).execute()) {
                if (!response.isSuccessful() || response.body() == null) throw new IOException("封面读取失败");
                byte[] bytes = MusicLibrary.read(response.body().byteStream(), 3 * 1024 * 1024);
                File temporary = new File(directory, file.getName() + ".tmp");
                try (FileOutputStream out = new FileOutputStream(temporary)) { out.write(bytes); }
                if (!temporary.renameTo(file)) temporary.delete();
            }
            File[] all = directory.listFiles();
            if (all != null && all.length > 600) { Arrays.sort(all, Comparator.comparingLong(File::lastModified)); for (int i = 0; i < all.length - 500; i++) all[i].delete(); }
        }
        return new FileInputStream(file);
    }

    private static String qr(String content) throws Exception {
        Map<EncodeHintType, Object> hints = new EnumMap<>(EncodeHintType.class); hints.put(EncodeHintType.MARGIN, 2);
        BitMatrix matrix = new QRCodeWriter().encode(content, BarcodeFormat.QR_CODE, 300, 300, hints);
        int width = matrix.getWidth(), height = matrix.getHeight(); int[] pixels = new int[width * height];
        for (int y = 0; y < height; y++) for (int x = 0; x < width; x++) pixels[y * width + x] = matrix.get(x, y) ? 0xFF000000 : 0xFFFFFFFF;
        Bitmap bitmap = Bitmap.createBitmap(pixels, width, height, Bitmap.Config.ARGB_8888);
        ByteArrayOutputStream out = new ByteArrayOutputStream(); bitmap.compress(Bitmap.CompressFormat.PNG, 100, out); bitmap.recycle();
        return "data:image/png;base64," + android.util.Base64.encodeToString(out.toByteArray(), android.util.Base64.NO_WRAP);
    }

    // ---- bridge --------------------------------------------------------------------------------------------
    void handle(MainActivity activity, JSONObject input) {
        String operation = input.optString("operation"), request = input.optString("requestId");
        try { (WRITES.contains(operation) ? writes : reads).execute(() -> {
            JSONObject result;
            try {
                PlayerApp app = PlayerApp.instance;
                switch (operation) {
                    case "status": result = status(); break;
                    case "qr-create": result = qrCreate(); break;
                    case "qr-check": result = qrCheck(input.optString("key")); break;
                    case "cookie-login": result = cookieLogin(input.optString("cookie")); break;
                    case "logout": logout(); result = json("loggedIn", false, "message", "已退出网易云账号"); break;
                    case "level": {
                        String level = input.optString("level"); if (!LEVELS.contains(level)) throw new IOException("未知音质");
                        JSONObject settings = app.settings("netease"); settings.put("level", level); app.save("netease", settings);
                        synchronized (urls) { urls.clear(); }
                        result = json("level", level, "message", "音质设置已保存，下一首生效"); break;
                    }
                    case "search": result = search(input.optString("query"), input.optInt("offset"), input.optInt("type", 1)); break;
                    case "home": result = home(); break;
                    case "playlists": result = userPlaylists(); break;
                    case "playlist": result = playlist(input.optString("id")); break;
                    case "daily": result = daily(); break;
                    case "fm": result = fm(); break;
                    case "fm-trash": fmTrash(input.optString("id")); result = json("message", "已减少此类推荐"); break;
                    case "heartbeat": result = heartbeat(input.optString("id"), input.optString("playlistId")); break;
                    case "likes": result = likes(); break;
                    case "like": result = like(input.optString("id"), input.optBoolean("like", true)); break;
                    case "playlist-add": result = addToPlaylist(input.optString("pid"), input.optString("id")); break;
                    case "comments": result = comments(input.optString("id")); break;
                    case "simi": result = simi(input.optString("id")); break;
                    case "artist": result = artist(input.optString("id")); break;
                    case "album": result = album(input.optString("id")); break;
                    case "recent": result = recent(); break;
                    case "import": {
                        JSONArray songs = input.getJSONArray("songs");
                        if (songs.length() == 0 || songs.length() > 3000) throw new IOException("歌曲数量无效");
                        result = app.library.importNetease(songs);
                        break;
                    }
                    case "remove-imported": {
                        Set<String> keep = new HashSet<>();
                        JSONArray ids = input.optJSONArray("keep");
                        if (ids != null) for (int i = 0; i < ids.length(); i++) keep.add(ids.optString(i));
                        JSONObject current = app.playback == null ? null : PlayerApp.instance.settings("ui");
                        if (current != null && !current.optString("trackId").isEmpty()) keep.add(current.optString("trackId"));
                        result = json("removed", app.library.removeNetease(keep), "message", "已移除导入的网易云曲目");
                        break;
                    }
                    default: throw new IOException("未知网易云操作");
                }
            } catch (Exception error) {
                android.util.Log.w("M3X.Netease", operation + " failed", error);
                result = json("error", error.getMessage() == null ? "网易云操作失败" : error.getMessage());
            }
            try { result.put("operation", operation).put("requestId", request); } catch (JSONException ignored) {}
            activity.neteaseResult(result);
        }); } catch (RejectedExecutionException error) {
            activity.neteaseResult(json("operation", operation, "requestId", request, "error", "请求过多，请稍候"));
        }
    }
}
