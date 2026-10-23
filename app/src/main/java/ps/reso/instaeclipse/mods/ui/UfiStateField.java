package ps.reso.instaeclipse.mods.ui;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.List;

final class UfiStateField {

    private UfiStateField() {}

    static Field fieldFor(Object blank, String label) throws IllegalAccessException {
        List<Field> flags = new ArrayList<>();
        for (Field f : blank.getClass().getDeclaredFields()) {
            if (Modifier.isStatic(f.getModifiers()) || f.getType() != boolean.class) continue;
            f.setAccessible(true);
            if (f.getBoolean(blank)) return null;
            flags.add(f);
        }
        if (!"false".equals(shown(blank.toString(), label))) return null;
        Field found = null;
        for (Field f : flags) {
            String shown;
            f.setBoolean(blank, true);
            try {
                shown = shown(blank.toString(), label);
            } finally {
                f.setBoolean(blank, false);
            }
            if (!"true".equals(shown)) continue;
            if (found != null) return null;
            found = f;
        }
        return found;
    }

    static String shown(String rendered, String label) {
        if (rendered == null || label == null || label.isEmpty()) return null;
        int at = rendered.indexOf(label);
        if (at < 0 || rendered.indexOf(label, at + 1) >= 0) return null;
        int value = at + label.length();
        if (rendered.startsWith("true", value)) return "true";
        if (rendered.startsWith("false", value)) return "false";
        return null;
    }
}
