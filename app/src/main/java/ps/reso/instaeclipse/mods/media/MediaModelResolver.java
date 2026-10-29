package ps.reso.instaeclipse.mods.media;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Collection;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.Set;

final class MediaModelResolver {

    private MediaModelResolver() {}

    static Object findObjectOfType(Object root, Class<?> target, int maxDepth) {
        Set<Object> visited = java.util.Collections.newSetFromMap(new IdentityHashMap<>());
        return findObjectOfType(root, target, maxDepth, visited);
    }

    private static Object findObjectOfType(Object obj, Class<?> target, int depth,
                                           Set<Object> visited) {
        if (obj == null || target == null || depth < 0 || !visited.add(obj)) return null;
        if (target.isInstance(obj)) return obj;

        if (obj instanceof Map<?, ?> map) {
            if (depth == 0) return null;
            for (Object value : map.values()) {
                Object nested = findObjectOfType(value, target, depth - 1, visited);
                if (nested != null) return nested;
            }
            return null;
        }
        if (obj instanceof Iterable<?> iterable) {
            if (depth == 0) return null;
            for (Object value : iterable) {
                Object nested = findObjectOfType(value, target, depth - 1, visited);
                if (nested != null) return nested;
            }
            return null;
        }
        if (obj instanceof Object[] array) {
            if (depth == 0) return null;
            for (Object value : array) {
                Object nested = findObjectOfType(value, target, depth - 1, visited);
                if (nested != null) return nested;
            }
            return null;
        }

        Class<?> cls = obj.getClass();
        while (cls != null && cls != Object.class) {
            for (Field field : ps.reso.instaeclipse.utils.core.SafeReflect.fields(cls)) {
                try {
                    if (java.lang.reflect.Modifier.isStatic(field.getModifiers())) continue;
                    field.setAccessible(true);
                    Object value = field.get(obj);
                    if (value == null) continue;
                    if (target.isInstance(value)) return value;
                    boolean traversable = isInstagramModelClass(value.getClass().getName())
                            || value instanceof Iterable<?> || value instanceof Map<?, ?>
                            || value instanceof Object[];
                    if (depth == 0 || !traversable) continue;
                    Object nested = findObjectOfType(value, target, depth - 1, visited);
                    if (nested != null) return nested;
                } catch (Throwable ignored) {}
            }
            cls = cls.getSuperclass();
        }

        if (depth > 0 && isInstagramModelClass(obj.getClass().getName())) {
            cls = obj.getClass();
            while (cls != null && cls != Object.class) {
                for (Method method : ps.reso.instaeclipse.utils.core.SafeReflect.methods(cls)) {
                    if (method.getParameterCount() != 0
                            || java.lang.reflect.Modifier.isStatic(method.getModifiers())) continue;
                    Class<?> returnType = method.getReturnType();
                    if (returnType.isPrimitive() || returnType == String.class
                            || returnType == Class.class) continue;
                    if (!target.isAssignableFrom(returnType)
                            && !isInstagramModelClass(returnType.getName())
                            && !Collection.class.isAssignableFrom(returnType)
                            && !Map.class.isAssignableFrom(returnType)) continue;
                    try {
                        method.setAccessible(true);
                        Object value = method.invoke(obj);
                        Object nested = findObjectOfType(value, target, depth - 1, visited);
                        if (nested != null) return nested;
                    } catch (Throwable ignored) {}
                }
                cls = cls.getSuperclass();
            }
        }
        return null;
    }

    private static boolean isInstagramModelClass(String name) {
        return name.startsWith("X.")
                || name.startsWith("com.instagram.")
                || name.startsWith("com.facebook.")
                || name.startsWith("ps.reso.instaeclipse.mods.media.");
    }
}
