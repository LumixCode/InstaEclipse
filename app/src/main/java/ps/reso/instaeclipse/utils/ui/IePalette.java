package ps.reso.instaeclipse.utils.ui;

import android.app.Activity;
import android.content.Context;
import android.content.ContextWrapper;
import android.content.res.Configuration;
import android.content.res.Resources;
import android.util.TypedValue;

import java.util.Map;
import java.util.WeakHashMap;

import ps.reso.instaeclipse.mods.ui.theme.IgThemeEngine;

public final class IePalette {

    public static final double TEXT = 4.5;
    public static final double ICON = 3.0;
    public static final int ACCENT_FALLBACK = 0xFF0095F6;
    public static final int BLACK = 0xFF000000;
    public static final int WHITE = 0xFFFFFFFF;
    public static final int MISSING = 0;

    static final int PRIMARY_BACKGROUND = 0;
    static final int ELEVATED_BACKGROUND = 1;
    static final int SECONDARY_BACKGROUND = 2;
    static final int HIGHLIGHT_BACKGROUND = 3;
    static final int PRIMARY_TEXT = 4;
    static final int SECONDARY_TEXT = 5;
    static final int SEPARATOR = 6;
    static final int ELEVATED_SEPARATOR = 7;
    static final int ERROR_OR_DESTRUCTIVE = 8;
    static final int PRIMARY_BUTTON = 9;
    static final int SUCCESS = 10;

    static final String[] ATTRS = {
            "igds_color_primary_background",
            "igds_color_elevated_background",
            "igds_color_secondary_background",
            "igds_color_highlight_background",
            "igds_color_primary_text",
            "igds_color_secondary_text",
            "igds_color_separator",
            "igds_color_elevated_separator",
            "igds_color_error_or_destructive",
            "igds_color_primary_button",
            "igds_color_success",
    };

    static final int[] IG_LIGHT = {
            0xFFFFFFFF, 0xFFFFFFFF, 0xFFF5F5F5, 0xFFEFEFEF, 0xFF000000, 0xFF737373,
            0xFFDBDBDB, 0xFFDBDBDB, 0xFFDB2634, 0xFF0095F6, 0xFF0E8630,
    };

    static final int[] IG_DARK = {
            0xFF000000, 0xFF262626, 0xFF1A1A1A, 0xFF262626, 0xFFF5F5F5, 0xFFA8A8A8,
            0xFF262626, 0xFF363636, 0xFFDB2634, 0xFF0095F6, 0xFF0E8630,
    };

    private static final Map<Resources.Theme, IePalette> CACHE = new WeakHashMap<>();

    public final boolean dark;
    public final int sheet, card, field, dialog;
    public final int text, sub, line, pressed;
    public final int danger, success, accent, onAccent;
    public final int backdrop, highlight;
    public final int thumbOff, trackOff, trackOn;

    private final boolean night;
    private final int themeAccent;

    private IePalette(boolean night, int themeAccent, int[] v) {
        this.night = night;
        this.themeAccent = themeAccent;
        backdrop = v[PRIMARY_BACKGROUND];
        dark = luminance(backdrop) < 0.5;
        int elevated = v[ELEVATED_BACKGROUND];
        int primaryText = v[PRIMARY_TEXT];
        if (dark) {
            sheet = elevated;
            card = mix(elevated, primaryText, 0.08f);
            field = card;
        } else {
            sheet = v[SECONDARY_BACKGROUND];
            card = elevated;
            field = v[SECONDARY_BACKGROUND];
        }
        dialog = elevated;
        text = contrast(primaryText, card) >= TEXT ? primaryText : blackOrWhite(card);
        sub = readableOn(v[SECONDARY_TEXT], TEXT, sheet, card, dialog, field);
        line = dark ? v[ELEVATED_SEPARATOR] : v[SEPARATOR];
        pressed = mix(card, text, 0.08f);
        danger = v[ERROR_OR_DESTRUCTIVE];
        success = v[SUCCESS];
        accent = themeAccent != MISSING ? themeAccent | 0xFF000000 : v[PRIMARY_BUTTON];
        onAccent = contrast(WHITE, accent) >= ICON ? WHITE : blackOrWhite(accent);
        highlight = v[HIGHLIGHT_BACKGROUND];
        thumbOff = WHITE;
        trackOff = trackOff(card, text);
        trackOn = trackOn(card, accent);
    }

    static int trackOff(int surface, int text) {
        return readable(mix(surface, text, 0.38f), surface, ICON);
    }

    static int trackOn(int surface, int accent) {
        return mix(surface, accent, 0.5f);
    }

