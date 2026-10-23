package ps.reso.instaeclipse.utils.core;

import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

public final class LegacyPaths {

    public static final String CACHE = "cache";
    public static final String BRIDGE = "bridge";
    public static final String PHASES = "phases";
    public static final String WARMBRIDGE = "warmbridge";
    public static final String APPLY = "apply";
    public static final String SEED = "seed";
    public static final String IDLE = "idle";
    public static final String EXECGUARD = "execguard";
    public static final String HEADER = "header";
    public static final String ALL = "all";

    static final String PROPERTY = "debug.instaeclipse.legacy";
    static final List<String> TOKENS = Collections.unmodifiableList(Arrays.asList(
            CACHE, BRIDGE, PHASES, WARMBRIDGE, APPLY, SEED, IDLE, EXECGUARD, HEADER, ALL));

    interface PropertyReader {
        String get(String key);
    }

    static volatile PropertyReader reader = LegacyPaths::systemProperty;
    private static volatile Set<String> tokens;

    private LegacyPaths() {}

    public static boolean has(String token) {
        Set<String> set = tokens();
        return set.contains(ALL) || set.contains(token);
    }

    public static Set<String> tokens() {
        Set<String> set = tokens;
        if (set == null) {
            synchronized (LegacyPaths.class) {
                set = tokens;
                if (set == null) {
                    set = parse(reader.get(PROPERTY));
                    tokens = set;
                }
            }
        }
        return set;
    }

    public static void overrideForTest(String value) {
        tokens = parse(value);
    }

    static void resetForTest(PropertyReader propertyReader) {
        reader = propertyReader == null ? LegacyPaths::systemProperty : propertyReader;
        tokens = null;
    }

    static Set<String> parse(String value) {
        Set<String> out = new LinkedHashSet<>();
        if (value == null) return Collections.unmodifiableSet(out);
        for (String raw : value.split(",")) {
            String token = raw.trim().toLowerCase(Locale.ROOT);
            if (TOKENS.contains(token)) out.add(token);
        }
        return Collections.unmodifiableSet(out);
    }

    public static String summarySuffix() {
        Set<String> set = tokens();
        return set.isEmpty() ? "" : " · legacy: " + String.join(",", set);
    }

    private static String systemProperty(String key) {
        try {
            Object v = Class.forName("android.os.SystemProperties").getMethod("get", String.class).invoke(null, key);
            return v instanceof String ? (String) v : "";
        } catch (Throwable t) {
            return "";
        }
    }
}
