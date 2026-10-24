package ps.reso.instaeclipse.utils.core;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import ps.reso.instaeclipse.mods.ads.ShareDomain;
import ps.reso.instaeclipse.mods.misc.DeviceSpoofHook;
import ps.reso.instaeclipse.mods.reels.DailyReelLimit;
import ps.reso.instaeclipse.mods.reels.NightReelSchedule;
import ps.reso.instaeclipse.mods.ui.StartupTabHook;
import ps.reso.instaeclipse.mods.ui.StoryTrayHook;
import ps.reso.instaeclipse.utils.feature.FeatureFlags;
import ps.reso.instaeclipse.utils.media.StoryCacheRetention;
import ps.reso.instaeclipse.utils.profile.SpoofAvatar;
import ps.reso.instaeclipse.utils.text.TimestampFormat;

public final class SettingsSchema {
    public static final int MAX_STRING_CHARS = 16 * 1024;
    public static final Set<String> PRIVATE = set("lockDirectPasscode", "lockDirectSalt", "lockedDirectThreads");
    public static final Set<String> LOCKS = set("lockWholeApp", "lockDirectMessages", "lockSpecificMessages",
            "lockDirectAlways", "lockSavedCollections", "lockedCollections", "lockUseFingerprint");
    public static final Set<String> PATHS = set("downloaderCustomPath", "downloaderCustomUri", "customFontPath",
            "customEmojiPath");
    public static final Set<String> LEGACY_IGNORED = set("isGhostModeEnabled", "isMiscEnabled", "isDistractionFree");
    private static final Set<String> NOT_IN_BACKUP = set("enablePhotoZoom", "customThemeEnabled", "themePresetId",
            "themeLightPresetId", "themePaletteJson", "navTabOrder", "autoClearCache", "autoClearCacheSizeMb",
            "customFontEnabled", "customEmojiEnabled");
    private static final Map<String, Field> FIELDS = fields();
    private static final Map<String, Class<?>> TYPES = types(FIELDS);

    private SettingsSchema() {}

    public static Map<String, Class<?>> types() {
        return TYPES;
    }

    public static boolean isSetting(String key) {
        return key != null && TYPES.containsKey(key);
    }

    public static Class<?> type(String key) {
        return key == null ? null : TYPES.get(key);
    }

    public static Object defaultValue(String key) {
        return key == null ? null : FeatureFlags.DEFAULTS.get(key);
    }

    public static Object read(String key) {
        Field field = key == null ? null : FIELDS.get(key);
        if (field == null) return null;
        try {
            Object value = field.get(null);
            return field.getType() == double.class ? String.valueOf(value) : value;
        } catch (IllegalAccessException e) {
            throw new IllegalStateException(e);
        }
    }

    public static void write(String key, Object value) {
        Field field = key == null ? null : FIELDS.get(key);
        if (field == null || !TYPES.get(key).isInstance(value)) throw new IllegalArgumentException(key);
        try {
            field.set(null, field.getType() == double.class ? Double.parseDouble((String) value) : value);
        } catch (IllegalAccessException e) {
            throw new IllegalStateException(e);
        }
    }

    public static Map<String, Object> migrate(Map<String, ?> stored) {
        Map<String, Object> edits = new LinkedHashMap<>();
        if (stored.containsKey("hideCreateGroupButton")) {
            Object old = stored.get("hideCreateGroupButton");
            if (!stored.containsKey("hideShareCreateGroup") && old instanceof Boolean) edits.put("hideShareCreateGroup", old);
            edits.put("hideCreateGroupButton", null);
        }
        if (stored.containsKey("blockLocationPrompts")) {
            if (Boolean.TRUE.equals(stored.get("blockLocationPrompts"))) edits.put("hideOnboardingPrompts", true);
            edits.put("blockLocationPrompts", null);
        }
        return edits;
    }

    public static List<String> backupKeys(boolean withLocks) {
        List<String> keys = new ArrayList<>();
        for (String key : TYPES.keySet()) {
            if (PRIVATE.contains(key) || "isExtremeMode".equals(key)) continue;
            if (withLocks ? "lockedCollections".equals(key)
                    : LOCKS.contains(key) || PATHS.contains(key) || NOT_IN_BACKUP.contains(key)) continue;
            keys.add(key);
        }
        keys.add("isExtremeMode");
        return keys;
    }

    public static Object restorable(String key, Object raw) {
        Class<?> type = type(key);
        if (type == null || raw == null) return null;
        if (type == Boolean.class) return toBoolean(raw);
        if (type == Integer.class) return restorableInt(key, raw);
        return restorableString(key, raw);
    }

