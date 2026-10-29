package ps.reso.instaeclipse.xposedcompat;

import java.util.Collections;
import java.util.Map;

public final class CompanionPrefs {

    private CompanionPrefs() {}

    public static Map<String, ?> all() {
        try {
            Map<String, ?> m = XposedBridge.backend().companionPrefs();
            return m != null ? m : Collections.emptyMap();
        } catch (Throwable t) {
            return Collections.emptyMap();
        }
    }

    public static String getString(String key, String def) {
        Object v = all().get(key);
        return v instanceof String s ? s : def;
    }
}
