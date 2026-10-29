package ps.reso.instaeclipse.mods.ui.theme;

import android.content.Context;
import android.content.res.Resources;
import android.util.SparseIntArray;

import ps.reso.instaeclipse.utils.log.ModuleLog;

public final class IgColorRemapEngine {

    private static final ThreadLocal<Integer> BYPASS_DEPTH = ThreadLocal.withInitial(() -> 0);
    private static final int CACHE_MISS = Integer.MIN_VALUE;
    private static final int FUZZY_CACHE_LIMIT = 1024;

    private static volatile boolean built;
    private static volatile SparseIntArray exactTable;
    private static volatile SparseIntArray rgbTable;
    private static volatile FuzzyCache fuzzyCache;
    private static volatile SparseIntArray resPolarityFix;

    private static volatile int fuzzyBg, fuzzySurface, fuzzyPrimary, fuzzySecondary, fuzzyButton, fuzzyLink, fuzzyDestructive;
    private static volatile boolean fuzzyDarkTheme;

    private IgColorRemapEngine() {}

    public static boolean isBypassing() {
        return BYPASS_DEPTH.get() > 0;
    }

    private static void enterBypass() {
        BYPASS_DEPTH.set(BYPASS_DEPTH.get() + 1);
    }

    private static void exitBypass() {
        BYPASS_DEPTH.set(BYPASS_DEPTH.get() - 1);
    }

    public static int sampleColor(Resources res, int resId) {
        if (res == null || resId == 0) return 0;
        enterBypass();
        try {
            return res.getColor(resId, null);
        } catch (Throwable th) {
            return 0;
        } finally {
            exitBypass();
        }
    }

    private static volatile int generation;

    public static int generation() {
        return generation;
    }

    public static void invalidate() {
        generation++;
        built = false;
        exactTable = null;
        rgbTable = null;
        fuzzyCache = null;
        resPolarityFix = null;
    }

    public static Integer polarityFix(int resId) {
        SparseIntArray fix = resPolarityFix;
        if (fix == null) return null;
        int v = fix.get(resId, CACHE_MISS);
        return v == CACHE_MISS ? null : v;
    }

    public static boolean isReady() {
        return built && rgbTable != null;
    }

    public static void ensureBuilt(Context context) {
        if (built || context == null || !IgThemeEngine.isActive()) return;
        synchronized (IgColorRemapEngine.class) {
            if (built) return;
            buildTable(context);
            built = true;
            int size = (rgbTable != null ? rgbTable.size() : 0) + (exactTable != null ? exactTable.size() : 0);
            ModuleLog.detail("(InstaEclipse | Theme): color remap table size=" + size);
        }
    }

    public static int remap(int color) {
        if (!IgThemeEngine.isActive() || isBypassing() || color == 0) return color;
        SparseIntArray exact = exactTable;
        SparseIntArray rgb = rgbTable;
        if (rgb == null) return color;
        if (exact != null) {
            int exactHit = exact.get(color, CACHE_MISS);
            if (exactHit != CACHE_MISS) return exactHit;
        }
        int mappedRgb = rgb.get(color & 0x00FFFFFF, CACHE_MISS);
        if (mappedRgb != CACHE_MISS) {
            return (0xFF000000 & color) | (0x00FFFFFF & mappedRgb);
        }
        return cachedFuzzy(fuzzyCache, color);
    }

    static int cachedFuzzy(FuzzyCache fuzzy, int color) {
        if (fuzzy != null) {
            int cached = fuzzy.get(color, CACHE_MISS);
            if (cached != CACHE_MISS) return cached;
        }
        int result = remapFuzzy(color);
        if (fuzzy != null) fuzzy.put(color, result);
        return result;
    }

