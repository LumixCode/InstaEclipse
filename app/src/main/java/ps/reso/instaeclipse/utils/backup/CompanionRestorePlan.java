package ps.reso.instaeclipse.utils.backup;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import ps.reso.instaeclipse.utils.core.CommonUtils;
import ps.reso.instaeclipse.utils.core.PrefsMerge;
import ps.reso.instaeclipse.utils.core.SettingsManager;
import ps.reso.instaeclipse.utils.media.StoryCacheRetention;

public final class CompanionRestorePlan {

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

    public static CompanionRestorePlan build(Map<String, ?> settings, Map<String, ?> current) {
        Map<String, Object> s = new LinkedHashMap<>(settings);
        if (Boolean.TRUE.equals(s.get("blockLocationPrompts"))) s.put("hideOnboardingPrompts", true);
        List<String> keys = new ArrayList<>(s.keySet());
        keys.sort(Comparator.comparing("isExtremeMode"::equals));
        int skipped = 0;
        LinkedHashMap<String, Object> writes = new LinkedHashMap<>();
        for (String key : keys) {
            String target = key;
            if ("hideCreateGroupButton".equals(key)) {
                if (s.containsKey("hideShareCreateGroup")) continue;
                target = "hideShareCreateGroup";
            } else if (CommonUtils.STALE_CACHE_KEYS.contains(key)) {
                continue;
            }
            if (target.startsWith("__") || PrefsMerge.isPrivate(target)) continue;
            Object val = s.get(key);
            if (SettingsManager.isPasscodeLock(target) && !Boolean.TRUE.equals(val)) {
                if (Boolean.TRUE.equals(current.get(target))) skipped++;
                continue;
            }
            if ("lockedCollections".equals(target)) {
                skipped++;
                continue;
            }
            if ("storyCacheHours".equals(key)) val = StoryCacheRetention.normalize(optInt(val));
            if (val instanceof Boolean) {
                writes.put(target, val);
            } else if (val instanceof Number) {
                writes.put(target, ((Number) val).intValue());
            } else if (val instanceof String) {
                String value = SettingsBackupManager.restorableString(target, (String) val);
                if (value == null) {
                    skipped++;
                    continue;
                }
                writes.put(target, value);
            } else {
                skipped++;
            }
        }
        return new CompanionRestorePlan(writes, Collections.emptyMap(), skipped, false);
    }

    private static int optInt(Object value) {
        if (value instanceof Number) return ((Number) value).intValue();
        if (value instanceof String) {
            try {
                return (int) Double.parseDouble((String) value);
            } catch (NumberFormatException e) {
                return StoryCacheRetention.DEFAULT_HOURS;
            }
        }
        return StoryCacheRetention.DEFAULT_HOURS;
    }
}
