package ps.reso.instaeclipse.utils.core;

import java.util.Arrays;
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
            IpcSecurity.EXTRA_NONCE, "pathJustSetLocally"));

    private PrefsMerge() {}

    /** True for a key that must stay inside Instagram's process. */
    public static boolean isPrivate(String key) {
        return key != null && EXCLUDED.contains(key);
    }

    /** True for a setting Instagram may share with the companion. */
    public static boolean isSharedSetting(String key, Object value) {
        if (key == null || key.startsWith("__") || isPrivate(key)) return false;
        return value instanceof Boolean || value instanceof Integer || value instanceof String;
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
            if (!isSharedSetting(key, value)) continue;
            long ts = (Long) entry.getValue();
            if (ts <= stamp(instagram, IG_TS + key) || ts <= stamp(instagram, APPLIED + key)) continue;
            out.put(key, value);
            out.put(tsKey, ts);
        }
        return out;
    }

    private static long stamp(Map<String, ?> map, String key) {
        if (map == null) return 0;
        Object v = map.get(key);
        return v instanceof Long ? (Long) v : 0;
    }
}
