package ps.reso.instaeclipse.utils.users;

import java.lang.reflect.Method;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

public class UserUtils {

    public static Method userUsernameGetter;

    private static final Pattern USERNAME = Pattern.compile("^[a-z0-9._]{2,30}$");
    private static final Map<Class<?>, Method> scannedGetters = new ConcurrentHashMap<>();

    public static String callUsernameGetter(Object user) {
        if (user == null) return null;

        Method getter = userUsernameGetter;
        if (getter != null) {
            if (!getter.getDeclaringClass().isInstance(user)) return null;
            String name = call(getter, user);
            return isValidUsername(name) ? name : null;
        }

        Method known = scannedGetters.get(user.getClass());
        if (known != null) {
            String name = call(known, user);
            return looksLikeUsername(name) ? name : null;
        }
        for (Method m : ps.reso.instaeclipse.utils.core.SafeReflect.methods(user.getClass())) {
            if (m.getParameterCount() != 0 || !m.getReturnType().equals(String.class)) continue;
            try {
                m.setAccessible(true);
            } catch (Throwable ignored) {
                continue;
            }
            String name = call(m, user);
            if (looksLikeUsername(name)) {
                scannedGetters.put(user.getClass(), m);
                return name;
            }
        }
        return null;
    }

    public static boolean isValidUsername(String s) {
        if (s == null || s.isEmpty()) return false;
        return USERNAME.matcher(s).matches();
    }

    private static boolean looksLikeUsername(String s) {
        if (!isValidUsername(s)) return false;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c >= 'a' && c <= 'z') return true;
        }
        return false;
    }

    private static String call(Method getter, Object user) {
        try {
            return getter.invoke(user) instanceof String s ? s : null;
        } catch (Throwable ignored) {
            return null;
        }
    }
}
