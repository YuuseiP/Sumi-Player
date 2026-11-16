package cn.yuusei.m3x;

import java.text.Normalizer;
import java.util.*;
import java.util.regex.*;
import org.json.*;
import static cn.yuusei.m3x.PlayerApp.json;

/** One native classifier supplies both the WebView preview and background queue. */
final class PreferenceRules {
    static final Set<String> CATEGORIES = new LinkedHashSet<>(Arrays.asList("backing", "solo-explicit", "solo-named", "group-named", "short", "piano"));
    private static final Pattern LABEL = Pattern.compile("\\(([^()]*)\\)|\\[([^\\[\\]]*)\\]|【([^【】]*)】");
    private static final Pattern VERSION = Pattern.compile("^(.*?)\\s*(?:version|ver\\.?)$");
    private static final Pattern TAIL = Pattern.compile("^(?:(?:version|ver\\.?)\\s*)?(?:$|[)\\]】]|\\[(?:ort\\b|\\d))");
    private final List<Rule> rules = new ArrayList<>();
    private final Set<String> characters = new HashSet<>();
    private final Set<String> groups = new HashSet<>();
    final JSONArray categories;

    private static final class Rule {
        final String id, category, label;
        final Pattern pattern;
        Rule(JSONObject value) throws JSONException {
            id = value.getString("id"); category = value.getString("category"); label = value.getString("label");
            pattern = Pattern.compile(value.getString("pattern"));
        }
    }

    PreferenceRules(JSONObject config) throws JSONException {
        if (config.getInt("schema") != 1) throw new IllegalArgumentException("Unsupported preference rule schema");
        categories = config.getJSONArray("categories");
        JSONArray entries = config.getJSONArray("rules");
        for (int i = 0; i < entries.length(); i++) rules.add(new Rule(entries.getJSONObject(i)));
        entries = config.getJSONArray("characters");
        for (int i = 0; i < entries.length(); i++) characters.add(compact(entries.getString(i)));
        entries = config.optJSONArray("groups");
        if (entries != null) for (int i = 0; i < entries.length(); i++) groups.add(compact(entries.getString(i)));
    }

    private static String normalize(String value) { return Normalizer.normalize(value, Normalizer.Form.NFKC).toLowerCase(Locale.ROOT); }
    private static String compact(String value) { return normalize(value).replaceAll("\\s+", ""); }
    private JSONArray matches(String text, String field) throws JSONException {
        text = normalize(text);
        JSONArray hits = new JSONArray();
        for (Rule rule : rules) {
            Matcher match = rule.pattern.matcher(text);
            while (match.find()) if (TAIL.matcher(text.substring(match.end()).trim()).find()) {
                hits.put(json("rule", rule.id, "category", rule.category, "label", rule.label, "field", field, "evidence", match.group()));
                break;
            }
        }
        Matcher labels = LABEL.matcher(text);
        while (labels.find()) {
            String label = labels.group(1) != null ? labels.group(1) : labels.group(2) != null ? labels.group(2) : labels.group(3);
            Matcher version = VERSION.matcher(label.trim());
            if (version.matches()) {
                String subject = compact(version.group(1));
                if (characters.contains(subject)) hits.put(json("rule", "named-character-version", "category", "solo-named", "label", "单角色名 Ver.", "field", field, "evidence", label));
                if (groups.contains(subject)) hits.put(json("rule", "named-group-version", "category", "group-named", "label", "组合名 Ver.", "field", field, "evidence", label));
            }
        }
        return hits;
    }

    private static Set<String> categories(JSONArray hits) throws JSONException {
        Set<String> result = new LinkedHashSet<>();
        for (int i = 0; i < hits.length(); i++) result.add(hits.getJSONObject(i).getString("category"));
        return result;
    }

    JSONObject annotate(JSONObject track) throws JSONException {
        String title = track.optString("title").trim();
        String filename = track.optString("filename").replaceFirst("\\.[^.]+$", "");
        JSONArray titleHits = matches(title, "title");
        // A CUE filename names the container, not its individual song.
        JSONArray fileHits = track.has("cueStart") ? new JSONArray() : matches(filename, "filename");
        Set<String> primary = categories(title.isEmpty() ? fileHits : titleHits);
        Set<String> extra = categories(fileHits); extra.removeAll(primary);
        JSONArray reasons = new JSONArray();
        for (JSONArray source : new JSONArray[]{titleHits, fileHits}) for (int i = 0; i < source.length(); i++) reasons.put(source.getJSONObject(i));
        track.put("preference", json("categories", new JSONArray(primary), "reasons", reasons,
            "needsReview", !title.isEmpty() && !extra.isEmpty()));
        return track;
    }

    static final class Settings {
        final Set<String> enabled = new LinkedHashSet<>(), keep = new LinkedHashSet<>(), exclude = new LinkedHashSet<>();
        final boolean hideInLibrary;
        Settings(JSONObject value) {
            if (value == null) value = new JSONObject();
            if (value.has("schema") && value.optInt("schema") != 1) throw new IllegalArgumentException("不支持的听歌喜好版本");
            read(value, "enabledCategories", enabled, CATEGORIES);
            read(value, "keepTrackIds", keep, null); read(value, "excludeTrackIds", exclude, null);
            if (value.has("hideInLibrary") && !(value.opt("hideInLibrary") instanceof Boolean)) throw new IllegalArgumentException("听歌喜好隐藏设置无效");
            hideInLibrary = value.optBoolean("hideInLibrary");
        }
        private static void read(JSONObject value, String key, Set<String> result, Set<String> allowed) {
            if (!value.has(key)) return;
            JSONArray array = value.optJSONArray(key);
            if (array == null || array.length() > 20000) throw new IllegalArgumentException("听歌喜好列表无效：" + key);
            for (int i = 0; i < array.length(); i++) {
                Object entry = array.opt(i);
                if (!(entry instanceof String) || ((String) entry).isEmpty() || ((String) entry).length() > 256
                        || allowed != null && !allowed.contains(entry)) throw new IllegalArgumentException("听歌喜好项目无效：" + key);
                result.add((String) entry);
            }
        }
        boolean excludes(JSONObject track) {
            String id = track.optString("id");
            if (keep.contains(id)) return false;
            if (exclude.contains(id)) return true;
            JSONObject info = track.optJSONObject("preference");
            JSONArray found = info == null ? null : info.optJSONArray("categories");
            if (found != null) for (int i = 0; i < found.length(); i++) if (enabled.contains(found.optString(i))) return true;
            return false;
        }
        JSONObject jsonValue() {
            return json("schema", 1, "enabledCategories", new JSONArray(enabled), "keepTrackIds", new JSONArray(keep),
                "excludeTrackIds", new JSONArray(exclude), "hideInLibrary", hideInLibrary);
        }
    }

    static Settings settings(JSONObject ui) { return new Settings(ui.optJSONObject("preferences")); }
    static void validateUi(JSONObject ui) throws JSONException {
        if (!ui.has("preferences")) return;
        if (!(ui.opt("preferences") instanceof JSONObject)) throw new IllegalArgumentException("听歌喜好设置无效");
        ui.put("preferences", settings(ui).jsonValue());
    }
}
