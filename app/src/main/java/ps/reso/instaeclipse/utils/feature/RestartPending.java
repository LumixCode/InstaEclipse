package ps.reso.instaeclipse.utils.feature;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;

public final class RestartPending {

    private static final Map<String, String> SEEN = new ConcurrentHashMap<>();
    private static volatile Map<String, Boolean> rules;

    private RestartPending() {}

    public static void capture(String key, Object value) {
        if (key != null) SEEN.put(key, String.valueOf(value));
    }

    public static String seen(String key) {
        return key == null ? null : SEEN.get(key);
    }

    public static Map<String, Boolean> rules() {
        Map<String, Boolean> out = rules;
        if (out != null) return out;
        Map<String, Boolean> built = new LinkedHashMap<>();
        for (FeatureCatalog.Category c : FeatureCatalog.CATEGORIES) {
            for (FeatureCatalog.Item item : c.items()) {
                if (item.restartOn != FeatureCatalog.Restart.NONE) {
                    built.put(item.key, item.restartOn == FeatureCatalog.Restart.ON_ONLY);
                }
            }
        }
        built.put("customFontPath", false);
        built.put("customEmojiPath", false);
        out = Collections.unmodifiableMap(built);
        rules = out;
        return out;
    }

    public static Set<String> pending() {
        if (SEEN.isEmpty()) return new TreeSet<>();
        return pending(rules());
    }

    public static boolean captured(String key) {
        return key != null && SEEN.containsKey(key);
    }

    public static Set<String> pending(Map<String, Boolean> onOnly) {
        return pending(onOnly, RestartPending::flag);
    }

    public static Set<String> pending(Map<String, Boolean> onOnly, Function<String, Object> current) {
        Set<String> out = new TreeSet<>();
        for (Map.Entry<String, Boolean> rule : onOnly.entrySet()) {
            String was = SEEN.get(rule.getKey());
            if (was == null) continue;
            String now = String.valueOf(current.apply(rule.getKey()));
            if (was.equals(now)) continue;
            if (Boolean.TRUE.equals(rule.getValue()) && (!on(now) || on(was))) continue;
            out.add(rule.getKey());
        }
        return out;
    }

    private static boolean on(String value) {
        if ("true".equals(value)) return true;
        try {
            return Integer.parseInt(value) > 0;
        } catch (NumberFormatException e) {
            return false;
        }
    }

    private static Object flag(String key) {
        try {
            return FeatureFlags.class.getField(key).get(null);
        } catch (ReflectiveOperationException e) {
            return null;
        }
    }

    public static void resetForTest() {
        SEEN.clear();
    }
}
