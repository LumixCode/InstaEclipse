package ps.reso.instaeclipse.utils.backup;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import ps.reso.instaeclipse.utils.core.CommonUtils;
import ps.reso.instaeclipse.utils.core.CompanionUiPrefs;
import ps.reso.instaeclipse.utils.core.PrefsMerge;
import ps.reso.instaeclipse.utils.core.SettingsManager;
import ps.reso.instaeclipse.utils.core.SettingsSchema;
import ps.reso.instaeclipse.utils.ipc.ModuleReplies;

public final class CompanionRestorePlan {

    private static final Set<String> DEVICE_LOCAL = Collections.unmodifiableSet(new HashSet<>(Arrays.asList(
            "dailyReelsWatchedToday", "dailyReelUsageDate", CompanionUiPrefs.BACKGROUND_ACCESS_ASKED,
            "pathJustSetLocally")));

    public final LinkedHashMap<String, Object> writes;
    public final Map<String, Object> uiWrites;
    public final int skipped;
    public final boolean turnsLocksOn;

    private CompanionRestorePlan(LinkedHashMap<String, Object> writes, Map<String, Object> uiWrites, int skipped,
                                 boolean turnsLocksOn) {
        this.writes = writes;
        this.uiWrites = uiWrites;
        this.skipped = skipped;
        this.turnsLocksOn = turnsLocksOn;
    }

    public int count() {
        return writes.size() + uiWrites.size();
    }

    public static CompanionRestorePlan build(Map<String, ?> settings, Map<String, ?> current) {
        Map<String, Object> values = new LinkedHashMap<>(settings);
        for (Map.Entry<String, Object> edit : SettingsSchema.migrate(values).entrySet()) {
            if (edit.getValue() == null) values.remove(edit.getKey());
            else values.put(edit.getKey(), edit.getValue());
        }
        List<String> keys = new ArrayList<>(values.keySet());
        keys.sort(Comparator.comparing("isExtremeMode"::equals));
        LinkedHashMap<String, Object> writes = new LinkedHashMap<>();
        Map<String, Object> uiWrites = new LinkedHashMap<>();
        int skipped = 0;
        boolean locksOn = false;
        for (String key : keys) {
            Object raw = values.get(key);
            if (key.startsWith("__") || PrefsMerge.isPrivate(key) || DEVICE_LOCAL.contains(key)
                    || SettingsSchema.LEGACY_IGNORED.contains(key)) {
                continue;
            }
            if (CompanionUiPrefs.HOME_PINNED_SHORTCUTS.equals(key)) {
                if (raw instanceof String && ((String) raw).length() <= SettingsSchema.MAX_STRING_CHARS) {
                    uiWrites.put(key, raw);
                } else {
                    skipped++;
                }
                continue;
            }
            if (CommonUtils.STALE_CACHE_KEYS.contains(key)) continue;
            if (SettingsManager.isPasscodeLock(key)) {
                boolean on = Boolean.TRUE.equals(current.get(key));
                if (Boolean.TRUE.equals(raw)) {
                    writes.put(key, true);
                    if (!on) locksOn = true;
                } else if (on) {
                    skipped++;
                }
                continue;
            }
            if ("lockedCollections".equals(key)) {
                skipped++;
                continue;
            }
            Object value = restorable(key, raw);
            if (value == null) {
                skipped++;
                continue;
            }
            writes.put(key, value);
        }
        return new CompanionRestorePlan(writes, uiWrites, skipped, locksOn);
    }

    private static Object restorable(String key, Object raw) {
        Class<?> type = SettingsSchema.type(key);
        if (type == null) return null;
        Object value = raw;
        if (raw instanceof Number) {
            if (type != Integer.class) return null;
            value = wholeInt((Number) raw);
            if (value == null) return null;
        } else if (raw instanceof String && type != String.class) {
            return null;
        }
        Object restored = SettingsSchema.restorable(key, value);
        return restored != null && ModuleReplies.isPreferenceValue(key, restored) ? restored : null;
    }

    private static Integer wholeInt(Number n) {
        if (n instanceof Integer) return (Integer) n;
        if (n instanceof Long) {
            long v = n.longValue();
            return v == (int) v ? (int) v : null;
        }
        double d = n.doubleValue();
        return d == Math.rint(d) && d >= Integer.MIN_VALUE && d <= Integer.MAX_VALUE ? (int) d : null;
    }
}
