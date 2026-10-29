package ps.reso.instaeclipse.mods.ui.theme;

import android.content.Context;
import android.content.res.Resources;
import android.util.SparseIntArray;
import android.util.TypedValue;

import java.util.Set;
import java.util.function.IntFunction;
import java.util.function.IntUnaryOperator;

import ps.reso.instaeclipse.utils.core.CommonUtils;
import ps.reso.instaeclipse.utils.feature.FeatureFlags;
import ps.reso.instaeclipse.utils.log.ModuleLog;

public final class IgThemeEngine {

    private static volatile IgThemePalette activePalette;
    private static volatile int[] activeSlots;
    private static volatile int night = -1;
    private static volatile SparseIntArray attrToSlot;
    private static volatile SparseIntArray colorResToSlot;
    private static volatile boolean initialized;

    private IgThemeEngine() {}

    private static volatile int paletteNight = -1;

    public static boolean isActive() {
        return themeEnabled() && !IgColorRemapEngine.isBypassing();
    }

    public static int accentOr(int fallback) {
        try {
            if (!themeEnabled()) return fallback;
            IgThemePalette p = getActivePalette();
            return p != null ? (p.get(IgThemePalette.SLOT_ACCENT) | 0xFF000000) : fallback;
        } catch (Throwable t) {
            return fallback;
        }
    }

    public static boolean themeEnabled() {
        if (!FeatureFlags.customThemeEnabled) return false;
        return FeatureFlags.themeLightPresetId != -1 || nightCached();
    }

    public static boolean isNightNow() {
        return isNight(null);
    }

    static boolean nightCached() {
        int n = night;
        if (n < 0) {
            refreshNight(null);
            n = night;
        }
        return n == 1;
    }

    static void refreshNight(Context context) {
        night = isNight(context) ? 1 : 0;
    }

    private static boolean isNight(Context context) {
        try {
            android.content.Context c = context != null ? context : ps.reso.instaeclipse.mods.ui.UIHookManager.getCurrentActivity();
            if (c == null) c = ps.reso.instaeclipse.xposedcompat.AndroidAppHelper.currentApplication();
            android.content.res.Configuration cfg = c != null
                    ? c.getResources().getConfiguration()
                    : Resources.getSystem().getConfiguration();
            return (cfg.uiMode & android.content.res.Configuration.UI_MODE_NIGHT_MASK)
                    == android.content.res.Configuration.UI_MODE_NIGHT_YES;
        } catch (Throwable t) {
            return true;
        }
    }

    public static boolean isInitialized() {
        return initialized;
    }

    public static IgThemePalette getActivePalette() {
        if (FeatureFlags.themeLightPresetId > 0) {
            int night = nightCached() ? 1 : 0;
            if (night != paletteNight) {
                synchronized (IgThemeEngine.class) {
                    if (night != paletteNight) {
                        boolean flipped = paletteNight != -1;
                        use(night == 1
                                ? resolvePalette()
                                : ThemePresets.getById(FeatureFlags.themeLightPresetId).palette.copy());
                        paletteNight = night;
                        if (flipped) IgColorRemapEngine.invalidate();
                    }
                }
            }
            return activePalette;
        }
        if (activePalette == null) {
            synchronized (IgThemeEngine.class) {
                if (activePalette == null) use(resolvePalette());
            }
        }
        return activePalette;
    }

    private static void use(IgThemePalette palette) {
        activeSlots = palette.slots();
        activePalette = palette;
    }

    static int slotColor(int slot) {
        IgThemePalette palette = getActivePalette();
        int[] slots = activeSlots;
        return slots != null ? slots[slot] : palette.slots()[slot];
    }

    public static void invalidate() {
        activeSlots = null;
        activePalette = null;
        paletteNight = -1;
        initialized = false;
        attrToSlot = null;
        colorResToSlot = null;
        IgColorRemapEngine.invalidate();
    }

    public static SparseIntArray getColorResToSlot() {
        return colorResToSlot;
    }

    public static IgThemePalette resolvePalette() {
        return ThemeSettingsHelper.resolveEffectivePalette();
    }

    public static void ensureInitialized(Context context) {
        if (context == null) return;
        ensureInitialized(context.getResources());
    }

    public static void ensureInitialized(Resources res) {
        if (res == null || initialized) return;
        synchronized (IgThemeEngine.class) {
            if (initialized) return;
            initMappings(res);
            initialized = true;
            ModuleLog.detail("(InstaEclipse | Theme): mapped " + attrToSlot.size() + " attrs, " + colorResToSlot.size() + " colors");
        }
    }

