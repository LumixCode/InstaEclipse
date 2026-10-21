package ps.reso.instaeclipse.mods.extras;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Predicate;

/** What "Hide onboarding prompts" swallows; kept Android-free for JVM tests. */
final class OnboardingPromptPolicy {
    /** App ids of the server-sent "Set up on new device" pages: contacts sync, location services. */
    static final String CONTACTS_SETUP = "com.bloks.www.bloks.ig.ndx.ci.entry.screen";
    static final String LOCATION_SETUP = "com.bloks.www.bloks.ig.ndx.ls.entry.screen";

    private static final ClassLoader BOOT = Object.class.getClassLoader();

    private OnboardingPromptPolicy() {}

    /**
     * The onboarding app id that a screen opener or screen-data object names in one of its own
     * String fields, or null. Obfuscation renames the field, never the server's app id. Only app
     * classes are read: framework objects (maps, boxed ints) never carry it, and their fields can
     * be hidden API.
     */
    static String skippedScreen(Object screen) {
        if (screen == null) return null;
        for (Class<?> c = screen.getClass(); c != null && c.getClassLoader() != BOOT; c = c.getSuperclass()) {
            for (Field field : c.getDeclaredFields()) {
                if (field.getType() != String.class || Modifier.isStatic(field.getModifiers())) continue;
                try {
                    field.setAccessible(true);
                    Object value = field.get(screen);
                    if (CONTACTS_SETUP.equals(value) || LOCATION_SETUP.equals(value)) return (String) value;
                } catch (ReflectiveOperationException | RuntimeException ignored) {}
            }
        }
        return null;
    }

    static boolean isLocationPermission(String permission) {
        return "android.permission.ACCESS_FINE_LOCATION".equals(permission)
                || "android.permission.ACCESS_COARSE_LOCATION".equals(permission)
                || "android.permission.ACCESS_BACKGROUND_LOCATION".equals(permission);
    }

    /**
     * The permissions still worth asking Android for. Already-granted location permissions stay
     * because Android answers them without a dialog; returns the same array when nothing changes.
     */
    static String[] withoutUngrantedLocation(String[] requested, Predicate<String> granted) {
        if (requested == null) return null;
        List<String> kept = new ArrayList<>(requested.length);
        for (String permission : requested) {
            if (!isLocationPermission(permission) || granted.test(permission)) kept.add(permission);
        }
        return kept.size() == requested.length ? requested : kept.toArray(new String[0]);
    }

    /** Instagram's permission-result callback: an interface method taking the GRANTED/DENIED enum. */
    static Method resultMethod(Class<?> type) {
        if (type == null || !type.isInterface()) return null;
        for (Method method : type.getMethods()) {
            Class<?>[] params = method.getParameterTypes();
            if (params.length == 1 && deniedConstant(params[0]) != null) return method;
        }
        return null;
    }

    /** By name(): the enum's fields are obfuscated, the names it was built with are not. */
    static Object deniedConstant(Class<?> type) {
        if (type == null || !type.isEnum()) return null;
        for (Object constant : type.getEnumConstants()) {
            if ("DENIED".equals(((Enum<?>) constant).name())) return constant;
        }
        return null;
    }
}
