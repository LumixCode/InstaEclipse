package ps.reso.instaeclipse.utils.core;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Instagram -> companion half of the settings sync: decides which of Instagram's settings
 * ({@code instaeclipse_prefs}) go into the companion's copy ({@code instaeclipse_cache}).
 *
 * <p>Mirror image of {@link CompanionSync#pull}: a companion value is still "pending" (newer than
 * anything Instagram has) when its {@code __ts_<key>} stamp is newer than both Instagram's last
 * in-app change ({@code __igts_<key>}) and the last companion value Instagram applied
 * ({@code __applied_<key>}). Pending companion values are kept; for every other key Instagram's
 * value is the effective one and replaces the companion's. So an older value never overwrites a
 * newer one in either direction.
 *
 * <p>Pure Java (no Android) so it can be unit tested.
 */
public final class PrefsMerge {

    static final String TS = "__ts_";
    static final String IG_TS = "__igts_";
    static final String APPLIED = "__applied_";

    /**
     * Keys that are never copied to the companion (secrets, the locked chats' ids and titles, and
     * IPC/bookkeeping extras). The companion's cache is world-readable under LSPosed.
     */
    static final Set<String> EXCLUDED = new HashSet<>(Arrays.asList(
            "lockDirectPasscode", "lockDirectSalt", "lockedDirectThreads",
            IpcSecurity.EXTRA_NONCE));

    static final Set<String> STATUS = Collections.unmodifiableSet(
            new HashSet<>(Arrays.asList("lockPasscodeSet", "restartPendingKeys")));
    static final Set<String> NOT_SEEDED = new HashSet<>(Arrays.asList("customFontPath", "customEmojiPath"));

    private PrefsMerge() {}

    /** True for a key that must stay inside Instagram's process. */
    public static boolean isPrivate(String key) {
        return key != null && EXCLUDED.contains(key);
    }

    public static boolean isStatus(String key) {
        return key != null && STATUS.contains(key);
    }

    /** True for a setting Instagram may share with the companion. */
    public static boolean isSharedSetting(String key, Object value) {
        if (key == null || key.startsWith("__") || isPrivate(key)) return false;
        if (STATUS.contains(key)) return "restartPendingKeys".equals(key) ? value instanceof String : value instanceof Boolean;
        Class<?> type = SettingsSchema.type(key);
        return type != null && type.isInstance(value);
    }

    /** True for the stamps Instagram sends along with its settings. */
    public static boolean isSharedStamp(String key, Object value) {
        return key != null && value instanceof Long
                && (key.startsWith(IG_TS) || key.startsWith(APPLIED));
    }

    /**
     * Returns the settings from {@code instagram} (values plus their {@code __igts_} /
     * {@code __applied_} stamps) that should be written into {@code companion}.
     */
    public static Map<String, Object> select(Map<String, ?> instagram, Map<String, ?> companion) {
        Map<String, Object> out = new LinkedHashMap<>();
        if (instagram == null) return out;
        for (Map.Entry<String, ?> entry : instagram.entrySet()) {
            String key = entry.getKey();
            Object value = entry.getValue();
            if (!isSharedSetting(key, value)) continue;
            Object current = companion != null ? companion.get(key) : null;
            if (Objects.equals(current, value)) continue;
            if (current != null && untouched(instagram, key)) continue;
            long companionTs = stamp(companion, TS + key);
            if (companionTs > stamp(instagram, IG_TS + key)
                    && companionTs > stamp(instagram, APPLIED + key)) {
                continue; // newer companion change Instagram hasn't applied yet
            }
            out.put(key, value);
        }
        return out;
    }

    /**
     * The other direction: the companion's pending values (with their {@code __ts_} stamps) that
     * Instagram has neither applied nor overridden, so Instagram can apply the changes its live
     * broadcast missed. Values equal to Instagram's are included too, so Instagram stamps them
     * applied and they stop being pending.
     */
    public static Map<String, Object> pending(Map<String, ?> companion, Map<String, ?> instagram) {
        Map<String, Object> out = new LinkedHashMap<>();
        if (companion == null) return out;
        for (Map.Entry<String, ?> entry : companion.entrySet()) {
            String tsKey = entry.getKey();
            if (tsKey == null || !tsKey.startsWith(TS) || !(entry.getValue() instanceof Long)) continue;
            String key = tsKey.substring(TS.length());
            Object value = companion.get(key);
            if (!isSharedSetting(key, value) || STATUS.contains(key)) continue;
            long ts = (Long) entry.getValue();
            if (ts <= stamp(instagram, IG_TS + key) || ts <= stamp(instagram, APPLIED + key)) continue;
            out.put(key, value);
            out.put(tsKey, ts);
        }
        for (Map.Entry<String, ?> entry : companion.entrySet()) {
            String key = entry.getKey();
            Object value = entry.getValue();
            if (out.containsKey(key) || !isSharedSetting(key, value) || STATUS.contains(key) || NOT_SEEDED.contains(key)) continue;
            if (!untouched(instagram, key) || Objects.equals(value, SettingsSchema.defaultValue(key))) continue;
            out.put(key, value);
            out.put(TS + key, Math.max(stamp(companion, TS + key), 1L));
        }
        return out;
    }

    public static boolean untouched(Map<String, ?> instagram, String key) {
        if (LegacyPaths.has(LegacyPaths.SEED) || key == null) return false;
        if (instagram == null) return true;
        if (instagram.containsKey(IG_TS + key) || instagram.containsKey(APPLIED + key)) return false;
        Object value = instagram.get(key);
        return value == null || value.equals(SettingsSchema.defaultValue(key));
    }

    private static long stamp(Map<String, ?> map, String key) {
        if (map == null) return 0;
        Object v = map.get(key);
        return v instanceof Long ? (Long) v : 0;
    }
}
