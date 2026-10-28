package ps.reso.instaeclipse.utils.ghost;

import org.luckypray.dexkit.query.FindMethod;
import org.luckypray.dexkit.query.matchers.MethodMatcher;
import org.luckypray.dexkit.result.FieldData;
import org.luckypray.dexkit.result.UsingFieldData;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.WeakHashMap;
import java.util.concurrent.ConcurrentHashMap;

import ps.reso.instaeclipse.utils.core.DexKitCache;
import ps.reso.instaeclipse.utils.core.DexKitQueryCache;
import ps.reso.instaeclipse.utils.core.DexKitQueryCache.Found;
import ps.reso.instaeclipse.utils.core.ModuleBridge;
import ps.reso.instaeclipse.utils.core.SafeReflect;

/** Bounded lookup on the bound row, never across adapter collections or adjacent messages. */
public final class UnsentIdentity {
    public static final String SESSION = "com.instagram.common.session.UserSession";
    public static final String THREAD = "com.instagram.model.direct.DirectThreadKey";
    public static final String IDENTIFIER = "com.instagram.model.direct.messageid.DirectMessageIdentifier";
    public static final String USER = "com.instagram.user.model.User";
    private static final String SENDER_KEY = "KeepUnsent_senderField_v1";
    private static final String STRING_TYPE = ":Ljava/lang/String;";
    private static final String EXPIRY = "message_expiration_timestamp_ms";
    private static volatile Field sender;
    private static volatile boolean senderLooked;
    // Cached lookups: the Lock gate reads the open screen's account several times a second, and
    // each call used to load the class, throw NoSuchMethodException and list every field again.
    /** Classes found by name, per loader. Only hits: a class missing now may be loaded later. */
    private static final Map<ClassLoader, Map<String, Class<?>>> types = new ConcurrentHashMap<>();
    /** Public no-arg methods by class and name, misses included. */
    private static final Map<Class<?>, Map<String, Optional<Method>>> methods = new ConcurrentHashMap<>();
    /** Each class's own non-static, non-primitive fields, already accessible. */
    private static final Map<Class<?>, Field[]> objectFields = new ConcurrentHashMap<>();
    private static final Map<Class<?>, Method[]> identifierGetters = new ConcurrentHashMap<>();
    private static final Map<Class<?>, Field[]> stringFields = new ConcurrentHashMap<>();
    private static final Map<Class<?>, Field[]> boundFields = new ConcurrentHashMap<>();
    private static final Map<Object, String> threadIds = Collections.synchronizedMap(new WeakHashMap<>());
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
        for (Field field : SafeReflect.fields(cls)) {
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
            return key == null ? null : keyThread(key);
        } catch (Throwable ignored) { return null; }
    }

    private static String keyThread(Object key) {
        String known = threadIds.get(key);
        if (known != null) return known;
        String id = threadId(key.toString());
        if (id != null) threadIds.put(key, id);
        return id;
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
        for (Field field : boundFields(holder.getClass())) {
            try {
                Object data = field.get(holder);
                if (directValue(data, messageClass) != null) return data;
            } catch (ReflectiveOperationException ignored) {}
        }
        return null;
    }

    static Field[] boundFields(Class<?> holder) {
        Field[] fields = boundFields.get(holder);
        if (fields != null) return fields;
        List<Field> found = new ArrayList<>();
        for (Class<?> cls = holder; cls != null && cls != Object.class; cls = cls.getSuperclass()) {
            for (Field field : SafeReflect.fields(cls)) {
                if (Modifier.isStatic(field.getModifiers()) || !field.getType().isInterface()) continue;
                Method getType = publicMethod(field.getType(), "getType");
                if (getType == null || getType.getReturnType() != int.class) continue;
                field.setAccessible(true);
                found.add(field);
            }
        }
        fields = found.toArray(new Field[0]);
        boundFields.put(holder, fields);
        return fields;
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
            for (Method method : identifierGetters(message.getClass())) {
                try { return strings(method.invoke(message)); } catch (ReflectiveOperationException ignored) {}
            }
        }
        return Collections.emptyList();
    }

    public static Object identifier(Object message) {
        if (message != null) {
            for (Method method : identifierGetters(message.getClass())) {
                try { return method.invoke(message); } catch (ReflectiveOperationException ignored) {}
            }
        }
        return null;
    }

    static Method[] identifierGetters(Class<?> type) {
        Method[] getters = identifierGetters.get(type);
        if (getters != null) return getters;
        List<Method> found = new ArrayList<>();
        for (Method method : SafeReflect.publicMethods(type)) {
            if (!Modifier.isStatic(method.getModifiers()) && method.getParameterCount() == 0
                    && method.getReturnType().getName().equals(IDENTIFIER)) found.add(method);
        }
        getters = found.toArray(new Method[0]);
        identifierGetters.put(type, getters);
        return getters;
    }

    public static boolean isFromAccount(Object message, String accountId) {
        return isFromAccount(message, accountId, sender);
    }

    static boolean isFromAccount(Object message, String accountId, Field field) {
        if (message == null || accountId == null || field == null
                || !field.getDeclaringClass().isInstance(message)) return false;
        try {
            return accountId.equals(field.get(message));
        } catch (Throwable ignored) {
            return false;
        }
    }

    public static boolean anyStringFieldIs(Object message, String value) {
        if (message == null || value == null) return false;
        for (Field field : stringFields(message.getClass())) {
            try { if (value.equals(field.get(message))) return true; } catch (Throwable ignored) {}
        }
        return false;
    }

    public static Field senderField(Class<?> messageClass) {
        if (senderLooked || messageClass == null) return sender;
        synchronized (UnsentIdentity.class) {
            if (senderLooked) return sender;
            Field found = DexKitCache.isCacheValid() ? DexKitCache.loadField(SENDER_KEY, messageClass.getClassLoader()) : null;
            if (!isSenderOf(found, messageClass)) {
                if (ModuleBridge.get() == null) {
                    if (ModuleBridge.failed()) senderLooked = true;
                    return null;
                }
                found = findSenderField(messageClass);
                if (found != null) DexKitCache.saveField(SENDER_KEY, found);
            }
            sender = found;
            senderLooked = true;
            return found;
        }
    }

    private static boolean isSenderOf(Field field, Class<?> messageClass) {
        return field != null && field.getType() == String.class && !Modifier.isStatic(field.getModifiers())
                && field.getDeclaringClass().isAssignableFrom(messageClass);
    }

    private static Field findSenderField(Class<?> messageClass) {
        try {
            List<List<String>> reads = new ArrayList<>();
            Map<String, FieldData> fields = new HashMap<>();
            for (Found method : DexKitQueryCache.methods(FindMethod.create().matcher(MethodMatcher.create()
                    .declaredClass(messageClass.getName()).paramTypes(USER).returnType("boolean")))) {
                List<String> read = new ArrayList<>();
                for (UsingFieldData use : DexKitQueryCache.usingFields(method)) {
                    if (!use.getUsingType().isRead()) continue;
                    String descriptor = use.getField().getDescriptor();
                    read.add(descriptor);
                    fields.put(descriptor, use.getField());
                }
                reads.add(read);
            }
            String picked = pickSenderField(reads);
            if (picked == null) return null;
            Field field = fields.get(picked).getFieldInstance(messageClass.getClassLoader());
            field.setAccessible(true);
            return isSenderOf(field, messageClass) ? field : null;
        } catch (Throwable t) {
            return null;
        }
    }

    static String pickSenderField(List<List<String>> readsPerMethod) {
        String picked = null;
        for (List<String> reads : readsPerMethod) {
            Set<String> distinct = new LinkedHashSet<>(reads);
            if (distinct.size() != 1) continue;
            String field = distinct.iterator().next();
            if (!field.endsWith(STRING_TYPE)) continue;
            if (picked != null && !picked.equals(field)) return null;
            picked = field;
        }
        return picked;
    }

    public static Long expiry(byte[] copy) {
        if (copy == null || copy.length == 0) return null;
        try {
            return UnsentJson.parse(new String(copy, StandardCharsets.UTF_8)) instanceof Map<?, ?> message
                    && message.get(EXPIRY) instanceof Number expiry ? expiry.longValue() : null;
        } catch (RuntimeException unreadable) {
            return null;
        }
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
        for (Field field : stringFields(message.getClass())) {
            try { String value = (String) field.get(message);
                if (value != null && !value.isEmpty()) out.add(value);
            } catch (Throwable ignored) {}
        }
        return out;
    }

    static Field[] stringFields(Class<?> type) {
        Field[] fields = stringFields.get(type);
        if (fields != null) return fields;
        List<Field> found = new ArrayList<>();
        for (Class<?> cls = type; cls != null && cls != Object.class; cls = cls.getSuperclass()) {
            for (Field field : SafeReflect.fields(cls)) {
                if (Modifier.isStatic(field.getModifiers()) || field.getType() != String.class) continue;
                try { field.setAccessible(true); found.add(field); } catch (Throwable ignored) {}
            }
        }
        fields = found.toArray(new Field[0]);
        stringFields.put(type, fields);
        return fields;
    }
}
