package ps.reso.instaeclipse.mods.ui.theme;

import android.content.Context;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.view.View;
import android.widget.LinearLayout;

import org.json.JSONException;
import org.json.JSONObject;

/**
 * A full custom color palette for Instagram's UI: 15 semantic "slots" (background, surface,
 * text, accent, etc.) that {@link IgColorRemapEngine} and {@link IgThemeEngine} substitute in
 * place of Instagram's own resolved colors.
 */
public class IgThemePalette {

    public static final String SLOT_BACKGROUND = "background";
    public static final String SLOT_SURFACE = "surface";
    public static final String SLOT_PRIMARY_TEXT = "primaryText";
    public static final String SLOT_SECONDARY_TEXT = "secondaryText";
    public static final String SLOT_ACCENT = "accent";
    public static final String SLOT_BUTTON = "button";
    public static final String SLOT_ICON = "icon";
    public static final String SLOT_GLYPH = "glyph";
    public static final String SLOT_DIVIDER = "divider";
    public static final String SLOT_BORDER = "border";
    public static final String SLOT_STATUS_BAR = "statusBar";
    public static final String SLOT_NAVIGATION = "navigation";
    public static final String SLOT_LINK = "link";
    public static final String SLOT_ERROR = "error";
    public static final String SLOT_DESTRUCTIVE = "destructive";

    public static final String[] SLOT_KEYS = {
            SLOT_BACKGROUND, SLOT_SURFACE, SLOT_PRIMARY_TEXT, SLOT_SECONDARY_TEXT, SLOT_ACCENT,
            SLOT_BUTTON, SLOT_ICON, SLOT_GLYPH, SLOT_DIVIDER, SLOT_BORDER, SLOT_STATUS_BAR,
            SLOT_NAVIGATION, SLOT_LINK, SLOT_ERROR, SLOT_DESTRUCTIVE
    };

    public int background;
    public int surface;
    public int primaryText;
    public int secondaryText;
    public int accent;
    public int button;
    public int icon;
    public int glyph;
    public int divider;
    public int border;
    public int statusBar;
    public int navigation;
    public int link;
    public int error;
    public int destructive;

    public IgThemePalette() {}

    public IgThemePalette(int background, int surface, int primaryText, int secondaryText, int accent,
                           int button, int icon, int glyph, int divider, int border, int statusBar,
                           int navigation, int link, int error, int destructive) {
        this.background = background;
        this.surface = surface;
        this.primaryText = primaryText;
        this.secondaryText = secondaryText;
        this.accent = accent;
        this.button = button;
        this.icon = icon;
        this.glyph = glyph;
        this.divider = divider;
        this.border = border;
        this.statusBar = statusBar;
        this.navigation = navigation;
        this.link = link;
        this.error = error;
        this.destructive = destructive;
    }

    public IgThemePalette copy() {
        return new IgThemePalette(background, surface, primaryText, secondaryText, accent, button,
                icon, glyph, divider, border, statusBar, navigation, link, error, destructive);
    }

    public int[] slots() {
        return new int[]{background, surface, primaryText, secondaryText, accent, button, icon, glyph,
                divider, border, statusBar, navigation, link, error, destructive};
    }

    public int get(String key) {
        switch (key) {
            case SLOT_BACKGROUND: return background;
            case SLOT_SURFACE: return surface;
            case SLOT_PRIMARY_TEXT: return primaryText;
            case SLOT_SECONDARY_TEXT: return secondaryText;
            case SLOT_ACCENT: return accent;
            case SLOT_BUTTON: return button;
            case SLOT_ICON: return icon;
            case SLOT_GLYPH: return glyph;
            case SLOT_DIVIDER: return divider;
            case SLOT_BORDER: return border;
            case SLOT_STATUS_BAR: return statusBar;
            case SLOT_NAVIGATION: return navigation;
            case SLOT_LINK: return link;
            case SLOT_ERROR: return error;
            case SLOT_DESTRUCTIVE: return destructive;
            default: return 0;
        }
    }

    public void set(String key, int color) {
        switch (key) {
            case SLOT_BACKGROUND: background = color; break;
            case SLOT_SURFACE: surface = color; break;
            case SLOT_PRIMARY_TEXT: primaryText = color; break;
            case SLOT_SECONDARY_TEXT: secondaryText = color; break;
            case SLOT_ACCENT: accent = color; break;
            case SLOT_BUTTON: button = color; break;
            case SLOT_ICON: icon = color; break;
            case SLOT_GLYPH: glyph = color; break;
            case SLOT_DIVIDER: divider = color; break;
            case SLOT_BORDER: border = color; break;
            case SLOT_STATUS_BAR: statusBar = color; break;
            case SLOT_NAVIGATION: navigation = color; break;
            case SLOT_LINK: link = color; break;
            case SLOT_ERROR: error = color; break;
            case SLOT_DESTRUCTIVE: destructive = color; break;
            default: break;
        }
    }