    private static Object restorableInt(String key, Object raw) {
        if ("forceReelQuality".equals(key) && raw instanceof Boolean) return (Boolean) raw ? 1080 : 0;
        Integer value = toInteger(raw);
        if (value == null) return "storyCacheHours".equals(key) ? StoryCacheRetention.DEFAULT_HOURS : null;
        int n = value;
        switch (key) {
            case "storyTrayScale":
                return Math.max(30, Math.min(100, n));
            case "feedCornerRadius":
                return Math.max(0, Math.min(48, n));
            case "downloaderQualityMaxShortSide":
                return Math.max(0, n);
            case "spoofFollowers":
            case "spoofFollowing":
            case "spoofPosts":
                return Math.max(-1, n);
            case "dailyReelLimit":
                return DailyReelLimit.validLimit(n);
            case "nightReelBlockStartMinutes":
            case "nightReelBlockEndMinutes":
                int minutes = NightReelSchedule.validMinutes(n, -1);
                return minutes < 0 ? null : minutes;
            case "storyCacheHours":
                return StoryCacheRetention.normalize(n);
            default:
                return n;
        }
    }

    private static Object restorableString(String key, Object raw) {
        if ("spoofLat".equals(key) || "spoofLng".equals(key)) {
            double value = toDouble(raw);
            if (Double.isNaN(value)) return null;
            return raw instanceof String ? raw : String.valueOf(value);
        }
        String value = String.valueOf(raw);
        switch (key) {
            case "startupTab":
                return Arrays.asList(StartupTabHook.TABS).contains(value) ? value : null;
            case "customShareDomain":
                return ShareDomain.normalize(value);
            case "exactTimestampFormat":
                return TimestampFormat.isValid(value) ? value : null;
            case "spoofDevice":
                return Arrays.asList(DeviceSpoofHook.DEVICES).contains(value) ? value : null;
            case "storyRingColor":
                return value.isEmpty() || StoryTrayHook.parseColors(value) != null ? value : null;
            case "spoofAvatar":
                return SpoofAvatar.isValid(value) ? value : null;
            default:
                return value;
        }
    }

    private static Boolean toBoolean(Object raw) {
        if (raw instanceof Boolean) return (Boolean) raw;
        if (raw instanceof String) {
            String text = ((String) raw).trim();
            if ("true".equalsIgnoreCase(text)) return true;
            if ("false".equalsIgnoreCase(text)) return false;
        }
        return null;
    }

    private static Integer toInteger(Object raw) {
        if (raw instanceof Integer) return (Integer) raw;
        if (raw instanceof Number) {
            double value = ((Number) raw).doubleValue();
            return value >= Integer.MIN_VALUE && value <= Integer.MAX_VALUE ? (int) ((Number) raw).longValue() : null;
        }
        if (raw instanceof String) {
            try {
                return Integer.parseInt(((String) raw).trim());
            } catch (NumberFormatException e) {
                return null;
            }
        }
        return null;
    }

    private static double toDouble(Object raw) {
        if (raw instanceof Number) return ((Number) raw).doubleValue();
        if (raw instanceof String) {
            try {
                return Double.parseDouble((String) raw);
            } catch (NumberFormatException e) {
                return Double.NaN;
            }
        }
        return Double.NaN;
    }

    private static Map<String, Field> fields() {
        Map<String, Field> out = new LinkedHashMap<>();
        for (Field field : SafeReflect.fields(FeatureFlags.class)) {
            int m = field.getModifiers();
            if (!Modifier.isStatic(m) || Modifier.isFinal(m) || field.isSynthetic()) continue;
            Class<?> type = field.getType();
            if (type == boolean.class || type == int.class || type == String.class || type == double.class) {
                out.put(field.getName(), field);
            }
        }
        return Collections.unmodifiableMap(out);
    }

    private static Map<String, Class<?>> types(Map<String, Field> fields) {
        Map<String, Class<?>> out = new LinkedHashMap<>();
        for (Map.Entry<String, Field> e : fields.entrySet()) {
            Class<?> type = e.getValue().getType();
            out.put(e.getKey(), type == boolean.class ? Boolean.class : type == int.class ? Integer.class : String.class);
        }
        return Collections.unmodifiableMap(out);
    }

    private static Set<String> set(String... keys) {
        return Collections.unmodifiableSet(new HashSet<>(Arrays.asList(keys)));
    }
}
