package cn.yuusei.m3x;

import org.json.*;
import java.text.Normalizer;
import java.util.*;

/**
 * Pure candidate ranking for automatic lyrics. A candidate is only eligible when its platform duration
 * is within {@link #MAX_DELTA_MS} of the local track and its title agrees; artist agreement only ranks.
 */
final class LyricsAutoMatch {
    static final long MAX_DELTA_MS = 1000;
    static final int MAX_FETCHES = 4;
    private static final String[] VERSIONS = {"live", "remix", "instrumental", "inst.", "off vocal", "offvocal", "karaoke", "伴奏",
        "tv size", "tvsize", "short", "game size", "acoustic", "cover", "翻唱", "現場", "现场", "ver.", "version", "mix"};

    private LyricsAutoMatch() {}

    static String normalize(String value) {
        String text = Normalizer.normalize(value == null ? "" : value, Normalizer.Form.NFKC).toLowerCase(Locale.ROOT);
        return text.replaceAll("[\\s\\p{P}\\p{S}・～〜]+", ""); // \p{P}/\p{S}: supported by both JVM and Android ICU
    }

    /** Removes bracketed annotations such as translated titles or "(feat. …)" for a looser comparison. */
    static String base(String value) {
        String text = Normalizer.normalize(value == null ? "" : value, Normalizer.Form.NFKC);
        String stripped = text.replaceAll("[(\\[（【「『〔][^)\\]）】」』〕]*[)\\]）】」』〕]", " ").replaceAll("\\s+-\\s+.*$", "");
        String result = normalize(stripped);
        return result.isEmpty() ? normalize(text) : result;
    }

    private static Set<String> versions(String title) {
        String text = Normalizer.normalize(title == null ? "" : title, Normalizer.Form.NFKC).toLowerCase(Locale.ROOT);
        Set<String> found = new HashSet<>();
        for (String marker : VERSIONS) if (text.contains(marker)) found.add(marker.replace(" ", "").replace(".", ""));
        return found;
    }

    static Set<String> artists(String value) {
        Set<String> result = new HashSet<>();
        for (String part : (value == null ? "" : value).split("\\s*(?:/|,|，|、|&|＆|;|；|\\bfeat\\.?|\\bft\\.?|\\bx\\b|×|\\(CV[.:：]?|（CV[.:：]?|\\)|）)\\s*")) {
            String name = normalize(part);
            if (!name.isEmpty() && !name.equals("cv")) result.add(name);
        }
        return result;
    }

    /** Returns -1 when ineligible, otherwise a score (higher is better). */
    static int score(JSONObject track, JSONObject candidate) {
        long local = Math.round(track.optDouble("duration", 0) * 1000), remote = candidate.optLong("durationMs", 0);
        if (local <= 0 || remote <= 0 || Math.abs(remote - local) > MAX_DELTA_MS) return -1;
        String title = track.optString("title"), other = candidate.optString("title");
        if (normalize(title).isEmpty() || normalize(other).isEmpty()) return -1;
        int score;
        if (normalize(title).equals(normalize(other))) score = 30;
        else if (base(title).equals(base(other))) score = 20;
        else if (base(title).length() >= 2 && base(other).length() >= 2 && (base(title).contains(base(other)) || base(other).contains(base(title)))) score = 10;
        else return -1;
        // A live/remix/off-vocal release can share the duration: the candidate may not name a version the local title lacks.
        if (!versions(title).containsAll(versions(other))) return -1;
        Set<String> mine = artists(track.optString("originalArtist", track.optString("artist")));
        mine.addAll(artists(track.optString("artist")));
        Set<String> theirs = artists(candidate.optString("artist"));
        for (String name : theirs) if (mine.contains(name)) { score += 8; break; }
        if (!track.optString("album").isEmpty() && base(track.optString("album")).equals(base(candidate.optString("album")))) score += 4;
        long delta = Math.abs(remote - local);
        return score * 10000 + (int) (MAX_DELTA_MS - delta);
    }

    /** Eligible candidates, best first. Each entry gets "autoScore" and "durationDeltaMs". */
    static List<JSONObject> rank(JSONObject track, JSONArray... lists) throws JSONException {
        List<JSONObject> result = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        long local = Math.round(track.optDouble("duration", 0) * 1000);
        for (JSONArray list : lists) {
            if (list == null) continue;
            for (int i = 0; i < list.length(); i++) {
                JSONObject row = list.getJSONObject(i);
                if (!seen.add(row.optString("provider") + ":" + row.optString("songId"))) continue;
                int value = score(track, row);
                if (value < 0) continue;
                JSONObject copy = new JSONObject(row.toString());
                copy.put("autoScore", value).put("durationDeltaMs", copy.optLong("durationMs") - local);
                result.add(copy);
            }
        }
        result.sort((a, b) -> Integer.compare(b.optInt("autoScore"), a.optInt("autoScore")));
        return result;
    }

    /** Platform placeholders such as "纯音乐，请欣赏" are not lyrics. */
    static boolean placeholder(String original) throws Exception {
        List<LyricsDocument.Line> lines = LyricsDocument.parse(original);
        int text = 0;
        for (LyricsDocument.Line line : lines) if (!line.text.isEmpty() && !line.text.matches("^(作词|作曲|编曲|作詞|編曲|词|曲|Lyrics|Composer)\\s*[:：].*")) text++;
        return text < 3 && (original.contains("纯音乐") || original.contains("純音樂") || original.contains("此歌曲为没有填词的纯音乐") || text == 0);
    }
}
