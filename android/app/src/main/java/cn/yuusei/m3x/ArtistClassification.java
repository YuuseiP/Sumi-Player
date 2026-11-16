package cn.yuusei.m3x;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import java.security.MessageDigest;
import java.util.*;

final class ArtistClassification {
    private final Map<String, JSONObject> entities = new LinkedHashMap<>();
    private final Map<String, JSONObject> paths = new HashMap<>();
    private final Map<String, JSONObject> artists = new java.util.concurrent.ConcurrentHashMap<>();
    private final JSONObject tags;
    private static final java.util.regex.Pattern VOLUME = java.util.regex.Pattern.compile("^[^/]*:");
    final String baseline, folderName;
    final int trackCount;

    ArtistClassification(JSONObject config) throws JSONException {
        if (config.getInt("schema") != 1) throw new IllegalArgumentException("Unsupported classification schema");
        baseline = config.getString("baseline");
        folderName = config.getString("folderName");
        tags = config.getJSONObject("tags");
        JSONArray list = config.getJSONArray("entities");
        for (int i = 0; i < list.length(); i++) {
            JSONObject entity = list.getJSONObject(i);
            entities.put(entity.getString("id"), entity);
            if (entity.getString("kind").equals("artist")) artists.put(entity.getString("name"), entity);
        }
        list = config.getJSONArray("tracks");
        trackCount = list.length();
        for (int i = 0; i < list.length(); i++) {
            JSONObject track = list.getJSONObject(i);
            paths.put(track.getString("path"), track);
        }
    }

    JSONObject apply(JSONObject source, String locator) throws Exception {
        JSONObject track = new JSONObject();
        for (Iterator<String> keys = source.keys(); keys.hasNext();) {
            String key = keys.next();
            track.put(key, source.get(key));
        }
        String raw = track.optString("originalArtist", track.optString("artist", "未知歌手"));
        // Runs once per track at startup: precompiled pattern, no per-call split.
        String path = locator.isEmpty() ? "" : VOLUME.matcher(locator.replace('\\', '/')).replaceFirst("");
        JSONObject known = null;
        // Match a complete saved relative path, regardless of the device volume or selected root.
        for (int start = 0; start < path.length();) {
            known = paths.get(path.substring(start));
            if (known != null) break;
            int slash = path.indexOf('/', start);
            if (slash < 0) break;
            start = slash + 1;
        }
        JSONArray memberships = null;
        if (!track.has("cueStart") && known != null && known.optLong("bytes") == track.optLong("bytes"))
            memberships = known.getJSONArray("memberships");
        else if (!path.isEmpty() && ("/" + path + "/").contains("/" + folderName + "/"))
            memberships = tags.optJSONArray(raw);
        JSONArray credits = new JSONArray();
        if (memberships != null) {
            for (int i = 0; i < memberships.length(); i++) {
                JSONObject entity = entities.get(memberships.getString(i));
                if (entity == null) throw new IllegalArgumentException("Unknown built-in classification entity");
                credits.put(entity);
            }
        }
        if (credits.length() == 0) {
            JSONObject artist = artists.get(raw);
            if (artist == null) {
                artist = new JSONObject().put("id", "artist-raw-" + identity(raw)).put("kind", "artist").put("name", raw);
                artists.put(raw, artist);
            }
            credits.put(artist);
        }
        List<String> names = new ArrayList<>();
        for (int i = 0; i < credits.length(); i++) names.add(credits.getJSONObject(i).getString("name"));
        return track.put("originalArtist", raw).put("artist", String.join(" / ", names))
            .put("credits", credits).put("classificationMatched", memberships != null);
    }

    /**
     * For a track restored from an already classified catalog: points its credits at the shared entity objects
     * instead of per-track copies. Returns false when the track was never classified and needs {@link #apply}.
     */
    boolean relink(JSONObject track) throws JSONException {
        JSONArray credits = track.optJSONArray("credits");
        if (credits == null || !track.has("originalArtist") || !track.has("classificationMatched")) return false;
        for (int i = 0; i < credits.length(); i++) {
            JSONObject credit = credits.optJSONObject(i);
            if (credit == null) return false;
            JSONObject shared = entities.get(credit.optString("id"));
            if (shared == null && "artist".equals(credit.optString("kind"))) {
                JSONObject known = artists.putIfAbsent(credit.optString("name"), credit);
                shared = known == null ? credit : known;
            }
            if (shared == null) return false; // an entity removed from the built-in rules
            if (shared != credit) credits.put(i, shared);
        }
        return true;
    }

    private static String identity(String text) throws Exception {
        byte[] digest = MessageDigest.getInstance("SHA-256").digest(text.getBytes("UTF-8"));
        StringBuilder result = new StringBuilder();
        for (int i = 0; i < 8; i++) result.append(String.format(Locale.ROOT, "%02x", digest[i]));
        return result.toString();
    }
}
