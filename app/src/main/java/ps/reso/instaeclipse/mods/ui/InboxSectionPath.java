package ps.reso.instaeclipse.mods.ui;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;

/**
 * Where Instagram's Direct inbox generator emits each of its sections, found from class shape only.
 * The generator (451 X.07Ln, 446 X.05Ho) owns one section generator per section type (451 X.07U5
 * subclasses, 446 X.0DBC), each holding its type: a Java enum whose constant names are kept
 * (451 X.07JC, 446 X.05Eo: "THREADS", "FIND_PEOPLE", ...). Every section's rows go through one
 * static emitter next to the type lookup that throws "No section generator found for section
 * type " (451 X.07Lo.A00 / A04(X.07Mq, X.07U5, X.07Lr, X.07Mp)Z, 446 X.05Hp.A00 / A04); the
 * emitter returns false when the section added no rows.
 */
final class InboxSectionPath {
    final Method emit;
    private final int sectionArg;
    private final Field type;

    private InboxSectionPath(Method emit, int sectionArg, Field type) {
        this.emit = emit;
        this.sectionArg = sectionArg;
        this.type = type;
    }

    /**
     * From the static type lookup {@code (Enum type, ...) -> section}: the one static boolean
     * method of its class that takes the section type, and the section's one field of the enum.
     * Null when any of them is missing or ambiguous.
     */
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

    /** The type name of the section an emitter call was given, or null. */
    String typeOf(Object[] emitArgs) throws IllegalAccessException {
        Object section = emitArgs[sectionArg];
        Object value = section == null ? null : type.get(section);
        return value instanceof Enum<?> constant ? constant.name() : null;
    }

    /**
     * Sections that only suggest people: FIND_PEOPLE (the "find friends" header and its
     * contacts / search activation cards) and RECOMMENDED_USER_SUGGESTIONS (suggested accounts to
     * follow or message). SUGGESTIONS (recent contacts to message, channel invites), the threads,
     * requests and every other section stay.
     */
    static boolean suggestsPeople(String type) {
        return "FIND_PEOPLE".equals(type) || "RECOMMENDED_USER_SUGGESTIONS".equals(type);
    }
}