    public static IePalette of(Context context) {
        Context host = host(context);
        boolean night = night(host);
        int themeAccent = IgThemeEngine.accentOr(MISSING);
        Resources.Theme theme;
        try {
            theme = host.getTheme();
        } catch (Throwable t) {
            theme = null;
        }
        if (theme == null) return compute(night, null, themeAccent);
        synchronized (CACHE) {
            IePalette cached = CACHE.get(theme);
            if (cached != null && cached.night == night && cached.themeAccent == themeAccent) return cached;
        }
        IePalette palette = compute(night, resolve(host, theme), themeAccent);
        synchronized (CACHE) {
            CACHE.put(theme, palette);
        }
        return palette;
    }

    public static IePalette compute(boolean night, int[] attrsOrMissing, int themeAccent) {
        int[] fallback = night ? IG_DARK : IG_LIGHT;
        int[] v = new int[ATTRS.length];
        for (int i = 0; i < v.length; i++) {
            boolean present = attrsOrMissing != null && i < attrsOrMissing.length && attrsOrMissing[i] != MISSING;
            v[i] = present ? attrsOrMissing[i] : fallback[i];
        }
        v[PRIMARY_BACKGROUND] = over(v[PRIMARY_BACKGROUND], night ? BLACK : WHITE);
        for (int i = 0; i < v.length; i++) {
            if (i != PRIMARY_BACKGROUND) v[i] = over(v[i], v[PRIMARY_BACKGROUND]);
        }
        return new IePalette(night, themeAccent, v);
    }

    public static int readable(int color, int background, double min) {
        int bg = over(background, BLACK);
        int c = over(color, bg);
        if (contrast(c, bg) >= min) return c;
        int target = blackOrWhite(bg);
        if (contrast(target, bg) < min) return target;
        float lo = 0f, hi = 1f;
        for (int i = 0; i < 16; i++) {
            float mid = (lo + hi) / 2f;
            if (contrast(mix(c, target, mid), bg) >= min) hi = mid;
            else lo = mid;
        }
        return mix(c, target, hi);
    }

    static int readableOn(int color, double min, int... backgrounds) {
        int c = color;
        for (int pass = 0; pass < 2; pass++) {
            for (int bg : backgrounds) c = readable(c, bg, min);
        }
        return c;
    }

    public static double contrast(int a, int b) {
        double la = luminance(a), lb = luminance(b);
        return (Math.max(la, lb) + 0.05) / (Math.min(la, lb) + 0.05);
    }

    public static double luminance(int color) {
        return 0.2126 * channel((color >> 16) & 0xFF)
                + 0.7152 * channel((color >> 8) & 0xFF)
                + 0.0722 * channel(color & 0xFF);
    }

    public static int mix(int from, int to, float amount) {
        return (mixChannel(from >>> 24, to >>> 24, amount) << 24)
                | (mixChannel((from >> 16) & 0xFF, (to >> 16) & 0xFF, amount) << 16)
                | (mixChannel((from >> 8) & 0xFF, (to >> 8) & 0xFF, amount) << 8)
                | mixChannel(from & 0xFF, to & 0xFF, amount);
    }

    public static int withAlpha(int color, int alpha) {
        return (color & 0x00FFFFFF) | (alpha << 24);
    }

    static int over(int color, int background) {
        int alpha = color >>> 24;
        if (alpha == 0xFF) return color;
        float a = alpha / 255f;
        int bg = background | 0xFF000000;
        return mix(bg, color | 0xFF000000, a) | 0xFF000000;
    }

    static int blackOrWhite(int background) {
        return contrast(BLACK, background) >= contrast(WHITE, background) ? BLACK : WHITE;
    }

    private static int mixChannel(int from, int to, float amount) {
        return Math.round(from + (to - from) * amount);
    }

    private static double channel(int value) {
        double s = value / 255.0;
        return s <= 0.03928 ? s / 12.92 : Math.pow((s + 0.055) / 1.055, 2.4);
    }

    private static Context host(Context context) {
        Context c = context;
        while (!(c instanceof Activity) && c instanceof ContextWrapper w && w.getBaseContext() != null) {
            c = w.getBaseContext();
        }
        return c instanceof Activity ? c : context;
    }

    private static boolean night(Context context) {
        try {
            return (context.getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK)
                    == Configuration.UI_MODE_NIGHT_YES;
        } catch (Throwable t) {
            return false;
        }
    }

    private static int[] resolve(Context context, Resources.Theme theme) {
        int[] out = new int[ATTRS.length];
        TypedValue value = new TypedValue();
        for (int i = 0; i < ATTRS.length; i++) {
            out[i] = MISSING;
            int attr = ResIds.attr(context, ATTRS[i]);
            if (attr == 0) continue;
            try {
                if (!theme.resolveAttribute(attr, value, true)) continue;
                if (value.type >= TypedValue.TYPE_FIRST_COLOR_INT && value.type <= TypedValue.TYPE_LAST_COLOR_INT) {
                    out[i] = value.data;
                } else if (value.resourceId != 0) {
                    out[i] = context.getResources().getColorStateList(value.resourceId, theme).getDefaultColor();
                }
            } catch (Throwable ignored) {
            }
        }
        return out;
    }
}