    static int remapFuzzy(int color) {
        int alpha = (color >>> 24) & 0xFF;
        if (alpha == 0) return color;
        int r = (color >> 16) & 0xFF;
        int g = (color >> 8) & 0xFF;
        int b = color & 0xFF;
        double lum = ((r * 0.299d) + (g * 0.587d) + (b * 0.114d)) / 255.0d;
        int target;
        if (isAccentBlue(r, g, b)) target = fuzzyButton;
        else if (isDestructiveRed(r, g, b)) target = fuzzyDestructive;
        else if (isLinkBlue(r, g, b)) target = fuzzyLink;
        else if (fuzzyDarkTheme) {
            if (lum < 0.08d) target = fuzzyBg;
            else if (lum < 0.22d) target = fuzzySurface;
            else if (lum > 0.65d) target = fuzzyPrimary;
            else target = fuzzySecondary;
        } else if (lum > 0.92d) target = fuzzyBg;
        else if (lum > 0.75d) target = fuzzySurface;
        else if (lum < 0.25d) target = fuzzyPrimary;
        else target = fuzzySecondary;
        return (alpha << 24) | (0x00FFFFFF & target);
    }

    static void cacheFuzzyPalette(IgThemePalette palette) {
        fuzzyBg = palette.background;
        fuzzySurface = palette.surface;
        fuzzyPrimary = palette.primaryText;
        fuzzySecondary = palette.secondaryText;
        fuzzyButton = palette.button;
        fuzzyLink = palette.link;
        fuzzyDestructive = palette.destructive;
        int bg = palette.background;
        int r = (bg >> 16) & 0xFF, g = (bg >> 8) & 0xFF, b = bg & 0xFF;
        fuzzyDarkTheme = ((r * 0.299d) + (g * 0.587d) + (b * 0.114d)) / 255.0d < 0.2d;
    }

    private static boolean isAccentBlue(int r, int g, int b) {
        return b > 160 && b > r + 30 && b > g + 10 && b - Math.min(r, g) > 60;
    }

    private static boolean isLinkBlue(int r, int g, int b) {
        return b > 120 && b >= r && g < b && b - Math.min(r, g) > 60;
    }

    private static boolean isDestructiveRed(int r, int g, int b) {
        return r > 180 && r > g + 60 && r > b + 60;
    }

    private static void buildTable(Context context) {
        SparseIntArray exact = new SparseIntArray(128);
        SparseIntArray rgb = new SparseIntArray(512);
        IgThemePalette palette = IgThemeEngine.getActivePalette();
        cacheFuzzyPalette(palette);
        Resources res = context.getResources();
        mapCanonical(exact, rgb, palette);
        mapFromSlots(exact, rgb, res, palette);
        if (!fuzzyDarkTheme && !IgThemeEngine.nightCached()) {
            keepLightPolarity(exact, palette);
            keepLightPolarity(rgb, palette);
            resPolarityFix = buildResourcePolarityFix(res, palette);
        }
        SparseIntArray own = new SparseIntArray(IgThemePalette.SLOT_KEYS.length);
        for (String key : IgThemePalette.SLOT_KEYS) {
            int v = palette.get(key) & 0x00FFFFFF;
            own.put(v, v);
            rgb.put(v, v);
        }
        for (int i = exact.size() - 1; i >= 0; i--) {
            if (own.indexOfKey(exact.keyAt(i) & 0x00FFFFFF) >= 0) exact.removeAt(i);
        }
        exactTable = exact;
        rgbTable = rgb;
        fuzzyCache = new FuzzyCache(FUZZY_CACHE_LIMIT);
    }

    static final class FuzzyCache {
        private final int[] keys;
        private final int[] values;
        private final int mask;
        private final int limit;
        private int size;

        FuzzyCache(int limit) {
            this.limit = limit;
            int capacity = Integer.highestOneBit(Math.max(1, limit) * 2 - 1) << 1;
            keys = new int[capacity];
            values = new int[capacity];
            mask = capacity - 1;
        }

        synchronized int get(int key, int miss) {
            if (key == 0) return miss;
            for (int i = index(key); ; i = (i + 1) & mask) {
                int k = keys[i];
                if (k == key) return values[i];
                if (k == 0) return miss;
            }
        }

        synchronized void put(int key, int value) {
            if (key == 0) return;
            for (int i = index(key); ; i = (i + 1) & mask) {
                int k = keys[i];
                if (k == key) {
                    values[i] = value;
                    return;
                }
                if (k == 0) {
                    if (size >= limit) return;
                    keys[i] = key;
                    values[i] = value;
                    size++;
                    return;
                }
            }
        }

