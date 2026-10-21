package ps.reso.instaeclipse.utils.ghost;

import org.json.JSONObject;
import java.util.LinkedHashMap;
import java.util.Map;
import ps.reso.instaeclipse.utils.feature.FeatureFlags;
import ps.reso.instaeclipse.utils.core.SettingsManager;

/** Canonical Direct thread IDs and labels; persisted through the existing settings store. */
public final class LockedThreads {
    private static String cachedJson;
    private static final Map<String, String> threads = new LinkedHashMap<>();

    private LockedThreads() {}

    public static synchronized Map<String, String> all() {
        load();
        return new LinkedHashMap<>(threads);
    }

    public static synchronized boolean contains(String id) {
        load();
        return id != null && threads.containsKey(id);
    }

    public static synchronized void set(String id, String title, boolean locked) {
        if (id == null || id.isEmpty()) return;
        load();
        if (locked) threads.put(id, title == null ? "" : title);
        else threads.remove(id);
        FeatureFlags.lockedDirectThreads = new JSONObject(threads).toString();
        cachedJson = FeatureFlags.lockedDirectThreads;
        SettingsManager.saveAllFlags();
    }

    private static void load() {
        String json = FeatureFlags.lockedDirectThreads;
        if (json != null && json.equals(cachedJson)) return;
        try {
            JSONObject parsed = new JSONObject(json == null ? "{}" : json);
            Map<String, String> next = new LinkedHashMap<>();
            for (java.util.Iterator<String> it = parsed.keys(); it.hasNext();) {
                String id = it.next();
                next.put(id, parsed.optString(id, ""));
            }
            threads.clear();
            threads.putAll(next);
            cachedJson = json;
        } catch (org.json.JSONException ignored) {
            // A malformed settings update must not erase previously protected IDs.
        }
    }
}
