package ps.reso.instaeclipse.utils.ghost;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Set;
import java.util.function.Function;

import ps.reso.instaeclipse.utils.core.SafeReflect;

public final class IdKeys {

    private static final String[] HOST = {"X.", "com.instagram."};
    static final int MAX_DEPTH = 4;
    static final int MAX_KEYS = 60;

    private IdKeys() {}

    public static List<Long> collect(Object root, Function<String, Long> fromString) {
        return collect(root, fromString, HOST);
    }

    static List<Long> collect(Object root, Function<String, Long> fromString, String... packages) {
        List<Long> out = new ArrayList<>();
        walk(root, out, 0, Collections.newSetFromMap(new IdentityHashMap<>()), fromString, packages);
        return out;
    }

    private static void walk(Object obj, List<Long> out, int depth, Set<Object> seen,
                             Function<String, Long> fromString, String[] packages) {
        if (obj == null || depth > MAX_DEPTH || out.size() > MAX_KEYS || !seen.add(obj)) return;
        if (obj instanceof Object[] array) {
            for (Object e : array) walk(e, out, depth + 1, seen, fromString, packages);
            return;
        }
        if (!hosted(obj.getClass().getName(), packages)) return;
        for (Class<?> c = obj.getClass(); c != null && c != Object.class; c = c.getSuperclass()) {
            for (Field f : SafeReflect.fields(c)) {
                if (Modifier.isStatic(f.getModifiers())) continue;
                f.setAccessible(true);
                Class<?> t = f.getType();
                try {
                    if (t == long.class) {
                        long v = f.getLong(obj);
                        if (v != 0 && v != Long.MAX_VALUE && !out.contains(v)) out.add(v);
                    } else if (t == String.class) {
                        Long v = fromString.apply((String) f.get(obj));
                        if (v != null && !out.contains(v)) out.add(v);
                    } else if (!t.isPrimitive()) {
                        walk(f.get(obj), out, depth + 1, seen, fromString, packages);
                    }
                } catch (Throwable ignored) {}
            }
        }
    }

    private static boolean hosted(String name, String[] packages) {
        for (String p : packages) if (name.startsWith(p)) return true;
        return false;
    }
}