        synchronized int size() {
            return size;
        }

        private int index(int key) {
            int h = key * 0x9E3779B9;
            return (h ^ (h >>> 16)) & mask;
        }
    }

    static final Object[][] CANONICAL = {
            {0xFF000000, IgThemePalette.SLOT_BACKGROUND},
            {0xFF0C1014, IgThemePalette.SLOT_BACKGROUND},
            {0xFF1A1A1A, IgThemePalette.SLOT_SURFACE},
            {0xFF1E1E1E, IgThemePalette.SLOT_SURFACE},
            {0xFF262626, IgThemePalette.SLOT_SURFACE},
            {0xFF363636, IgThemePalette.SLOT_DIVIDER},
            {0xFF121212, IgThemePalette.SLOT_BACKGROUND},
            {0xFF141414, IgThemePalette.SLOT_SURFACE},
            {0xFF212328, IgThemePalette.SLOT_SURFACE},
            {0xFF32353E, IgThemePalette.SLOT_SURFACE},
            {0xFFF5F5F5, IgThemePalette.SLOT_PRIMARY_TEXT},
            {0xFFEFEFEF, IgThemePalette.SLOT_SECONDARY_TEXT},
            {0xFFDBDBDB, IgThemePalette.SLOT_SECONDARY_TEXT},
            {0xFFC7C7C7, IgThemePalette.SLOT_SECONDARY_TEXT},
            {0xFFA8A8A8, IgThemePalette.SLOT_SECONDARY_TEXT},
            {0xFF737373, IgThemePalette.SLOT_SECONDARY_TEXT},
            {0xFF555555, IgThemePalette.SLOT_SECONDARY_TEXT},
            {0xFFBCC0C4, IgThemePalette.SLOT_SECONDARY_TEXT},
            {0xFFFFFFFF, IgThemePalette.SLOT_PRIMARY_TEXT},
            {0xFF0095F6, IgThemePalette.SLOT_BUTTON},
            {0xFF1877F2, IgThemePalette.SLOT_BUTTON},
            {0xFF4A5DF9, IgThemePalette.SLOT_BUTTON},
            {0xFFED4956, IgThemePalette.SLOT_DESTRUCTIVE},
            {0xFFFF3040, IgThemePalette.SLOT_DESTRUCTIVE},
            {0xFFFCAF17, IgThemePalette.SLOT_ACCENT},
            {0xFF1C1D22, IgThemePalette.SLOT_SURFACE},
            {0xFF233038, IgThemePalette.SLOT_SURFACE},
            {0xFFEBF7FE, IgThemePalette.SLOT_SURFACE},
            {0xFFF3F5F7, IgThemePalette.SLOT_BACKGROUND},
            {0xFFFAFAFA, IgThemePalette.SLOT_PRIMARY_TEXT},
            {0xFFE0E0E0, IgThemePalette.SLOT_SECONDARY_TEXT},
            {0xFF757575, IgThemePalette.SLOT_SECONDARY_TEXT},
            {0xFF9E9E9E, IgThemePalette.SLOT_SECONDARY_TEXT},
            {0xFF424242, IgThemePalette.SLOT_BORDER},
            {0xFF2C2C2C, IgThemePalette.SLOT_BORDER},
            {0xFF383838, IgThemePalette.SLOT_BORDER},
            {0xFF54CEFF, IgThemePalette.SLOT_LINK},
            {0xFF00376B, IgThemePalette.SLOT_LINK},
            {0xFF58C322, IgThemePalette.SLOT_ACCENT},
            {0xFFFFDB70, IgThemePalette.SLOT_ACCENT},
            {0x80FFFFFF, IgThemePalette.SLOT_PRIMARY_TEXT, 0.5f},
            {0x80F5F5F5, IgThemePalette.SLOT_PRIMARY_TEXT, 0.5f},
            {0x80000000, IgThemePalette.SLOT_BACKGROUND, 0.5f},
            {0xCC000000, IgThemePalette.SLOT_BACKGROUND, 0.8f},
            {0x33000000, IgThemePalette.SLOT_BACKGROUND, 0.2f},
            {0x33FFFFFF, IgThemePalette.SLOT_PRIMARY_TEXT, 0.2f},
            {0xFF8E8E8E, IgThemePalette.SLOT_SECONDARY_TEXT},
            {0xFF999999, IgThemePalette.SLOT_SECONDARY_TEXT},
    };

