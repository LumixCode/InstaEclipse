package ps.reso.instaeclipse.utils.ghost;

import android.content.Context;
import android.view.View;
import android.view.ViewGroup;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.LinkedHashSet;
import java.util.Set;

import ps.reso.instaeclipse.utils.core.SafeReflect;

public final class HeaderButtonKind {

    public enum Header { CALLS, CHANNEL, OTHER }

    public static final String AUDIO_CALL = "AUDIO_CALL";
    public static final String VIDEO_CALL = "VIDEO_CALL";
    public static final String BLEND_ADD = "BLEND_ADD";
    public static final String NONE = "";
    private static final int KIND_TAG = 0x7e1e0277;

    private HeaderButtonKind() {}

    public static Header classify(Set<String> kinds) {
        for (String kind : kinds) {
            String text = kind.toLowerCase();
            if (text.contains("audio call") || text.contains("video call")) return Header.CALLS;
            if (text.contains("blend")) return Header.OTHER;
        }
        return Header.CHANNEL;
    }

    public static String kindOf(Set<String> names) {
        for (String name : names) {
            if (name.equals(AUDIO_CALL) || name.equals(VIDEO_CALL)) return name;
        }
        return NONE;
    }

    public static Set<String> kinds(ViewGroup header) {
        Set<String> kinds = new LinkedHashSet<>();
        for (int i = 0; i < header.getChildCount(); i++) {
            CharSequence description = header.getChildAt(i).getContentDescription();
            if (description != null) kinds.add(description.toString());
        }
        return kinds;
    }

    public static String kind(View v) {
        Object cached = v.getTag(KIND_TAG);
        if (cached instanceof String s) return s;
        String found = NONE;
        try {
            Object info = field(View.class, "mListenerInfo", v);
            Object listener = info != null ? field(info.getClass(), "mOnClickListener", info) : null;
            Set<String> names = new LinkedHashSet<>();
            collect(listener, 0, names);
            found = kindOf(names);
        } catch (Throwable ignored) {}
        v.setTag(KIND_TAG, found);
        return found;
    }

    private static void collect(Object o, int depth, Set<String> out) throws IllegalAccessException {
        if (o == null || depth > 3 || o instanceof View || o instanceof Context) return;
        if (o instanceof Enum<?> e) {
            String n = e.name();
            if (n.equals(AUDIO_CALL) || n.equals(VIDEO_CALL)) out.add(n);
            return;
        }
        String cn = o.getClass().getName();
        if (!cn.startsWith("X.") && !cn.startsWith("com.instagram.")) return;
        for (Class<?> k = o.getClass(); k != null && k != Object.class; k = k.getSuperclass()) {
            for (Field f : SafeReflect.fields(k)) {
                if (!out.isEmpty()) return;
                if (Modifier.isStatic(f.getModifiers()) || f.getType().isPrimitive() || f.getType() == String.class) continue;
                f.setAccessible(true);
                collect(f.get(o), depth + 1, out);
            }
        }
    }

    private static Object field(Class<?> c, String name, Object o) throws ReflectiveOperationException {
        Field f = c.getDeclaredField(name);
        f.setAccessible(true);
        return f.get(o);
    }
}
