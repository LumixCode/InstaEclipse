package ps.reso.instaeclipse.utils.ghost;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/** Bounded lookup on the bound row, never across adapter collections or adjacent messages. */
public final class UnsentIdentity {
    public static final String SESSION = "com.instagram.common.session.UserSession";
    public static final String THREAD = "com.instagram.model.direct.DirectThreadKey";
    public static final String IDENTIFIER = "com.instagram.model.direct.messageid.DirectMessageIdentifier";
    // Cached lookups: the Lock gate reads the open screen's account several times a second, and
    // each call used to load the class, throw NoSuchMethodException and list every field again.
    /** Classes found by name, per loader. Only hits: a class missing now may be loaded later. */
    private static final Map<ClassLoader, Map<String, Class<?>>> types = new ConcurrentHashMap<>();
    /** Public no-arg methods by class and name, misses included. */
    private static final Map<Class<?>, Map<String, Optional<Method>>> methods = new ConcurrentHashMap<>();
    /** Each class's own non-static, non-primitive fields, already accessible. */
    private static final Map<Class<?>, Field[]> objectFields = new ConcurrentHashMap<>();
    private UnsentIdentity() {}

    public static String account(Object owner) {
        if (owner == null) return null;
        try {
            Class<?> session = named(SESSION, owner.getClass().getClassLoader());
            Method userId = publicMethod(session, "getUserId");
            if (userId == null) return null;
            Method getSession = publicMethod(owner.getClass(), "getSession");
            if (getSession != null) {
                try {
                    Object current = getSession.invoke(owner);
                    if (session.isInstance(current)) return (String) userId.invoke(current);
                } catch (ReflectiveOperationException ignored) {}
            }
            // Store, entry and row each own a direct session. A nested controller can belong to
            // another account, so it must never supply ownership for retained private messages.
            Object value = session.isInstance(owner) ? owner : directValue(owner, session);
            return value == null ? null : (String) userId.invoke(value);
        } catch (Throwable ignored) { return null; }
    }

    private static Class<?> named(String name, ClassLoader loader) throws ClassNotFoundException {
        if (loader == null) return Class.forName(name, false, null);
        Map<String, Class<?>> loaded = types.computeIfAbsent(loader, ignored -> new ConcurrentHashMap<>());
        Class<?> type = loaded.get(name);
        if (type == null) {
            type = Class.forName(name, false, loader);
            loaded.put(name, type);
        }
        return type;
    }

    /** The class's public no-arg method of that name, or null. */
    static Method publicMethod(Class<?> cls, String name) {
        return methods.computeIfAbsent(cls, ignored -> new ConcurrentHashMap<>())
                .computeIfAbsent(name, ignored -> {
                    try {
                        return Optional.of(cls.getMethod(name));
                    } catch (NoSuchMethodException missing) {
                        return Optional.empty();
                    }
                }).orElse(null);
    }

    private static Field[] objectFields(Class<?> cls) {
        Field[] fields = objectFields.get(cls);
        if (fields != null) return fields;
        List<Field> found = new ArrayList<>();
        for (Field field : cls.getDeclaredFields()) {
            if (Modifier.isStatic(field.getModifiers()) || field.getType().isPrimitive()) continue;
            field.setAccessible(true);
            found.add(field);
        }
        fields = found.toArray(new Field[0]);
        objectFields.put(cls, fields);
        return fields;
    }

    public static String thread(Object owner) {
        if (owner == null) return null;
        try {
            Class<?> type = named(THREAD, owner.getClass().getClassLoader());
            Object key = type.isInstance(owner) ? owner : directValue(owner, type);
            if (key == null) return null;
            // Native keys carry both mThreadId and mThreadV2Id. Their labelled representation
            // identifies the canonical ID without guessing which obfuscated String field is first.
            return threadId(key.toString());
        } catch (Throwable ignored) { return null; }
    }

    public static String threadId(String description) {
        if (description == null || !description.startsWith("DirectThreadKey{")) return null;
        String label = "mThreadId='";
        int start = description.indexOf(label);
        if (start < 0) return null;
        start += label.length();
        int end = description.indexOf('\'', start);
        if (end <= start) return null;
        String id = description.substring(start, end);
        return "null".equals(id) ? null : id;
    }

    /** Current row data is stored through RecyclerView's typed item interface (getType()). */
    public static Object boundData(Object holder, Class<?> messageClass) {
        for (Class<?> cls = holder.getClass(); cls != null && cls != Object.class; cls = cls.getSuperclass()) {
            for (Field field : cls.getDeclaredFields()) {
                if (Modifier.isStatic(field.getModifiers()) || !field.getType().isInterface()) continue;
                try {
                    if (field.getType().getMethod("getType").getReturnType() != int.class) continue;
                    field.setAccessible(true);
                    Object data = field.get(holder);
                    if (directValue(data, messageClass) != null) return data;
                } catch (ReflectiveOperationException ignored) {}
            }
        }
        return null;
    }

    public static Object directValue(Object owner, Class<?> type) {
        if (owner == null) return null;
        Object result = null;
        for (Class<?> cls = owner.getClass(); cls != null && cls != Object.class; cls = cls.getSuperclass()) {
            for (Field field : objectFields(cls)) {
                try {
                    Object value = field.get(owner);
                    // ModalActivity declares a base Session field holding a UserSession.
                    if (!type.isInstance(value)) continue;
                    if (result != null && result != value) return null;
                    result = value;
                } catch (ReflectiveOperationException ignored) {}
            }
        }
        return result;
    }

    public static List<String> messageIds(Object message) {
        if (message != null) {
            for (Method method : message.getClass().getMethods()) {
                if (!Modifier.isStatic(method.getModifiers()) && method.getParameterCount() == 0
                        && method.getReturnType().getName().equals(IDENTIFIER)) {
                    try { return strings(method.invoke(message)); } catch (ReflectiveOperationException ignored) {}
                }
            }
        }
        return Collections.emptyList();
    }

    public static String messageText(Object message, Field field) {
        if (message == null || field == null || field.getType() != String.class
                || !field.getDeclaringClass().isInstance(message)) return null;
        try { return (String) field.get(message); } catch (IllegalAccessException ignored) { return null; }
    }

    /** Direct scalar fields only: quoted messages and shared media must never mark the parent. */
    public static List<String> strings(Object message) {
        List<String> out = new ArrayList<>();
        if (message == null) return out;
        for (Class<?> cls = message.getClass(); cls != null && cls != Object.class; cls = cls.getSuperclass()) {
            for (Field field : cls.getDeclaredFields()) {
                if (Modifier.isStatic(field.getModifiers()) || field.getType() != String.class) continue;
                try { field.setAccessible(true); String value = (String) field.get(message);
                    if (value != null && !value.isEmpty()) out.add(value);
                } catch (Throwable ignored) {}
            }
        }
        return out;
    }
}
