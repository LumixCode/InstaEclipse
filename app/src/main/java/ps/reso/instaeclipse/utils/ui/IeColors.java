package ps.reso.instaeclipse.utils.ui;

import android.content.Context;
import android.content.res.Resources;
import android.util.TypedValue;

import java.util.Map;
import java.util.WeakHashMap;

public final class IeColors {

    public static final int SUCCESS_LIGHT = 0xFF1E8E3E;
    public static final int SUCCESS_DARK = 0xFF34C759;
    public static final int DESTRUCTIVE_LIGHT = 0xFFED4956;
    public static final int DESTRUCTIVE_DARK = 0xFFFF453A;
    static final double MIN_CONTRAST = 3.0;
    static final String[] ATTRS = {"igds_color_success", "igds_color_error_or_destructive"};

    private static final Map<Resources.Theme, int[]> CACHE = new WeakHashMap<>();

    private IeColors() {}

    public static int success(Context ctx, int background) {
        return choose(themed(ctx)[0], background, SUCCESS_LIGHT, SUCCESS_DARK);
    }

    public static int destructive(Context ctx, int background) {
        return choose(themed(ctx)[1], background, DESTRUCTIVE_LIGHT, DESTRUCTIVE_DARK);
    }

    static int choose(int themed, int background, int light, int dark) {
        int bg = IePalette.over(background, IePalette.BLACK);
        if (themed != IePalette.MISSING && IePalette.contrast(IePalette.over(themed, bg), bg) >= MIN_CONTRAST) return themed;
        return IePalette.luminance(bg) < 0.5 ? dark : light;
    }

    private static int[] themed(Context ctx) {
        Resources.Theme theme;
        try {
            theme = ctx.getTheme();
        } catch (Throwable t) {
            theme = null;
        }
        if (theme == null) return new int[]{IePalette.MISSING, IePalette.MISSING};
        synchronized (CACHE) {
            int[] cached = CACHE.get(theme);
            if (cached != null) return cached;
        }
        int[] colors = new int[ATTRS.length];
        for (int i = 0; i < ATTRS.length; i++) colors[i] = resolve(ctx, theme, ATTRS[i]);
        synchronized (CACHE) {
            CACHE.put(theme, colors);
        }
        return colors;
    }

    private static int resolve(Context ctx, Resources.Theme theme, String name) {
        int attr = ResIds.attr(ctx, name);
        if (attr == 0) return IePalette.MISSING;
        try {
            TypedValue value = new TypedValue();
            if (!theme.resolveAttribute(attr, value, true)) return IePalette.MISSING;
            if (value.type >= TypedValue.TYPE_FIRST_COLOR_INT && value.type <= TypedValue.TYPE_LAST_COLOR_INT) return value.data;
            if (value.resourceId != 0) return ctx.getResources().getColorStateList(value.resourceId, theme).getDefaultColor();
        } catch (Throwable ignored) {
        }
        return IePalette.MISSING;
    }
}
