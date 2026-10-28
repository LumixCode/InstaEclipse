package ps.reso.instaeclipse.utils.core;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

public final class ExtremeRules {

    public static final Set<String> KEEP_ON = set("disableStories", "disableFeed", "disableReels", "disableExplore",
            "disableComments", "dailyReelLimitEnabled", "nightReelBlockEnabled");
    public static final Set<String> EXCEPTIONS = set("disableReelsExceptDM", "dailyReelLimitAllowDM",
            "nightReelBlockAllowDM");
    public static final Set<String> FROZEN = set("isExtremeMode", "dailyReelLimit", "nightReelBlockStartMinutes",
            "nightReelBlockEndMinutes");
    private static final Map<String, String> EXCEPTION_OF = exceptions();

    private ExtremeRules() {}

    public static boolean allows(String key, Object newValue) {
        if (key == null) return true;
        if (FROZEN.contains(key)) return false;
        if (KEEP_ON.contains(key)) return Boolean.TRUE.equals(newValue);
        if (EXCEPTIONS.contains(key)) return Boolean.FALSE.equals(newValue);
        return true;
    }

    public static boolean canToggle(String key, boolean current) {
        return allows(key, !current);
    }

    public static boolean covers(String key) {
        return KEEP_ON.contains(key) || EXCEPTIONS.contains(key) || FROZEN.contains(key);
    }

    public static String exceptionOf(String key) {
        return key == null ? null : EXCEPTION_OF.get(key);
    }

    private static Map<String, String> exceptions() {
        Map<String, String> out = new LinkedHashMap<>();
        out.put("disableReels", "disableReelsExceptDM");
        out.put("dailyReelLimitEnabled", "dailyReelLimitAllowDM");
        out.put("nightReelBlockEnabled", "nightReelBlockAllowDM");
        return Collections.unmodifiableMap(out);
    }

    private static Set<String> set(String... keys) {
        return Collections.unmodifiableSet(new HashSet<>(Arrays.asList(keys)));
    }
}