    public static Integer colorForAttr(int attrId) {
        if (!themeEnabled() || attrId == 0) return null;
        SparseIntArray map = attrToSlot;
        if (map == null) return null;
        int slotIndex = map.get(attrId, -1);
        if (slotIndex < 0) return null;
        return slotColor(slotIndex);
    }

    public static Integer colorForResource(int resId) {
        if (!themeEnabled() || resId == 0) return null;
        SparseIntArray map = colorResToSlot;
        if (map == null) return null;
        int slotIndex = map.get(resId, -1);
        if (slotIndex < 0) return null;
        Integer fix = IgColorRemapEngine.polarityFix(resId);
        if (fix != null) return fix;
        return slotColor(slotIndex);
    }

    static int themedColor(int resId, int resolved, IntFunction<Integer> byResource, IntUnaryOperator byValue) {
        return byResource.apply(resId) == null ? byValue.applyAsInt(resolved) : resolved;
    }

    public static void applyAttrOverride(int attrId, TypedValue out) {
        Integer override = colorForAttr(attrId);
        if (override == null || out == null) return;
        out.type = TypedValue.TYPE_INT_COLOR_ARGB8;
        out.data = override;
        out.resourceId = 0;
        out.assetCookie = 0;
        out.string = null;
    }

    private static void initMappings(Resources res) {
        SparseIntArray attrs = new SparseIntArray();
        SparseIntArray colors = new SparseIntArray();
        mapCoreAttrs(attrs, res);
        mapCoreColors(colors, res);
        attrToSlot = attrs;
        colorResToSlot = colors;
    }

    private static void mapCoreAttrs(SparseIntArray map, Resources res) {
        String[] names = {"colorBackground", "windowBackground", "navigationBarColor", "textColorPrimary", "textColorSecondary",
                "colorControlActivated", "colorControlNormal", "statusBarColor", "textColorLink"};
        for (String name : names) {
            int id = res.getIdentifier(name, "attr", "android");
            if (id != 0) {
                int slot = slotForAttrName(name);
                if (slot >= 0) map.put(id, slot);
            }
        }
    }

    private static void mapCoreColors(SparseIntArray map, Resources res) {
        String[] names = {"bds_black", "igds_prism_black", "bds_white", "igds_prism_gray_00", "bds_grey_0", "bds_grey_1",
                "bds_grey_2", "bds_grey_3", "bds_grey_4", "bds_grey_6", "bds_grey_7", "bds_grey_8", "bds_grey_9", "bds_grey_10",
                "bds_grey_11", "bds_grey_12", "bds_grey_16", "bds_grey_18", "bds_grey_21", "bds_grey_22", "bds_grey_24",
                "igds_prism_gray_08", "igds_prism_gray_10", "emphasized_action_color", "badge_color", "igds_prism_indigo_1000",
                "bds_blue_1", "bds_blue_2", "bds_red_5", "bds_red_6", "igds_primary_background", "bottom_sheet_undo_redo_color"};
        for (String name : names) mapColorByName(map, res, CommonUtils.IG_PACKAGE_NAME, name);
    }

    static int slotForAttrName(String name) {
        if (name == null) return -1;
        if (name.contains("primary_background") || name.contains("media_background") || name.contains("tab_bar_background")) return 0;
        if ((name.contains("clips_tab") && name.contains(IgThemePalette.SLOT_BACKGROUND)) || name.equals("status_bar_background")
                || name.contains("cta_banner") || name.equals("igdsPrimaryBackground") || name.contains("actionBarBackground")
                || name.contains("tabBarBackground") || name.contains("colorBackground") || name.contains("windowBackground")) return 0;
        if (name.contains("elevated") || name.contains("highlight_background") || name.contains("secondary_background")
                || name.contains("callout_background") || name.contains("form_field_background") || name.contains("banner_background")
                || (name.contains("creation") && name.contains(IgThemePalette.SLOT_BACKGROUND))) return 1;
        if (name.contains("primary_text") || name.equals("igdsPrimaryText") || name.contains("textColorPrimary")
                || name.contains("tabSelectedText") || name.contains("snackbar_text")) return 2;
        if (name.contains("secondary_text") || name.contains("textColorSecondary")) return 3;
        if (name.contains("glyphColor")) return 7;
        if (name.contains("primary_button") || name.contains("gradient_blue") || name.contains("colorControlActivated")
                || name.contains("data_visualization_primary") || name.contains("fbpay_focus") || name.contains("creation_tools_blue")) return 5;
        if (name.contains("emphasized") || name.contains(IgThemePalette.SLOT_ACCENT) || name.contains("cta")) return 4;
        if (name.contains("primary_icon") || name.contains("actionbar_drawable") || name.contains("clips_tab_bar_icon")
                || name.contains("colorControlNormal") || name.contains("tab_bar_icon")) return 6;
        if (name.contains("nav3_") && name.contains(IgThemePalette.SLOT_ICON)) return 6;
        if (name.contains(IgThemePalette.SLOT_DIVIDER) || name.contains("separator")) return 8;
        if (name.contains(IgThemePalette.SLOT_BORDER)) return 9;
        if (name.contains("stroke") && !name.contains(IgThemePalette.SLOT_DESTRUCTIVE)) return 9;
        if (name.contains("statusBarColor") || name.contains("status_bar")) return 10;
        if (name.contains("navigationBar") || name.contains("nav3_")) return 11;
        if (name.contains("text_link") || name.contains("link_text") || name.equals("igds_color_link") || name.contains("action_cell_emphasized")) return 12;
        if (name.contains(IgThemePalette.SLOT_DESTRUCTIVE)) return 14;
        if (name.contains("badge") && name.contains(IgThemePalette.SLOT_ICON)) return 14;
        if (name.contains("error") || name.contains("icon_badge")) return 13;
        return name.startsWith("igds_color_") ? 1 : -1;
    }

