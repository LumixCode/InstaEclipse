package ps.reso.instaeclipse.mods.ghost;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.List;

import ps.reso.instaeclipse.utils.ghost.UnsentIdentity;

/** Only a render model's own ID, or its single direct message model, can identify the preview. */
final class UnsentMediaIdentity {
    private UnsentMediaIdentity() {}

    static Field[] path(Class<?> model, Class<?> identifier) {
        List<Field[]> matches = new ArrayList<>();
        for (Field field : fields(model)) {
            if (field.getType() == identifier) matches.add(new Field[]{field});
        }
        if (matches.size() == 1) return accessible(matches.get(0));
        if (!matches.isEmpty()) return null;
        for (Field field : fields(model)) {
            for (Field nested : fields(field.getType())) {
                if (nested.getType() == identifier) matches.add(new Field[]{field, nested});
            }
        }
        return matches.size() == 1 ? accessible(matches.get(0)) : null;
    }

    /** The one parameter that carries a message identity, or -1 when none or several do. */
    static int argument(Class<?>[] types, Class<?> identifier) {
        int match = -1;
        for (int i = 0; i < types.length; i++) {
            if (types[i].isPrimitive() || path(types[i], identifier) == null) continue;
            if (match >= 0) return -1;
            match = i;
        }
        return match;
    }

    static List<String> ids(Object model, Field[] path) throws IllegalAccessException {
        if (path == null) return List.of();
        for (Field field : path) {
            if (model == null || !field.getDeclaringClass().isInstance(model)) return List.of();
            model = field.get(model);
        }
        return UnsentIdentity.strings(model);
    }

    static boolean matches(List<String> bound, List<String> rendered) {
        for (String id : rendered) if (id != null && !id.isEmpty() && bound.contains(id)) return true;
        return false;
    }

    /** Returns one matching active row; duplicate aliases fail closed instead of styling another message. */
    static int uniqueMatch(List<? extends List<String>> candidates, List<String> rendered) {
        int match = -1;
        for (int i = 0; i < candidates.size(); i++) {
            if (!matches(candidates.get(i), rendered)) continue;
            if (match >= 0) return -1;
            match = i;
        }
        return match;
    }

    private static List<Field> fields(Class<?> type) {
        List<Field> fields = new ArrayList<>();
        for (Class<?> cls = type; cls != null && cls != Object.class; cls = cls.getSuperclass()) {
            for (Field field : cls.getDeclaredFields()) {
                if (!Modifier.isStatic(field.getModifiers()) && !field.getType().isPrimitive()) fields.add(field);
            }
        }
        return fields;
    }

    private static Field[] accessible(Field[] path) {
        for (Field field : path) field.setAccessible(true);
        return path;
    }
}
