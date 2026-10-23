package ps.reso.instaeclipse.utils.ghost;

import android.content.Context;
import android.view.View;
import android.view.ViewGroup;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import ps.reso.instaeclipse.utils.core.SafeReflect;

public final class HeaderButtonKind {

    public enum Header { CALLS, CHANNEL, OTHER }

    public static final String AUDIO_CALL = "AUDIO_CALL";
    public static final String VIDEO_CALL = "VIDEO_CALL";
    public static final String BLEND_ADD = "BLEND_ADD";
    public static final String NONE = "";
    private static final Set<String> CHANNEL_KINDS =
            Set.of("CHANNEL_MUTE", "CHANNEL_UNMUTE", "CHANNEL_SHARE", "CHANNEL_SHARE_TO_STORY");
    private static final int KIND_TAG = 0x7e1e0277;
    private static final Map<Class<?>, Boolean> headerEnums = new ConcurrentHashMap<>();

    private HeaderButtonKind() {}

    public static Header classify(Set<String> kinds) {
        if (kinds.contains(AUDIO_CALL) || kinds.contains(VIDEO_CALL)) return Header.CALLS;
        for (String kind : kinds) {
            if (CHANNEL_KINDS.contains(kind)) return Header.CHANNEL;
        }
        return Header.OTHER;
    }

    public static String kindOf(Set<String> names) {
        for (String name : names) {
            if (name.equals(AUDIO_CALL) || name.equals(VIDEO_CALL)) return name;
        }
        return names.contains(BLEND_ADD) ? BLEND_ADD : NONE;
    }

    public static Set<String> kinds(ViewGroup header) {
        Set<String> kinds = new LinkedHashSet<>();
        for (int i = 0; i < header.getChildCount(); i++) kinds.addAll(names(header.getChildAt(i)));
        return kinds;
    }

    public static String kind(View v) {
        return kindOf(names(v));
    }

    @SuppressWarnings("unchecked")
    private static Set<String> names(View v) {
        Object cached = v.getTag(KIND_TAG);
        if (cached instanceof Set<?> set) return (Set<String>) set;
        Set<String> names = new LinkedHashSet<>();
        try {
            Object info = field(View.class, "mListenerInfo", v);
            Object listener = info != null ? field(info.getClass(), "mOnClickListener", info) : null;
            if (listener == null) return names;
            collect(listener, 0, names);
        } catch (Throwable ignored) {}
        Set<String> found = Collections.unmodifiableSet(names);
        v.setTag(KIND_TAG, found);
        return found;
    }

    private static void collect(Object o, int depth, Set<String> out) throws IllegalAccessException {
        if (o == null || depth > 3 || o instanceof View || o instanceof Context) return;
        if (o instanceof Enum<?> e) {
            if (isHeaderEnum(e.getDeclaringClass())) out.add(e.name());
            return;
        }
        String cn = o.getClass().getName();
        if (!cn.startsWith("X.") && !cn.startsWith("com.instagram.")) return;
        for (Class<?> k = o.getClass(); k != null && k != Object.class; k = k.getSuperclass()) {
            for (Field f : SafeReflect.fields(k)) {
                if (Modifier.isStatic(f.getModifiers()) || f.getType().isPrimitive() || f.getType() == String.class) continue;
                f.setAccessible(true);
                collect(f.get(o), depth + 1, out);
            }
        }
    }

    private static boolean isHeaderEnum(Class<?> type) {
        return headerEnums.computeIfAbsent(type, t -> {
            Object[] constants = t.getEnumConstants();
            if (constants == null) return false;
            for (Object constant : constants) {
                if (((Enum<?>) constant).name().equals(AUDIO_CALL)) return true;
            }
            return false;
        });
    }

    private static Object field(Class<?> c, String name, Object o) throws ReflectiveOperationException {
        Field f = c.getDeclaredField(name);
        f.setAccessible(true);
        return f.get(o);
    }
}
