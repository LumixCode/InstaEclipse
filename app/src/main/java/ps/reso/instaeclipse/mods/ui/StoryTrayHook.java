package ps.reso.instaeclipse.mods.ui;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.graphics.Color;
import android.view.View;
import android.view.ViewGroup;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.WeakHashMap;
import java.util.regex.Pattern;

import ps.reso.instaeclipse.xposedcompat.XC_MethodHook;
import ps.reso.instaeclipse.xposedcompat.XposedBridge;
import ps.reso.instaeclipse.utils.feature.FeatureFlags;
import ps.reso.instaeclipse.utils.feature.FeatureStatusTracker;
import ps.reso.instaeclipse.utils.log.ModuleLog;

/**
 * Story tray (#283): a smaller tray, so more stories fit in a row, and a custom color for the ring
 * around unseen stories.
 *
 * <p>Size: the tray (com.instagram.android:id/reels_tray_container) holds the horizontal list of
 * stories. That list is scaled from its top left and widened by the same factor, so it still spans
 * the screen and shows more items; the container's height shrinks to match, so the feed moves up.
 * Android maps touches through the scale, so taps land on the right story.
 *
 * <p>Ring color: every story ring gradient is built by GradientSpinner's static
 * (GradientSpinner, float[], float[], float[], int[] colors, int, int) -> LinearGradient; the colors
 * are swapped there. Close Friends' green ring is left alone, so it keeps its meaning.
 */
public final class StoryTrayHook {

    private static final String SPINNER = "com.instagram.ui.widget.gradientspinner.GradientSpinner";
    private static final String TAG = "(IE|StoryTray) ";
    private static final int ORIGINAL_TAG = 0x7e1e0283;
    private static final Pattern HEX_COLOR = Pattern.compile("#?[0-9A-Fa-f]{6}");

    private static int trayId;
    private static final Set<View> watched = Collections.newSetFromMap(new WeakHashMap<>());
    private static final Set<View> pending = Collections.newSetFromMap(new WeakHashMap<>());
    private static final Set<View> scaled = Collections.newSetFromMap(new WeakHashMap<>());
    private static volatile Memo ringMemo = new Memo(null, 0, null);
    private static volatile Memo spreadMemo = new Memo(null, 0, null);

    private static final class Main {
        static final android.os.Handler HANDLER = new android.os.Handler(android.os.Looper.getMainLooper());
    }

    private static final class Memo {
        final Object key;
        final int count;
        final int[] value;

        Memo(Object key, int count, int[] value) {
            this.key = key;
            this.count = count;
            this.value = value;
        }
    }

    private static final class Original {
        final int height;
        final int listWidth;
        final int listHeight;
        final int containerHeight;
        final boolean clipChildren;

        Original(int height, int listWidth, int listHeight, int containerHeight, boolean clipChildren) {
            this.height = height;
            this.listWidth = listWidth;
            this.listHeight = listHeight;
            this.containerHeight = containerHeight;
            this.clipChildren = clipChildren;
        }
    }

    private StoryTrayHook() {}