    public int[] previewColors() {
        return new int[]{background, surface, accent, destructive};
    }

    public static void bindCardPreview(Context context, LinearLayout container, int[] colors) {
        container.removeAllViews();
        container.setBackground(null);
        float density = context.getResources().getDisplayMetrics().density;
        int gap = Math.round(5.0f * density);
        int corner = Math.round(10.0f * density);
        int height = Math.round(40.0f * density);
        int count = Math.min(colors.length, 4);
        for (int i = 0; i < count; i++) {
            View swatch = new View(context);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, height, 1.0f);
            if (i < count - 1) lp.setMarginEnd(gap);
            swatch.setLayoutParams(lp);
            GradientDrawable bg = new GradientDrawable();
            bg.setCornerRadius(corner);
            bg.setColor(colors[i]);
            swatch.setBackground(bg);
            container.addView(swatch);
        }
    }

    public String toJson() {
        JSONObject o = new JSONObject();
        try {
            o.put(SLOT_BACKGROUND, background);
            o.put(SLOT_SURFACE, surface);
            o.put(SLOT_PRIMARY_TEXT, primaryText);
            o.put(SLOT_SECONDARY_TEXT, secondaryText);
            o.put(SLOT_ACCENT, accent);
            o.put(SLOT_BUTTON, button);
            o.put(SLOT_ICON, icon);
            o.put(SLOT_GLYPH, glyph);
            o.put(SLOT_DIVIDER, divider);
            o.put(SLOT_BORDER, border);
            o.put(SLOT_STATUS_BAR, statusBar);
            o.put(SLOT_NAVIGATION, navigation);
            o.put(SLOT_LINK, link);
            o.put(SLOT_ERROR, error);
            o.put(SLOT_DESTRUCTIVE, destructive);
            return o.toString();
        } catch (JSONException e) {
            return "{}";
        }
    }

    public static IgThemePalette fromJson(String json) {
        IgThemePalette palette = ThemePresets.getById(1).palette.copy();
        if (json == null || json.isEmpty()) return palette;
        try {
            JSONObject o = new JSONObject(json);
            if (o.has(SLOT_BACKGROUND)) palette.background = o.getInt(SLOT_BACKGROUND);
            if (o.has(SLOT_SURFACE)) palette.surface = o.getInt(SLOT_SURFACE);
            if (o.has(SLOT_PRIMARY_TEXT)) palette.primaryText = o.getInt(SLOT_PRIMARY_TEXT);
            if (o.has(SLOT_SECONDARY_TEXT)) palette.secondaryText = o.getInt(SLOT_SECONDARY_TEXT);
            if (o.has(SLOT_ACCENT)) palette.accent = o.getInt(SLOT_ACCENT);
            if (o.has(SLOT_BUTTON)) palette.button = o.getInt(SLOT_BUTTON);
            else if (o.has(SLOT_ACCENT)) palette.button = o.getInt(SLOT_ACCENT);
            if (o.has(SLOT_ICON)) palette.icon = o.getInt(SLOT_ICON);
            if (o.has(SLOT_GLYPH)) palette.glyph = o.getInt(SLOT_GLYPH);
            else if (o.has(SLOT_ICON)) palette.glyph = o.getInt(SLOT_ICON);
            if (o.has(SLOT_DIVIDER)) palette.divider = o.getInt(SLOT_DIVIDER);
            if (o.has(SLOT_BORDER)) palette.border = o.getInt(SLOT_BORDER);
            else if (o.has(SLOT_DIVIDER)) palette.border = o.getInt(SLOT_DIVIDER);
            if (o.has(SLOT_STATUS_BAR)) palette.statusBar = o.getInt(SLOT_STATUS_BAR);
            else if (o.has(SLOT_NAVIGATION)) palette.statusBar = o.getInt(SLOT_NAVIGATION);
            if (o.has(SLOT_STATUS_BAR) && o.has(SLOT_NAVIGATION)) palette.navigation = o.getInt(SLOT_NAVIGATION);
            else if (o.has(SLOT_BACKGROUND)) palette.navigation = o.getInt(SLOT_BACKGROUND);
            else if (o.has(SLOT_NAVIGATION)) palette.navigation = o.getInt(SLOT_NAVIGATION);
            if (o.has(SLOT_LINK)) palette.link = o.getInt(SLOT_LINK);
            if (o.has(SLOT_ERROR)) palette.error = o.getInt(SLOT_ERROR);
            else if (o.has(SLOT_DESTRUCTIVE)) palette.error = o.getInt(SLOT_DESTRUCTIVE);
            if (o.has(SLOT_DESTRUCTIVE)) palette.destructive = o.getInt(SLOT_DESTRUCTIVE);
        } catch (JSONException ignored) {}
        return palette;
    }

    public static int withAlpha(int color, float alpha) {
        int a = Math.round(255.0f * alpha);
        return Color.argb(a, Color.red(color), Color.green(color), Color.blue(color));
    }
}
