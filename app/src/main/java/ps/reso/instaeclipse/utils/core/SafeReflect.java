package ps.reso.instaeclipse.utils.core;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;

public final class SafeReflect {
    private static final Field[] NO_FIELDS = new Field[0];
    private static final Method[] NO_METHODS = new Method[0];
    private static final Constructor<?>[] NO_CONSTRUCTORS = new Constructor<?>[0];

    private SafeReflect() {}

    public static Field[] fields(Class<?> c) {
        try { return c.getDeclaredFields(); } catch (Throwable t) { return NO_FIELDS; }
    }

    public static Method[] methods(Class<?> c) {
        try { return c.getDeclaredMethods(); } catch (Throwable t) { return NO_METHODS; }
    }

    public static Method[] publicMethods(Class<?> c) {
        try { return c.getMethods(); } catch (Throwable t) { return NO_METHODS; }
    }

    public static Constructor<?>[] constructors(Class<?> c) {
        try { return c.getDeclaredConstructors(); } catch (Throwable t) { return NO_CONSTRUCTORS; }
    }
}
