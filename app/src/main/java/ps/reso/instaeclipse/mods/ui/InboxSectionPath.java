package ps.reso.instaeclipse.mods.ui;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;

final class InboxSectionPath {
    final Method emit;
    private final int sectionArg;
    private final Field type;

    private InboxSectionPath(Method emit, int sectionArg, Field type) {
        this.emit = emit;
        this.sectionArg = sectionArg;
        this.type = type;
    }

    static InboxSectionPath of(Method lookup) {
        Class<?>[] params = lookup.getParameterTypes();
        Class<?> section = lookup.getReturnType();
        if (!Modifier.isStatic(lookup.getModifiers()) || params.length == 0 || !params[0].isEnum()
                || section.isPrimitive() || section.isEnum()) return null;
        Field type = null;
        for (Class<?> c = section; c != null && c != Object.class; c = c.getSuperclass()) {
            for (Field field : c.getDeclaredFields()) {
                if (Modifier.isStatic(field.getModifiers()) || field.getType() != params[0]) continue;
                if (type != null) return null;
                type = field;
            }
        }
        Method emit = null;
        int sectionArg = -1;
        for (Method method : lookup.getDeclaringClass().getDeclaredMethods()) {
            if (!Modifier.isStatic(method.getModifiers()) || method.getReturnType() != boolean.class) continue;
            Class<?>[] types = method.getParameterTypes();
            for (int i = 0; i < types.length; i++) {
                if (types[i] != section) continue;
                if (emit != null) return null;
                emit = method;
                sectionArg = i;
            }
        }
        if (type == null || emit == null) return null;
        type.setAccessible(true);
        emit.setAccessible(true);
        return new InboxSectionPath(emit, sectionArg, type);
    }

    String typeOf(Object[] emitArgs) throws IllegalAccessException {
        Object section = emitArgs[sectionArg];
        Object value = section == null ? null : type.get(section);
        return value instanceof Enum<?> constant ? constant.name() : null;
    }

    static boolean suggestsPeople(String type) {
        return "FIND_PEOPLE".equals(type) || "RECOMMENDED_USER_SUGGESTIONS".equals(type);
    }

    static boolean drops(String type, boolean hideDmSuggestions, boolean disableDiscoverPeople, boolean hideNotesTray) {
        if (hideNotesTray && ("TRAY".equals(type) || "TRAY_DIVIDER".equals(type))) return true;
        if (disableDiscoverPeople) return suggestsPeople(type);
        return hideDmSuggestions && "RECOMMENDED_USER_SUGGESTIONS".equals(type);
    }
}
