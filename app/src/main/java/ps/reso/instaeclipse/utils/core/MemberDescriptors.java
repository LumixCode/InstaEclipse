package ps.reso.instaeclipse.utils.core;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.Map;

public final class MemberDescriptors {

    private static final char SEP = '\u0000';
    private static final Map<String, Class<?>> PRIMITIVES = new HashMap<>();

    static {
        for (Class<?> c : new Class<?>[]{boolean.class, byte.class, char.class, short.class, int.class,
                long.class, float.class, double.class, void.class}) {
            PRIMITIVES.put(c.getName(), c);
        }
    }

    private MemberDescriptors() {}

    public static String encode(Method m) {
        return m.getDeclaringClass().getName() + SEP + m.getName() + SEP + descriptor(m);
    }

    public static Method decodeMethod(String encoded, ClassLoader loader) {
        try {
            int i1 = encoded.indexOf(SEP);
            int i2 = encoded.indexOf(SEP, i1 + 1);
            if (i1 < 0 || i2 < 0) return null;
            String className  = encoded.substring(0, i1);
            String methodName = encoded.substring(i1 + 1, i2);
            String desc       = encoded.substring(i2 + 1);
            Class<?> clazz = Class.forName(className, false, loader);
            for (Method m : SafeReflect.methods(clazz)) {
                if (m.getName().equals(methodName) && descriptor(m).equals(desc)) {
                    m.setAccessible(true);
                    return m;
                }
            }
        } catch (Throwable ignored) {}
        return null;
    }

    public static String encode(Field f) {
        return f.getDeclaringClass().getName() + '#' + f.getName();
    }

    public static Field decodeField(String encoded, ClassLoader loader) {
        try {
            int split = encoded.lastIndexOf('#');
            if (split <= 0 || split == encoded.length() - 1) return null;
            Field field = Class.forName(encoded.substring(0, split), false, loader)
                    .getDeclaredField(encoded.substring(split + 1));
            field.setAccessible(true);
            return field;
        } catch (Throwable ignored) {
            return null;
        }
    }

    public static String encode(Constructor<?> c) {
        StringBuilder sb = new StringBuilder(c.getDeclaringClass().getName()).append('(');
        Class<?>[] params = c.getParameterTypes();
        for (int i = 0; i < params.length; i++) {
            if (i > 0) sb.append(',');
            sb.append(params[i].getName());
        }
        return sb.append(')').toString();
    }

    public static Constructor<?> decodeConstructor(String encoded, ClassLoader loader) {
        try {
            int open = encoded.indexOf('(');
            if (open <= 0 || !encoded.endsWith(")")) return null;
            String inner = encoded.substring(open + 1, encoded.length() - 1);
            String[] names = inner.isEmpty() ? new String[0] : inner.split(",", -1);
            Class<?>[] params = new Class<?>[names.length];
            for (int i = 0; i < names.length; i++) {
                params[i] = type(names[i], loader);
                if (params[i] == null || params[i] == void.class) return null;
            }
            Constructor<?> constructor = Class.forName(encoded.substring(0, open), false, loader)
                    .getDeclaredConstructor(params);
            constructor.setAccessible(true);
            return constructor;
        } catch (Throwable ignored) {
            return null;
        }
    }

    public static String descriptor(Method m) {
        StringBuilder sb = new StringBuilder("(");
        for (Class<?> p : m.getParameterTypes()) typeDesc(sb, p);
        sb.append(")");
        typeDesc(sb, m.getReturnType());
        return sb.toString();
    }

    private static Class<?> type(String name, ClassLoader loader) throws ClassNotFoundException {
        if (name.isEmpty()) return null;
        Class<?> primitive = PRIMITIVES.get(name);
        return primitive != null ? primitive : Class.forName(name, false, loader);
    }

    private static void typeDesc(StringBuilder sb, Class<?> t) {
        while (t.isArray()) { sb.append('['); t = t.getComponentType(); }
        if (t.isPrimitive()) {
            if      (t == void.class)    sb.append('V');
            else if (t == boolean.class) sb.append('Z');
            else if (t == byte.class)    sb.append('B');
            else if (t == char.class)    sb.append('C');
            else if (t == short.class)   sb.append('S');
            else if (t == int.class)     sb.append('I');
            else if (t == long.class)    sb.append('J');
            else if (t == float.class)   sb.append('F');
            else if (t == double.class)  sb.append('D');
        } else {
            sb.append('L').append(t.getName().replace('.', '/')).append(';');
        }
    }
}
