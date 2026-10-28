package ps.reso.instaeclipse.mods.media;

import java.lang.reflect.Method;
import java.util.function.Function;

import ps.reso.instaeclipse.utils.core.SafeReflect;

final class StoryCapturePolicy {
    private static final long SECONDS_LIMIT = 100000000000L;

    private StoryCapturePolicy() {}

    static String id(Object raw) {
        try {
            return raw instanceof String s && !s.isEmpty() ? s.split("_")[0] : null;
        } catch (RuntimeException e) {
            return null;
        }
    }

    static long expiryMs(Object raw) {
        if (!(raw instanceof Long at) || at <= 0) return 0;
        return at < SECONDS_LIMIT ? at * 1000L : at;
    }

    static String author(Object item, String userType, String mediaType,
                         Function<Object, String> userName, Function<Object, String> mediaUserName) {
        if (item == null) return null;
        for (Method m : SafeReflect.methods(item.getClass())) {
            if (m.getParameterCount() != 0) continue;
            Class<?> ret = m.getReturnType();
            if (ret.isPrimitive() || ret == String.class || ret == void.class) continue;
            try {
                m.setAccessible(true);
                Object candidate = m.invoke(item);
                if (candidate == null) continue;
                String type = candidate.getClass().getName();
                String name = type.equals(userType) ? userName.apply(candidate)
                        : type.equals(mediaType) ? mediaUserName.apply(candidate) : null;
                if (name != null) return name;
            } catch (Throwable ignored) {}
        }
        return null;
    }
}
