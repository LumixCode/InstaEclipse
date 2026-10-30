package ps.reso.instaeclipse.utils.feature;

import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;

public final class RestartPending {

    private static final Map<String, String> SEEN = new ConcurrentHashMap<>();
    private static volatile Set<String> rules;

    private RestartPending() {}

    public static void capture(String key, Object value) {
        if (key != null) SEEN.put(key, String.valueOf(value));
    }

    public static String seen(String key) {
        return key == null ? null : SEEN.get(key);
    }

    public static Set<String> rules() {
        Set<String> out = rules;
        if (out != null) return out;
        Set<String> built = new LinkedHashSet<>();
        for (FeatureCatalog.Category c : FeatureCatalog.CATEGORIES) {
            for (FeatureCatalog.Item item : c.items()) {
                if (item.restartOn != FeatureCatalog.Restart.NONE) built.add(item.key);
            }
        }
        built.add("customFontPath");
        built.add("customEmojiPath");
        out = Collections.unmodifiableSet(built);
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

    public static Set<String> pending(Set<String> restarts) {
        return pending(restarts, RestartPending::flag);
    }

    public static Set<String> pending(Set<String> restarts, Function<String, Object> current) {
        Set<String> out = new TreeSet<>();
        for (String key : restarts) {
            String was = SEEN.get(key);
            if (was == null) continue;
            String now = String.valueOf(current.apply(key));
            String[] parents = FeatureCatalog.restartParents(key);
            if (parents.length > 0 && capturedAll(parents)) {
                was = String.valueOf(on(was) && anyOn(parents, SEEN::get));
                now = String.valueOf(on(now) && anyOn(parents, parent -> String.valueOf(current.apply(parent))));
            }
            if (was.equals(now)) continue;
            out.add(key);
        }
        return out;
    }

    public static Set<String> affected(Collection<String> applied) {
        Set<String> out = new LinkedHashSet<>(applied);
        for (String key : rules()) {
            for (String parent : FeatureCatalog.restartParents(key)) {
                if (applied.contains(parent)) out.add(key);
            }
        }
        return out;
    }

    private static boolean capturedAll(String[] keys) {
        for (String key : keys) if (!SEEN.containsKey(key)) return false;
        return true;
    }

    private static boolean anyOn(String[] keys, Function<String, String> values) {
        for (String key : keys) if (on(values.apply(key))) return true;
        return false;
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
