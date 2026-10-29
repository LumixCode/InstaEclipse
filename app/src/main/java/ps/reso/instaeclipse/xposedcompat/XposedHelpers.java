package ps.reso.instaeclipse.xposedcompat;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.WeakHashMap;
import java.util.concurrent.ConcurrentHashMap;

public final class XposedHelpers {

    private static final Object MISSING = new Object();
    private static final Map<Key, Object> FIELDS = new ConcurrentHashMap<>();
    private static final Map<Key, Object> METHODS = new ConcurrentHashMap<>();
    private static final Map<Object, Map<String, Object>> EXTRA = new WeakHashMap<>();

    private XposedHelpers() {}

    public static final class ClassNotFoundError extends Error {
        public ClassNotFoundError(String message, Throwable cause) { super(message, cause); }
    }

    public static Class<?> findClass(String className, ClassLoader classLoader) {
        try {
            return Class.forName(className, false, classLoader != null ? classLoader : XposedHelpers.class.getClassLoader());
        } catch (ClassNotFoundException e) {
            throw new ClassNotFoundError(className, e);
        }
    }

    public static Class<?> findClassIfExists(String className, ClassLoader classLoader) {
        try {
            return findClass(className, classLoader);
        } catch (ClassNotFoundError e) {
            return null;
        }
    }

    public static XC_MethodHook.Unhook findAndHookMethod(Class<?> clazz, String methodName, Object... parameterTypesAndCallback) {
        XC_MethodHook callback = callbackOf(parameterTypesAndCallback);
        Class<?>[] types = parameterTypes(clazz.getClassLoader(), parameterTypesAndCallback);
        return XposedBridge.hookMethod(findMethodExact(clazz, methodName, types), callback);
    }

    public static XC_MethodHook.Unhook findAndHookMethod(String className, ClassLoader classLoader, String methodName, Object... parameterTypesAndCallback) {
        return findAndHookMethod(findClass(className, classLoader), methodName, parameterTypesAndCallback);
    }

    public static XC_MethodHook.Unhook findAndHookConstructor(Class<?> clazz, Object... parameterTypesAndCallback) {
        XC_MethodHook callback = callbackOf(parameterTypesAndCallback);
        Class<?>[] types = parameterTypes(clazz.getClassLoader(), parameterTypesAndCallback);
        try {
            Constructor<?> c = clazz.getDeclaredConstructor(types);
            c.setAccessible(true);
            return XposedBridge.hookMethod(c, callback);
        } catch (NoSuchMethodException e) {
            throw new NoSuchMethodError(clazz.getName() + "<init>" + java.util.Arrays.toString(types));
        }
    }

    public static XC_MethodHook.Unhook findAndHookConstructor(String className, ClassLoader classLoader, Object... parameterTypesAndCallback) {
        return findAndHookConstructor(findClass(className, classLoader), parameterTypesAndCallback);
    }

    private static XC_MethodHook callbackOf(Object[] args) {
        if (args.length == 0 || !(args[args.length - 1] instanceof XC_MethodHook cb)) {
            throw new IllegalArgumentException("no callback defined");
        }
        return cb;
    }

    private static Class<?>[] parameterTypes(ClassLoader cl, Object[] args) {
        Class<?>[] types = new Class<?>[args.length - 1];
        for (int i = 0; i < types.length; i++) {
            Object t = args[i];
            if (t instanceof Class<?> c) types[i] = c;
            else if (t instanceof String s) types[i] = primitiveOr(s, cl);
            else throw new IllegalArgumentException("parameter type must be a Class or class name: " + t);
        }
        return types;
    }

    private static Class<?> primitiveOr(String name, ClassLoader cl) {
        switch (name) {
            case "int": return int.class;
            case "long": return long.class;
            case "boolean": return boolean.class;
            case "float": return float.class;
            case "double": return double.class;
            case "byte": return byte.class;
            case "short": return short.class;
            case "char": return char.class;
            case "void": return void.class;
            default: return findClass(name, cl);
        }
    }

    public static Method findMethodExact(Class<?> clazz, String methodName, Class<?>... parameterTypes) {
        for (Class<?> c = clazz; c != null; c = c.getSuperclass()) {
            try {
                Method m = c.getDeclaredMethod(methodName, parameterTypes);
                m.setAccessible(true);
                return m;
            } catch (NoSuchMethodException ignored) {}
        }
        throw new NoSuchMethodError(clazz.getName() + "#" + methodName + java.util.Arrays.toString(parameterTypes) + "#exact");
    }

    public static Object callMethod(Object obj, String methodName, Object... args) {
        return invoke(findCompatibleMethod(obj.getClass(), methodName, args, false), obj, args);
    }

    public static Object callStaticMethod(Class<?> clazz, String methodName, Object... args) {
        return invoke(findCompatibleMethod(clazz, methodName, args, true), null, args);
    }

    private static Object invoke(Method m, Object obj, Object[] args) {
        try {
            return m.invoke(obj, args);
        } catch (IllegalAccessException e) {
            throw new IllegalAccessError(e.getMessage());
        } catch (InvocationTargetException e) {
            Throwable cause = e.getCause();
            if (cause instanceof RuntimeException re) throw re;
            if (cause instanceof Error err) throw err;
            throw new RuntimeException(cause);
        }
    }

    private static final class Key {
        final Class<?> owner;
        final String name;
        final boolean isStatic;
        final Class<?>[] args;
        private final int hash;