    public static void install(ClassLoader cl) {
        try {
            Class<?> spinner = cl.loadClass(SPINNER);
            int hooked = 0;
            for (Method m : ps.reso.instaeclipse.utils.core.SafeReflect.methods(spinner)) {
                if (!Modifier.isStatic(m.getModifiers()) || m.getReturnType() != android.graphics.LinearGradient.class) continue;
                int colorsArg = -1;
                Class<?>[] p = m.getParameterTypes();
                for (int i = 0; i < p.length; i++) if (p[i] == int[].class) colorsArg = i;
                if (colorsArg < 0) continue;
                final int arg = colorsArg;
                XposedBridge.hookMethod(m, new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) {
                        int[] custom = ringColors(FeatureFlags.storyRingColor);
                        if (custom == null || !(param.args[arg] instanceof int[] colors) || colors.length == 0) return;
                        if (isCloseFriends(colors)) return;
                        param.args[arg] = ringSpread(custom, colors.length);
                    }
                });
                hooked++;
            }
            if (hooked > 0) ModuleLog.ok(TAG + "story ring color ready");
            else ModuleLog.error(TAG + "Story ring color will not work: the ring drawing code was not found in this Instagram version");
            ModuleLog.detail(TAG + "ring gradient hooks=" + hooked);
            if (hooked > 0) FeatureStatusTracker.setHooked("StoryRingColor");
        } catch (Throwable t) {
            ModuleLog.error(TAG + "Story ring color will not work: setup failed", t);
        }
    }

    // ---- ring color ----

    /** "#RRGGBB", or 2 to 4 of them for a gradient; null for Instagram's own colors or invalid input. */
    public static int[] parseColors(String value) {
        if (value == null || value.trim().isEmpty()) return null;
        String[] parts = value.split(",");
        if (parts.length > 4) return null;
        int[] out = new int[parts.length];
        for (int i = 0; i < parts.length; i++) {
            String p = parts[i].trim();
            if (!HEX_COLOR.matcher(p).matches()) return null;
            out[i] = 0xFF000000 | Integer.parseInt(p.startsWith("#") ? p.substring(1) : p, 16);
        }
        return out;
    }

    static int[] ringColors(String value) {
        Memo memo = ringMemo;
        if (memo.key == value) return memo.value;
        int[] colors = parseColors(value);
        ringMemo = new Memo(value, 0, colors);
        return colors;
    }

    static int[] ringSpread(int[] custom, int count) {
        Memo memo = spreadMemo;
        if (memo.key == custom && memo.count == count) return memo.value;
        int[] out = spread(custom, count);
        spreadMemo = new Memo(custom, count, out);
        return out;
    }

    /** {@code count} colors running through the custom colors in order. */
    static int[] spread(int[] custom, int count) {
        int[] out = new int[count];
        for (int i = 0; i < count; i++) {
            if (custom.length == 1 || count == 1) { out[i] = custom[0]; continue; }
            float pos = (float) i / (count - 1) * (custom.length - 1);
            int from = Math.min((int) pos, custom.length - 2);
            out[i] = blend(custom[from], custom[from + 1], pos - from);
        }
        return out;
    }

    private static int blend(int a, int b, float f) {
        int r = Math.round(Color.red(a) + (Color.red(b) - Color.red(a)) * f);
        int g = Math.round(Color.green(a) + (Color.green(b) - Color.green(a)) * f);
        int bl = Math.round(Color.blue(a) + (Color.blue(b) - Color.blue(a)) * f);
        return 0xFF000000 | (r << 16) | (g << 8) | bl;
    }

    /** Close Friends' ring: every color a clear green. */
    private static boolean isCloseFriends(int[] colors) {
        float[] hsv = new float[3];
        for (int c : colors) {
            Color.colorToHSV(c, hsv);
            if (hsv[0] < 80 || hsv[0] > 170 || hsv[1] < 0.3f) return false;
        }
        return true;
    }

    // ---- tray size ----

    /** Called on every main / modal resume (UIHookManager); keeps the tray sized while it lays out. */
    public static void watchActivity(Activity a) {
        if (a == null) return;
        try {
            if (trayId == 0) {
                @SuppressLint("DiscouragedApi")
                int id = a.getResources().getIdentifier("reels_tray_container", "id", a.getPackageName());
                trayId = id;
            }
            if (trayId == 0) return;
            View decor = a.getWindow() != null ? a.getWindow().getDecorView() : null;
            if (decor == null) return;
            if (needsWalk()) apply(decor);
            if (!watched.add(decor)) return;
            decor.getViewTreeObserver().addOnGlobalLayoutListener(() -> {
                try {
                    if (!needsWalk() || !pending.add(decor)) return;
                    Main.HANDLER.postDelayed(() -> {
                        pending.remove(decor);
                        try { apply(decor); } catch (Throwable t) { ModuleLog.warn(TAG + "Could not resize the story tray: " + t.getClass().getSimpleName() + ": " + t.getMessage()); }
                    }, 150);
                } catch (Throwable t) {
                    ModuleLog.warn(TAG + "Could not resize the story tray: " + t.getClass().getSimpleName() + ": " + t.getMessage());
                }
            });
        } catch (Throwable t) {
            ModuleLog.warn(TAG + "Could not resize the story tray: " + t.getClass().getSimpleName() + ": " + t.getMessage());
        }
    }

    /**
     * Layout fires many times per second on the feed, so the window is only walked while a size is
     * set, or to put back a tray scaled before the size went back to 100 (no tree walk to find out).
     */
    private static boolean needsWalk() {
        return FeatureFlags.storyTrayScale < 100 || !scaled.isEmpty();
    }

    private static void apply(View decor) {
        List<View> trays = new ArrayList<>();
        collect(decor, trays);
        for (View tray : trays) scale((ViewGroup) tray, FeatureFlags.storyTrayScale);
    }

    private static void collect(View v, List<View> out) {
        if (v.getId() == trayId && v instanceof ViewGroup) { out.add(v); return; }
        if (v instanceof ViewGroup g) for (int i = 0; i < g.getChildCount(); i++) collect(g.getChildAt(i), out);
    }

    /** Scales the tray's list to {@code percent} of its size; 100 puts Instagram's layout back. */
    static void scale(ViewGroup container, int percent) {
        if (container.getChildCount() == 0) return;
        View list = container.getChildAt(0);
        Original saved = list.getTag(ORIGINAL_TAG) instanceof Original o ? o : null;
        if (percent >= 100 || percent < 30) {
            if (saved == null) return;
            list.setScaleX(1f);
            list.setScaleY(1f);
            ViewGroup.LayoutParams lp = list.getLayoutParams();
            lp.width = saved.listWidth;
            lp.height = saved.listHeight;
            list.setLayoutParams(lp);
            ViewGroup.LayoutParams clp = container.getLayoutParams();
            clp.height = saved.containerHeight;
            container.setLayoutParams(clp);
            container.setClipChildren(saved.clipChildren);
            list.setTag(ORIGINAL_TAG, null);
            scaled.remove(list);
            return;
        }
        int height = saved != null ? saved.height : list.getHeight();
        int width = container.getWidth();
        if (height <= 0 || width <= 0) return;
        float s = percent / 100f;
        int wantWidth = Math.round(width / s), wantHeight = Math.round(height * s);
        ViewGroup.LayoutParams lp = list.getLayoutParams();
        ViewGroup.LayoutParams clp = container.getLayoutParams();
        if (saved != null && list.getScaleX() == s && lp.width == wantWidth && clp.height == wantHeight) return;
        if (saved == null) {
            list.setTag(ORIGINAL_TAG, new Original(height, lp.width, lp.height, clp.height, container.getClipChildren()));
        }
        scaled.add(list);
        list.setPivotX(0f);
        list.setPivotY(0f);
        list.setScaleX(s);
        list.setScaleY(s);
        lp.width = wantWidth;
        lp.height = height;
        list.setLayoutParams(lp);
        clp.height = wantHeight;
        container.setLayoutParams(clp);
        container.setClipChildren(true);
        FeatureStatusTracker.setHooked("StoryTraySize");
    }
}
