package ps.reso.instaeclipse.utils.feature;

import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;

public final class RestartPending {

    private static final Map<String, String> SEEN = new ConcurrentHashMap<>();

    private RestartPending() {}

    public static void capture(String key, Object value) {
        if (key != null) SEEN.put(key, String.valueOf(value));
    }

    public static String seen(String key) {
        return key == null ? null : SEEN.get(key);
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
            if (Boolean.TRUE.equals(rule.getValue()) && !("true".equals(now) && "false".equals(was))) continue;
            out.add(rule.getKey());
        }
        return out;
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
