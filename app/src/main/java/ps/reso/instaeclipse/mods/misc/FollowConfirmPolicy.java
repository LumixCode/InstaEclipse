package ps.reso.instaeclipse.mods.misc;

import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CancellationException;
import java.util.concurrent.atomic.AtomicBoolean;

import ps.reso.instaeclipse.utils.core.SafeReflect;

final class FollowConfirmPolicy {

    static final String FOLLOW_PATH = "create";
    static final String HELD = "follow held by InstaEclipse";

    private FollowConfirmPolicy() {}

    static boolean isFollow(Object[] args) {
        for (Object a : args) if (a instanceof Enum<?> && carriesFollowPath(a)) return true;
        return false;
    }

    static int continuationIndex(Class<?>[] p) {
        int found = -1;
        for (int i = 0; i < p.length; i++) {
            if (!p[i].isInterface() || p[i].getName().startsWith("kotlin.jvm.functions.")) continue;
            if (found >= 0) return -1;
            found = i;
        }
        return found;
    }

    static Method pickSender(List<Method> candidates, String userClass) {
        Method found = null;
        for (Method m : candidates) {
            if (!Modifier.isStatic(m.getModifiers()) || m.getReturnType() != void.class) continue;
            Class<?>[] p = m.getParameterTypes();
            if (!has(p, m.getDeclaringClass()) || !hasNamed(p, userClass) || !hasActionEnum(p)) continue;
            if (found != null) return null;
            found = m;
        }
        return found;
    }

    static boolean holdsProfileTap(Object status) {
        return isNotFollowing(status);
    }

    static boolean isNotFollowing(Object status) {
        return status instanceof Enum<?> e && e.name().toUpperCase(Locale.ROOT).contains("NOTFOLLOWING");
    }

    static boolean hasEnum(Class<?>[] types) {
        for (Class<?> t : types) if (t.isEnum()) return true;
        return false;
    }

    private static boolean hasActionEnum(Class<?>[] types) {
        for (Class<?> t : types) {
            if (!t.isEnum()) continue;
            Object[] constants = t.getEnumConstants();
            if (constants == null) continue;
            for (Object c : constants) if (carriesFollowPath(c)) return true;
        }
        return false;
    }

    private static boolean carriesFollowPath(Object constant) {
        for (Field f : SafeReflect.fields(constant.getClass())) {
            if (Modifier.isStatic(f.getModifiers()) || f.getType() != String.class) continue;
            try {
                f.setAccessible(true);
                if (FOLLOW_PATH.equals(f.get(constant))) return true;
            } catch (Throwable ignored) { }
        }
        return false;
    }

    private static boolean has(Class<?>[] types, Class<?> c) {
        for (Class<?> t : types) if (t == c) return true;
        return false;
    }

    private static boolean hasNamed(Class<?>[] types, String name) {
        for (Class<?> t : types) if (t.getName().equals(name)) return true;
        return false;
    }

    static final class HeldCall {

        interface Call {
            Object run() throws Throwable;
        }

        interface Resume {
            void with(Object result) throws Throwable;
        }

        interface Failure {
            Object of(Throwable cause) throws Throwable;
        }

        private final Call call;
        private final Resume resume;
        private final Failure failure;
        private final Object suspended;
        private final AtomicBoolean answered = new AtomicBoolean();

        HeldCall(Call call, Resume resume, Failure failure, Object suspended) {
            this.call = call;
            this.resume = resume;
            this.failure = failure;
            this.suspended = suspended;
        }

        void follow() throws Throwable {
            if (!answered.compareAndSet(false, true)) return;
            Object result;
            try {
                result = call.run();
            } catch (Throwable t) {
                resume.with(failure.of(cause(t)));
                return;
            }
            if (result != suspended) resume.with(result);
        }

        void cancel() throws Throwable {
            if (answered.compareAndSet(false, true)) resume.with(failure.of(new CancellationException(HELD)));
        }

        static Throwable cause(Throwable t) {
            while (t instanceof InvocationTargetException && t.getCause() != null) t = t.getCause();
            return t;
        }
    }
}
