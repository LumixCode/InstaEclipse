package ps.reso.instaeclipse.mods.misc;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Function;

import ps.reso.instaeclipse.utils.core.SafeReflect;

final class FollowConfirmPolicy {

    static final String FOLLOW_PATH = "create";

    private FollowConfirmPolicy() {}

    static boolean isFollow(Object[] args) {
        for (Object a : args) {
            if (!(a instanceof Enum<?>)) continue;
            for (Field f : SafeReflect.fields(a.getClass())) {
                if (Modifier.isStatic(f.getModifiers()) || f.getType() != String.class) continue;
                try {
                    f.setAccessible(true);
                    if (FOLLOW_PATH.equals(f.get(a))) return true;
                } catch (Throwable ignored) { }
            }
        }
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
        for (Method m : candidates) {
            if (!Modifier.isStatic(m.getModifiers())) continue;
            Class<?>[] p = m.getParameterTypes();
            if (has(p, m.getDeclaringClass()) && hasNamed(p, userClass) && hasEnum(p)) return m;
        }
        return null;
    }

    static boolean holdsProfileTap(Object status) {
        if (!(status instanceof Enum<?> e)) return true;
        String name = e.name().toUpperCase(Locale.ROOT);
        return !(name.contains("FOLLOWING") && !name.contains("NOTFOLLOWING") || name.contains("REQUESTED"));
    }

    static boolean isNotFollowing(Object status) {
        return status instanceof Enum<?> e && e.name().toUpperCase(Locale.ROOT).contains("NOTFOLLOWING");
    }

    static boolean hasEnum(Class<?>[] types) {
        for (Class<?> t : types) if (t.isEnum()) return true;
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

        private final Call call;
        private final Resume resume;
        private final Function<Throwable, Object> failure;
        private final Object suspended;
        private final AtomicBoolean answered = new AtomicBoolean();

        HeldCall(Call call, Resume resume, Function<Throwable, Object> failure, Object suspended) {
            this.call = call;
            this.resume = resume;
            this.failure = failure;
            this.suspended = suspended;
        }

        void follow() throws Throwable {
            if (answered.compareAndSet(false, true)) call.run();
        }

        void cancel() throws Throwable {
            answered.set(true);
        }
    }
}
