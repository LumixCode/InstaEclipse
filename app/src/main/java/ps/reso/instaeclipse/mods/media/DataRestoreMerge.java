package ps.reso.instaeclipse.mods.media;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Set;

final class DataRestoreMerge {

    private static final String POST_COUNTS = "ie_post_counts.json";
    private static final String INDEX = "ie_watchlist_";
    private static final String PROFILE = "ie_watch_";
    private static final String HISTORY = "ie_followers_history_";
    private static final String JSON = ".json";

    private enum Kind { BACKUP, INDEX, PROFILE, HISTORY, KEEP_CURRENT }

    private DataRestoreMerge() {}

    private static Kind kind(String name) {
        if (name == null || !name.endsWith(JSON)) return Kind.BACKUP;
        if (name.equals(POST_COUNTS)) return Kind.KEEP_CURRENT;
        if (name.startsWith(INDEX)) return Kind.INDEX;
        if (name.startsWith(PROFILE)) return Kind.PROFILE;
        if (name.startsWith(HISTORY)) return Kind.HISTORY;
        return Kind.BACKUP;
    }

    static boolean merges(String name) {
        return kind(name) != Kind.BACKUP;
    }

    static byte[] merge(String name, byte[] current, byte[] backup) throws JSONException {
        Kind kind = kind(name);
        if (kind == Kind.BACKUP) return backup;
        JSONObject fromBackup = new JSONObject(new String(backup, StandardCharsets.UTF_8));
        JSONObject onPhone = readable(current);
        if (onPhone == null) return backup;
        switch (kind) {
            case PROFILE:
                return fromBackup.optLong("last") > onPhone.optLong("last") ? backup : current;
            case INDEX:
                return bytes(index(onPhone, fromBackup));
            case HISTORY:
                return bytes(history(onPhone, fromBackup));
            default:
                return bytes(keepCurrent(onPhone, fromBackup));
        }
    }

    private static JSONObject readable(byte[] current) {
        if (current == null) return null;
        try {
            return new JSONObject(new String(current, StandardCharsets.UTF_8));
        } catch (JSONException damaged) {
            return null;
        }
    }

    private static byte[] bytes(JSONObject o) {
        return o.toString().getBytes(StandardCharsets.UTF_8);
    }

    private static JSONObject index(JSONObject onPhone, JSONObject fromBackup) throws JSONException {
        Set<String> ids = new HashSet<>();
        JSONArray rows = new JSONArray();
        addRows(rows, ids, onPhone.optJSONArray("profiles"));
        addRows(rows, ids, fromBackup.optJSONArray("profiles"));
        onPhone.put("profiles", rows);
        return onPhone;
    }

    private static void addRows(JSONArray rows, Set<String> ids, JSONArray from) {
        for (int i = 0; from != null && i < from.length(); i++) {
            JSONObject row = from.optJSONObject(i);
            String id = row != null ? row.optString("id") : "";
            if (!id.isEmpty() && ids.add(id)) rows.put(row);
        }
    }

    private static JSONObject history(JSONObject onPhone, JSONObject fromBackup) throws JSONException {
        JSONObject newer = fromBackup.optLong("lastCheck") > onPhone.optLong("lastCheck") ? fromBackup : onPhone;
        Set<String> keys = new HashSet<>();
        List<JSONObject> events = new ArrayList<>();
        addEvents(events, keys, onPhone.optJSONArray("events"));
        addEvents(events, keys, fromBackup.optJSONArray("events"));
        events.sort((a, b) -> Long.compare(b.optLong("ts"), a.optLong("ts")));
        JSONArray out = new JSONArray();
        for (JSONObject e : events) out.put(e);
        newer.put("events", out);
        return newer;
    }

    private static void addEvents(List<JSONObject> events, Set<String> keys, JSONArray from) {
        for (int i = 0; from != null && i < from.length(); i++) {
            JSONObject e = from.optJSONObject(i);
            if (e == null) continue;
            String key = e.optString("id") + '\u0000' + e.optString("type") + '\u0000' + e.optLong("ts");
            if (keys.add(key)) events.add(e);
        }
    }

    private static JSONObject keepCurrent(JSONObject onPhone, JSONObject fromBackup) throws JSONException {
        JSONObject out = new JSONObject();
        for (Iterator<String> it = fromBackup.keys(); it.hasNext(); ) {
            String k = it.next();
            if (!onPhone.has(k)) out.put(k, fromBackup.opt(k));
        }
        for (Iterator<String> it = onPhone.keys(); it.hasNext(); ) {
            String k = it.next();
            out.put(k, onPhone.opt(k));
        }
        return out;
    }
}
