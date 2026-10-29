package ps.reso.instaeclipse.mods.extras;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Predicate;

final class OnboardingPromptPolicy {
    static final String CONTACTS_SETUP = "com.bloks.www.bloks.ig.ndx.ci.entry.screen";
    static final String LOCATION_SETUP = "com.bloks.www.bloks.ig.ndx.ls.entry.screen";

    private static final ClassLoader BOOT = Object.class.getClassLoader();
    private static final Map<Class<?>, List<Field>> STRING_FIELDS = new ConcurrentHashMap<>();

    private OnboardingPromptPolicy() {}

    static String skippedScreen(Object screen) {
        if (screen == null) return null;
        for (Field field : stringFields(screen.getClass())) {
            try {
                Object value = field.get(screen);
                if (CONTACTS_SETUP.equals(value) || LOCATION_SETUP.equals(value)) return (String) value;
            } catch (ReflectiveOperationException | RuntimeException ignored) {}
        }
        return null;
    }

    static List<Field> stringFields(Class<?> type) {
        List<Field> cached = STRING_FIELDS.get(type);
        if (cached != null) return cached;
        List<Field> fields = new ArrayList<>();
        for (Class<?> c = type; c != null && c.getClassLoader() != BOOT; c = c.getSuperclass()) {
            for (Field field : c.getDeclaredFields()) {
                if (field.getType() != String.class || Modifier.isStatic(field.getModifiers())) continue;
                try {
                    field.setAccessible(true);
                    fields.add(field);
                } catch (RuntimeException ignored) {}
            }
        }
        List<Field> raced = STRING_FIELDS.putIfAbsent(type, fields);
        return raced != null ? raced : fields;
    }

    static boolean isLocationPermission(String permission) {
        return "android.permission.ACCESS_FINE_LOCATION".equals(permission)
                || "android.permission.ACCESS_COARSE_LOCATION".equals(permission)
                || "android.permission.ACCESS_BACKGROUND_LOCATION".equals(permission);
    }

    static String[] withoutUngrantedLocation(String[] requested, Predicate<String> granted) {
        if (requested == null) return null;
        List<String> kept = new ArrayList<>(requested.length);
        for (String permission : requested) {
            if (!isLocationPermission(permission) || granted.test(permission)) kept.add(permission);
        }
        return kept.size() == requested.length ? requested : kept.toArray(new String[0]);
    }

    static Method resultMethod(Class<?> type) {
        if (type == null || !type.isInterface()) return null;
        for (Method method : type.getMethods()) {
            Class<?>[] params = method.getParameterTypes();
            if (params.length == 1 && deniedConstant(params[0]) != null) return method;
        }
        return null;
    }

    static Object deniedConstant(Class<?> type) {
        if (type == null || !type.isEnum()) return null;
        for (Object constant : type.getEnumConstants()) {
            if ("DENIED".equals(((Enum<?>) constant).name())) return constant;
        }
        return null;
    }
}
