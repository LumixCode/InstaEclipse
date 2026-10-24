package ps.reso.instaeclipse.utils.backup;

import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import ps.reso.instaeclipse.utils.core.SettingsManager;
import ps.reso.instaeclipse.utils.core.SettingsSchema;
import ps.reso.instaeclipse.utils.feature.FeatureFlags;
import ps.reso.instaeclipse.utils.media.StoryCacheRetention;

/**
 * Lock settings (passcode, salt, locked chats and every lock switch) are never part of a backup:
 * restoring one must not turn a lock off without the passcode.
 */
public class SettingsBackupManager {

    private static final int VERSION = 1;
    private static final String NIGHT_START = "nightReelBlockStartMinutes";
    private static final String NIGHT_END = "nightReelBlockEndMinutes";

    public static final class Result {
        public final List<String> changedKeys;
        public final int skipped;

        Result(List<String> changedKeys, int skipped) {
            this.changedKeys = Collections.unmodifiableList(changedKeys);
            this.skipped = skipped;
        }
    }

    public static String toJson() throws JSONException {
        JSONObject s = new JSONObject();
        for (String key : SettingsSchema.backupKeys(false)) {
            s.put(key, "storyCacheHours".equals(key)
                    ? StoryCacheRetention.normalize(FeatureFlags.storyCacheHours) : SettingsSchema.read(key));
        }
        JSONObject root = new JSONObject();
        root.put("version", VERSION);
        root.put("settings", s);
        return root.toString(2);
    }

    public static Result restore(String json) throws JSONException {
        JSONObject root = new JSONObject(json);
        JSONObject s = root.has("settings") ? root.getJSONObject("settings") : root;
        Map<String, Object> values = new HashMap<>();
        for (Iterator<String> it = s.keys(); it.hasNext(); ) {
            String key = it.next();
            Object value = s.opt(key);
            values.put(key, value == JSONObject.NULL ? null : value);
        }
        for (Map.Entry<String, Object> edit : SettingsSchema.migrate(values).entrySet()) {
            if (edit.getValue() == null) values.remove(edit.getKey());
            else values.put(edit.getKey(), edit.getValue());
        }
        Map<String, Object> staged = new LinkedHashMap<>();
        int skipped = 0;
        for (String key : SettingsSchema.backupKeys(false)) {
            if (!values.containsKey(key)) continue;
            Object value = SettingsSchema.restorable(key, values.get(key));
            boolean locked = value != null && !value.equals(SettingsSchema.read(key))
                    && SettingsManager.isLockedByExtreme(key, value);
            if (value == null || locked) {
                skipped++;
                continue;
            }
            staged.put(key, value);
        }
        Object start = staged.containsKey(NIGHT_START) ? staged.get(NIGHT_START) : SettingsSchema.read(NIGHT_START);
        Object end = staged.containsKey(NIGHT_END) ? staged.get(NIGHT_END) : SettingsSchema.read(NIGHT_END);
        if (start.equals(end)) {
            staged.remove(NIGHT_START);
            staged.remove(NIGHT_END);
        }
        List<String> changed = new ArrayList<>();
        synchronized (SettingsManager.LOCK) {
            Map<String, Object> before = new HashMap<>();
            for (String key : staged.keySet()) before.put(key, SettingsSchema.read(key));
            for (Map.Entry<String, Object> e : staged.entrySet()) SettingsSchema.write(e.getKey(), e.getValue());
            for (String key : staged.keySet()) {
                if (!Objects.equals(before.get(key), SettingsSchema.read(key))) changed.add(key);
            }
        }
        return new Result(changed, skipped);
    }

    public static void fromJson(String json) throws JSONException {
        restore(json);
    }

    public static String restorableString(String key, String value) {
        if (key == null || value == null) return null;
        if (SettingsSchema.type(key) != String.class) return value;
        Object restored = SettingsSchema.restorable(key, value);
        return restored instanceof String ? (String) restored : null;
    }
}