        Key(Class<?> owner, String name, boolean isStatic, Class<?>[] args) {
            this.owner = owner;
            this.name = name;
            this.isStatic = isStatic;
            this.args = args;
            this.hash = (System.identityHashCode(owner) * 31 + name.hashCode()) * 31
                    + Arrays.hashCode(args) + (isStatic ? 1 : 0);
        }

        @Override public boolean equals(Object o) {
            if (!(o instanceof Key k)) return false;
            return owner == k.owner && isStatic == k.isStatic && name.equals(k.name) && Arrays.equals(args, k.args);
        }

        @Override public int hashCode() {
            return hash;
        }
    }

    static Method findCompatibleMethod(Class<?> clazz, String name, Object[] args, boolean isStatic) {
        Class<?>[] argTypes = new Class<?>[args.length];
        for (int i = 0; i < args.length; i++) argTypes[i] = args[i] != null ? args[i].getClass() : null;
        Key key = new Key(clazz, name, isStatic, argTypes);
        Object cached = METHODS.get(key);
        if (cached == null) {
            Method found = searchCompatibleMethod(clazz, name, args, isStatic);
            Object stored = found != null ? found : MISSING;
            Object previous = METHODS.putIfAbsent(key, stored);
            cached = previous != null ? previous : stored;
        }
        if (cached == MISSING) throw new NoSuchMethodError(clazz.getName() + "#" + name + " (" + args.length + " args)");
        return (Method) cached;
    }

    private static Method searchCompatibleMethod(Class<?> clazz, String name, Object[] args, boolean isStatic) {
        Method best = null;
        for (Class<?> c = clazz; c != null; c = c.getSuperclass()) {
            for (Method m : ps.reso.instaeclipse.utils.core.SafeReflect.methods(c)) {
                if (!m.getName().equals(name) || Modifier.isStatic(m.getModifiers()) != isStatic) continue;
                if (!accepts(m.getParameterTypes(), args)) continue;
                if (best == null || moreSpecific(m.getParameterTypes(), best.getParameterTypes())) best = m;
            }
            if (best != null) break;
        }
        if (best != null) best.setAccessible(true);
        return best;
    }

    private static boolean accepts(Class<?>[] types, Object[] args) {
        if (types.length != args.length) return false;
        for (int i = 0; i < types.length; i++) {
            if (args[i] == null) {
                if (types[i].isPrimitive()) return false;
            } else if (!box(types[i]).isInstance(args[i])) {
                return false;
            }
        }
        return true;
    }

    private static boolean moreSpecific(Class<?>[] a, Class<?>[] b) {
        for (int i = 0; i < a.length; i++) if (!box(b[i]).isAssignableFrom(box(a[i]))) return false;
        return true;
    }

    private static Class<?> box(Class<?> c) {
        if (!c.isPrimitive()) return c;
        if (c == int.class) return Integer.class;
        if (c == long.class) return Long.class;
        if (c == boolean.class) return Boolean.class;
        if (c == float.class) return Float.class;
        if (c == double.class) return Double.class;
        if (c == byte.class) return Byte.class;
        if (c == short.class) return Short.class;
        if (c == char.class) return Character.class;
        return Void.class;
    }

    public static Field findField(Class<?> clazz, String fieldName) {
        Key key = new Key(clazz, fieldName, false, null);
        Object cached = FIELDS.get(key);
        if (cached == null) {
            Field found = searchField(clazz, fieldName);
            Object stored = found != null ? found : MISSING;
            Object previous = FIELDS.putIfAbsent(key, stored);
            cached = previous != null ? previous : stored;
        }
        if (cached == MISSING) throw new NoSuchFieldError(clazz.getName() + '#' + fieldName);
        return (Field) cached;
    }

    private static Field searchField(Class<?> clazz, String fieldName) {
        for (Class<?> c = clazz; c != null; c = c.getSuperclass()) {
            try {
                Field f = c.getDeclaredField(fieldName);
                f.setAccessible(true);
                return f;
            } catch (NoSuchFieldException ignored) {}
        }
        return null;
    }

    public static Object getObjectField(Object obj, String fieldName) {
        try {
            return findField(obj.getClass(), fieldName).get(obj);
        } catch (IllegalAccessException e) {
            throw new IllegalAccessError(e.getMessage());
        }
    }

    public static boolean getBooleanField(Object obj, String fieldName) {
        try {
            return findField(obj.getClass(), fieldName).getBoolean(obj);
        } catch (IllegalAccessException e) {
            throw new IllegalAccessError(e.getMessage());
        }
    }

    public static Object getStaticObjectField(Class<?> clazz, String fieldName) {
        try {
            return findField(clazz, fieldName).get(null);
        } catch (IllegalAccessException e) {
            throw new IllegalAccessError(e.getMessage());
        }
    }

    public static void setStaticObjectField(Class<?> clazz, String fieldName, Object value) {
        try {
            findField(clazz, fieldName).set(null, value);
        } catch (IllegalAccessException e) {
            throw new IllegalAccessError(e.getMessage());
        }
    }

    public static Object setAdditionalInstanceField(Object obj, String key, Object value) {
        synchronized (EXTRA) {
            Map<String, Object> m = EXTRA.get(obj);
            if (m == null) EXTRA.put(obj, m = new HashMap<>());
            return m.put(key, value);
        }
    }

    public static Object getAdditionalInstanceField(Object obj, String key) {
        synchronized (EXTRA) {
            Map<String, Object> m = EXTRA.get(obj);
            return m != null ? m.get(key) : null;
        }
    }
}