    private static final Set<String> SURFACE_GREYS = Set.of("bds_grey_7", "bds_grey_8", "bds_grey_9", "bds_grey_10",
            "bds_grey_12", "bds_grey_16", "bds_grey_21", "bds_grey_22", "bds_grey_24", "igds_prism_gray_08", "igds_prism_gray_10");
    private static final Set<String> SECONDARY_GREYS = Set.of("bds_grey_1", "bds_grey_2", "bds_grey_3", "bds_grey_4",
            "bds_grey_6", "bds_grey_11", "bds_grey_13", "bds_grey_18");

    static int slotForColorName(String name) {
        if (name == null) return -1;
        if (name.contains("primary_text") || name.contains("text_on_color") || name.contains("text_on_white")) return 2;
        if (name.contains("secondary_text") || name.contains("text_subtitle")) return 3;
        if (name.contains(IgThemePalette.SLOT_GLYPH)) return 7;
        if (name.contains("primary_icon") || name.contains("secondary_icon")) return 6;
        if (name.contains(IgThemePalette.SLOT_DESTRUCTIVE) || (name.contains("badge") && name.contains(IgThemePalette.SLOT_ICON))) return 14;
        if (name.contains("error")) return 13;
        if (name.contains(IgThemePalette.SLOT_LINK)) return 12;
        if (name.contains("primary_button") || name.contains("bds_blue") || name.equals("emphasized_action_color")
                || name.equals("badge_color")) return 5;
        if (name.contains("indigo") || name.contains("blue") || name.contains("emphasized") || name.contains("gradient")
                || name.contains(IgThemePalette.SLOT_ACCENT) || name.contains("cta")) return 4;
        if (name.contains(IgThemePalette.SLOT_DIVIDER) || name.contains("separator")) return 8;
        if (name.contains(IgThemePalette.SLOT_BORDER) || name.contains("stroke")) return 9;
        if (name.contains("status_bar")) return 10;
        if (name.contains("nav")) return 11;
        if (name.contains("black") || name.contains("primary_background") || name.contains("media_background")) return 0;
        if (SURFACE_GREYS.contains(name) || name.contains("elevated") || name.contains("highlight")
                || name.contains(IgThemePalette.SLOT_SURFACE)) return 1;
        if (name.equals("bds_grey_0") || name.equals("igds_prism_gray_00") || name.contains("white")
                || name.equals("bottom_sheet_undo_redo_color")) return 2;
        if (SECONDARY_GREYS.contains(name) || name.contains("secondary")) return 3;
        if (name.contains(IgThemePalette.SLOT_ICON)) return 6;
        if (name.contains("red") && (name.contains("5") || name.contains("6") || name.contains(IgThemePalette.SLOT_DESTRUCTIVE))) return 14;
        return (name.startsWith("bds_") || name.startsWith("igds_prism_") || name.startsWith("igds_")) ? 1 : -1;
    }

    private static void mapColorByName(SparseIntArray map, Resources res, String pkg, String name) {
        int id = res.getIdentifier(name, "color", pkg);
        if (id != 0) {
            int slot = slotForColorName(name);
            if (slot >= 0) map.put(id, slot);
        }
    }
}