    private static void mapCanonical(SparseIntArray exact, SparseIntArray rgb, IgThemePalette palette) {
        for (Object[] row : CANONICAL) {
            int to = palette.get((String) row[1]);
            if (row.length > 2) to = IgThemePalette.withAlpha(to, (Float) row[2]);
            put(exact, rgb, (Integer) row[0], to);
        }
    }

    private static void mapFromSlots(SparseIntArray exact, SparseIntArray rgb, Resources res, IgThemePalette palette) {
        SparseIntArray colorResToSlot = IgThemeEngine.getColorResToSlot();
        if (colorResToSlot == null) return;
        for (int i = 0; i < colorResToSlot.size(); i++) {
            int resId = colorResToSlot.keyAt(i);
            int slot = colorResToSlot.valueAt(i);
            int original = sampleColor(res, resId);
            if (original != 0) put(exact, rgb, original, palette.get(IgThemePalette.SLOT_KEYS[slot]));
        }
    }

    private static void keepLightPolarity(SparseIntArray table, IgThemePalette palette) {
        for (int i = 0; i < table.size(); i++) {
            int to = table.valueAt(i);
            int fixed = lightPolarity(table.keyAt(i), to, palette);
            if (fixed != to) table.put(table.keyAt(i), (to & 0xFF000000) == 0 ? (0x00FFFFFF & fixed)
                    : ((to & 0xFF000000) | (0x00FFFFFF & fixed)));
        }
    }

    private static SparseIntArray buildResourcePolarityFix(Resources res, IgThemePalette palette) {
        SparseIntArray out = new SparseIntArray(64);
        SparseIntArray colorResToSlot = IgThemeEngine.getColorResToSlot();
        if (colorResToSlot == null) return out;
        for (int i = 0; i < colorResToSlot.size(); i++) {
            int resId = colorResToSlot.keyAt(i);
            int original = sampleColor(res, resId);
            if (original == 0) continue;
            int to = palette.get(IgThemePalette.SLOT_KEYS[colorResToSlot.valueAt(i)]);
            int fixed = lightPolarity(original, to, palette);
            if (fixed != to) out.put(resId, (to & 0xFF000000) | (0x00FFFFFF & fixed));
        }
        return out;
    }

    private static int lightPolarity(int from, int to, IgThemePalette palette) {
        if (!isNeutral(from)) return to;
        double lf = lum(from), lt = lum(to);
        boolean flips = (isStrongColor(to) && lf > 0.6d)
                || (lf < 0.6d && lt > 0.75d)
                || (lf > 0.8d && lt < 0.4d);
        if (!flips) return to;
        if (lf > 0.8d) return lf > 0.97d ? palette.background : palette.surface;
        return lf < 0.25d ? palette.primaryText : palette.secondaryText;
    }

    private static boolean isStrongColor(int c) {
        int r = (c >> 16) & 0xFF, g = (c >> 8) & 0xFF, b = c & 0xFF;
        return Math.max(r, Math.max(g, b)) - Math.min(r, Math.min(g, b)) > 70;
    }

    private static boolean isNeutral(int c) {
        int r = (c >> 16) & 0xFF, g = (c >> 8) & 0xFF, b = c & 0xFF;
        return Math.max(r, Math.max(g, b)) - Math.min(r, Math.min(g, b)) < 28;
    }

    private static double lum(int c) {
        return (((c >> 16) & 0xFF) * 0.299d + ((c >> 8) & 0xFF) * 0.587d + (c & 0xFF) * 0.114d) / 255.0d;
    }

    private static void put(SparseIntArray exact, SparseIntArray rgb, int from, int to) {
        if (from == 0 || to == 0) return;
        int fromAlpha = (from >>> 24) & 0xFF;
        int toAlpha = (to >>> 24) & 0xFF;
        if (fromAlpha != 255 || toAlpha != 255) exact.put(from, to);
        rgb.put(from & 0x00FFFFFF, 0x00FFFFFF & to);
    }
}
