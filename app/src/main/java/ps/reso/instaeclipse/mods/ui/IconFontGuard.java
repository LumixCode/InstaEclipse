package ps.reso.instaeclipse.mods.ui;

import java.util.Arrays;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Set;
import java.util.function.ToIntFunction;

final class IconFontGuard {

    static final String[] FONTS = {};

    private final int[] ids;
    private final ThreadLocal<Boolean> pending = ThreadLocal.withInitial(() -> Boolean.FALSE);
    private final Set<Object> iconTypefaces = Collections.synchronizedSet(Collections.newSetFromMap(new IdentityHashMap<>()));

    private IconFontGuard(int[] ids) {
        this.ids = ids;
    }

    static IconFontGuard resolve(ToIntFunction<String> fontId) {
        int[] ids = new int[FONTS.length];
        int n = 0;
        for (String name : FONTS) {
            int id = fontId.applyAsInt(name);
            if (id != 0) ids[n++] = id;
        }
        return new IconFontGuard(Arrays.copyOf(ids, n));
    }

    static boolean iconName(String name) {
        String low = name.toLowerCase();
        return low.contains("icon") || low.contains("glyph");
    }

    void fontResource(int id) {
        pending.set(isIconFont(id));
    }

    boolean keep(Object built) {
        if (!pending.get()) return false;
        pending.set(Boolean.FALSE);
        if (built != null) iconTypefaces.add(built);
        return true;
    }

    boolean isIcon(Object typeface) {
        return typeface != null && iconTypefaces.contains(typeface);
    }

    private boolean isIconFont(int id) {
        for (int i : ids) if (i == id) return true;
        return false;
    }
}
