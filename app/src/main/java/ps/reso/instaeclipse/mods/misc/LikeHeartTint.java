package ps.reso.instaeclipse.mods.misc;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.graphics.ColorMatrixColorFilter;
import android.graphics.Paint;
import android.graphics.RenderEffect;
import android.graphics.RuntimeShader;
import android.os.Build;
import android.os.SystemClock;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;

import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;

import ps.reso.instaeclipse.utils.core.JitGuard;
import ps.reso.instaeclipse.utils.feature.FeatureFlags;
import ps.reso.instaeclipse.utils.log.ModuleLog;
import ps.reso.instaeclipse.xposedcompat.XC_MethodHook;
import ps.reso.instaeclipse.xposedcompat.XposedBridge;

/**
 * The blue heart of a like {@link LikeUndoHook} holds: blue from the hold until the like is sent,
 * when Instagram's own red shows, or taken back.
 *
 * <p>The heart is found from where the like came from: a heart view (by id, or by Instagram's
 * like/unlike label) under the tap, or for a double tap the first one below it; else the view the
 * tap landed on; else, for a double tap, the spot under the media where the feed draws its heart.
 * That covers hearts drawn without a view of their own (Litho and Compose rows).
 *
 * <p>It is recolored where it is drawn, never in Instagram's state: on Android 13+ a RenderEffect
 * shader turns only Instagram's like red (anti-aliased edges included) blue, in the heart's view
 * or a circle around it; before that a color filter does so on a heart-sized view. Removing the
 * effect, or recycling the view (detached: the effect ends), brings Instagram's own drawing back.
 */
final class LikeHeartTint implements View.OnAttachStateChangeListener {
    private static final String TAG = "(InstaEclipse | LikeUndo): ";
    /** Post like buttons (feed, carousel slide, reels, the reel viewer). */
    private static final String[] HEART_IDS = {"row_feed_button_like", "row_feed_media_per_media_like_button",
            "clips_ufi_like_button", "like_button"};
    /** Instagram's accessibility labels of a like button, by resource name. */
    private static final String[] HEART_LABELS = {"like", "unlike", "liked"};
    /** A touch older than this when the like comes did not cause it. */
    private static final long TOUCH_MAX_AGE_MS = 1500;
    private static final int HEART_MAX_DP = 96, SMALL_DP = 72, SPOT_RADIUS_DP = 36, SLOP_DP = 12;

    /** Leaves everything but Instagram's like red (and blends of it with a grey) as it is. */
    private static final String SHADER = ""
            + "uniform shader content;\n"
            + "uniform float3 likeRed;\n"
            + "uniform float3 likeBlue;\n"
            + "uniform float3 spot;\n"
            + "half4 main(float2 coord) {\n"
            + "  half4 c = content.eval(coord);\n"
            + "  if (c.a < 0.01 || (spot.z > 0.0 && distance(coord, spot.xy) > spot.z)) return c;\n"
            + "  float3 rgb = float3(c.rgb) / float(c.a);\n"
            + "  float chroma = rgb.r - rgb.g;\n"
            + "  float full = likeRed.r - likeRed.g;\n"
            + "  if (chroma < 0.08) return c;\n"
            + "  if (abs((rgb.b - rgb.g) / chroma - (likeRed.b - likeRed.g) / full) > 0.15) return c;\n"
            + "  rgb = clamp(rgb + min(chroma / full, 1.0) * (likeBlue - likeRed), 0.0, 1.0);\n"
            + "  return half4(half3(rgb * float(c.a)), c.a);\n"
            + "}\n";

    // The last touch in an Instagram activity (raw screen position, uptime), the last two presses
    // and the lift before the last press, recorded while the undo window or Ask before liking is
    // on. Read without a lock: a torn read only misplaces one tint.
    private static volatile float touchX, touchY, downX, downY, prevDownX, prevDownY;
    private static volatile long touchTime, downTime, upTime, prevUpTime;
    private static volatile WeakReference<Activity> touchActivity = new WeakReference<>(null);
    private static boolean tracking;

    private static final Map<View, LikeHeartTint> tinted = new WeakHashMap<>(); // main thread
    private static Set<Integer> heartIds;
    private static Set<String> heartLabels;

    private final WeakReference<View> view;
    private final String how;
    private boolean layer, done;
    private int layerType;

    private LikeHeartTint(View view, String how) {
        this.view = new WeakReference<>(view);
        this.how = how;
    }

    /** Where a like came from: the touch just before it, and whether that was a double tap. */
    static final class Origin {
        final boolean touched, doubleTap;
        final float x, y;
        final WeakReference<Activity> activity;
        final WeakReference<View> heart;

        private Origin(boolean touched, boolean doubleTap, float x, float y, Activity activity, View heart) {
            this.touched = touched;
            this.doubleTap = doubleTap;
            this.x = x;
            this.y = y;
            this.activity = new WeakReference<>(activity);
            this.heart = new WeakReference<>(heart);
        }

        /** Now, on the thread Instagram likes on; {@code heart} is the heart view itself when known. */
        static Origin capture(View heart) {
            long time = touchTime;
            long age = SystemClock.uptimeMillis() - time;
            boolean touched = time != 0 && age >= 0 && age <= TOUCH_MAX_AGE_MS;
            // GestureDetector's callback on the stack, or (Compose, a like sent off that thread)
            // the last two presses: one quick double tap.
            boolean doubleTap = doubleTapOnStack() || (touched && LikeHeartPolicy.isDoubleTap(
                    prevUpTime, prevDownX, prevDownY, downTime, downX, downY,
                    android.view.ViewConfiguration.getDoubleTapTimeout(),
                    100 * android.content.res.Resources.getSystem().getDisplayMetrics().density));
            return new Origin(touched, doubleTap, touchX, touchY, touchActivity.get(), heart);
        }

        /**
         * The activity the like's touch landed in while it is still there, else {@code fallback}.
         * Posts and reels opened from a chat run in their own activity above the main one.
         */
        Activity activityOr(Activity fallback) {
            Activity touchedIn = touched ? activity.get() : null;
            return touchedIn != null && !touchedIn.isFinishing() && !touchedIn.isDestroyed() ? touchedIn : fallback;
        }
    }

    /** Records touches from now on (the Activity method every Instagram screen passes them to). */
    static synchronized void trackTouches() {
        if (tracking) return;
        tracking = true;
        try {
            XposedBridge.hookMethod(Activity.class.getMethod("dispatchTouchEvent", MotionEvent.class), new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    JitGuard.tick(param.method);
                    if (!(FeatureFlags.likeUndo || FeatureFlags.confirmLikes)
                            || !(param.args[0] instanceof MotionEvent e)) return;
                    int action = e.getActionMasked();
                    if (action != MotionEvent.ACTION_DOWN && action != MotionEvent.ACTION_UP) return;
                    touchX = e.getRawX();
                    touchY = e.getRawY();
                    touchTime = e.getEventTime();
                    if (action == MotionEvent.ACTION_UP) {
                        upTime = touchTime;
                    } else {
                        prevDownX = downX;
                        prevDownY = downY;
                        prevUpTime = upTime;
                        downX = touchX;
                        downY = touchY;
                        downTime = touchTime;
                    }
                    if (touchActivity.get() != param.thisObject) {
                        touchActivity = new WeakReference<>((Activity) param.thisObject);
                    }
                }
            });
        } catch (Throwable t) {
            ModuleLog.line(TAG + "⚠️ touch tracking: " + t);
        }
    }

    /** A double tap likes from inside GestureDetector's callback (feed and reels alike). */
    private static boolean doubleTapOnStack() {
        for (StackTraceElement frame : Thread.currentThread().getStackTrace()) {
            String name = frame.getMethodName();
            if ("onDoubleTap".equals(name) || "onDoubleTapEvent".equals(name)) return true;
        }
        return false;
    }

    /** Turns the heart of a like from {@code origin} blue; null when no heart was found (main thread). */
    static LikeHeartTint show(Activity a, Origin origin) {
        try {
            LikeHeartTint tint = locate(a, origin);
            ModuleLog.line(TAG + "heart " + (tint == null ? "not found" : "blue (" + tint.how + ")"));
            return tint;
        } catch (Throwable t) {
            ModuleLog.line(TAG + "⚠️ heart: " + t);
            return null;
        }
    }

    /** Whether the blue still shows on a view on screen. */
    boolean isShown() {
        View v = view.get();
        return !done && v != null && v.isAttachedToWindow() && v.isShown();
    }

    /** Instagram's own heart again (main thread; once). */
    void clear() {
        if (done) return;
        done = true;
        View v = view.get();
        if (v == null) return;
        v.removeOnAttachStateChangeListener(this);
        if (tinted.get(v) == this) tinted.remove(v);
        try {
            if (layer) v.setLayerType(layerType, null);
            else if (Build.VERSION.SDK_INT >= 31) v.setRenderEffect(null);
        } catch (Throwable t) {
            ModuleLog.line(TAG + "⚠️ heart reset: " + t);
        }
    }

    @Override public void onViewAttachedToWindow(View v) {}

    /** Recycled for another post, or gone: the blue must not follow the view. */
    @Override public void onViewDetachedFromWindow(View v) { clear(); }

    private static LikeHeartTint locate(Activity a, Origin origin) {
        View decor = a.getWindow().getDecorView();
        View known = origin.heart.get();
        if (known != null && known.isShown()) return tint(known, 0, 0, 0, "tapped heart");

        boolean touched = origin.touched && origin.activity.get() == a;
        int x = Math.round(origin.x), y = Math.round(origin.y);
        List<View> hearts = new ArrayList<>();
        collectHearts(decor, a, hearts);
        int[][] rects = new int[hearts.size()][];
        for (int i = 0; i < rects.length; i++) rects[i] = rect(hearts.get(i));
        List<View> atTouch = touched ? viewsAt(decor, x, y) : java.util.Collections.emptyList();
        View media = origin.doubleTap ? mediaOf(atTouch, decor) : null;
        int found = LikeHeartPolicy.pick(rects, touched, x, y, origin.doubleTap,
                media == null ? null : rect(media), dp(a, SLOP_DP), dp(a, 200));
        if (found >= 0) return tint(hearts.get(found), 0, 0, 0, "heart view");
        if (!touched) return null;

        if (!origin.doubleTap) return tintAt(a, atTouch, x, y, "tapped view");
        // No heart view: the feed draws its heart first in the row right under the media.
        if (media == null) return null;
        int[] m = rect(media);
        int px = media.getLayoutDirection() == View.LAYOUT_DIRECTION_RTL ? m[2] - dp(a, 24) : m[0] + dp(a, 24);
        int py = m[3] + dp(a, 24);
        List<View> below = viewsAt(decor, px, py);
        // Only within the same list: under a full-screen media that spot is the tab bar.
        if (below.isEmpty() || !scrollTogether(media, below.get(0))) return null;
        return tintAt(a, below, px, py, "row under the media");
    }

    /** The first heart-sized view at a point, else a circle around it in the view on top. */
    private static LikeHeartTint tintAt(Activity a, List<View> hits, int x, int y, String how) {
        for (View v : hits) {
            if (v.getWidth() <= dp(a, SMALL_DP) && v.getHeight() <= dp(a, SMALL_DP)) return tint(v, 0, 0, 0, how);
        }
        if (hits.isEmpty()) return null;
        View top = hits.get(0);
        int[] at = new int[2];
        top.getLocationOnScreen(at);
        return tint(top, x - at[0], y - at[1], dp(a, SPOT_RADIUS_DP), how + ", spot");
    }

    /** Applies the blue to {@code v}, whole when {@code radius} is 0, else within that circle. */
    @SuppressLint("NewApi") // RuntimeShader is only built on 33+; RenderEffect only used on 31+
    private static LikeHeartTint tint(View v, float cx, float cy, float radius, String how) {
        boolean shader = Build.VERSION.SDK_INT >= 33;
        if (!shader && radius > 0) return null; // a color filter would recolor the whole view
        LikeHeartTint old = tinted.get(v);
        if (old != null) old.clear();
        LikeHeartTint tint = new LikeHeartTint(v, how);
        if (shader) {
            RuntimeShader s = new RuntimeShader(SHADER);
            float[] red = LikeHeartPolicy.channels(LikeHeartPolicy.LIKE_RED);
            float[] blue = LikeHeartPolicy.channels(LikeHeartPolicy.LIKE_BLUE);
            s.setFloatUniform("likeRed", red[0], red[1], red[2]);
            s.setFloatUniform("likeBlue", blue[0], blue[1], blue[2]);
            s.setFloatUniform("spot", cx, cy, radius);
            v.setRenderEffect(RenderEffect.createRuntimeShaderEffect(s, "content"));
        } else {
            ColorMatrixColorFilter filter = new ColorMatrixColorFilter(LikeHeartPolicy.redToBlueMatrix(
                    LikeHeartPolicy.LIKE_RED, LikeHeartPolicy.LIKE_BLUE));
            if (Build.VERSION.SDK_INT >= 31) {
                v.setRenderEffect(RenderEffect.createColorFilterEffect(filter));
            } else {
                tint.layer = true;
                tint.layerType = v.getLayerType();
                Paint paint = new Paint();
                paint.setColorFilter(filter);
                v.setLayerType(View.LAYER_TYPE_HARDWARE, paint);
            }
        }
        v.addOnAttachStateChangeListener(tint);
        tinted.put(v, tint);
        return tint;
    }

    /** Shown, heart-sized views that are like buttons: by id, or by Instagram's like label. */
    @SuppressLint("DiscouragedApi")
    private static void collectHearts(View root, Activity a, List<View> out) {
        if (heartIds == null) {
            Set<Integer> ids = new HashSet<>();
            Set<String> labels = new HashSet<>();
            String pkg = a.getPackageName();
            for (String name : HEART_IDS) {
                int id = a.getResources().getIdentifier(name, "id", pkg);
                if (id != 0) ids.add(id);
            }
            for (String name : HEART_LABELS) {
                int id = a.getResources().getIdentifier(name, "string", pkg);
                if (id == 0) continue;
                try {
                    labels.add(a.getResources().getString(id).trim().toLowerCase(Locale.ROOT));
                } catch (Throwable ignored) {}
            }
            heartLabels = labels;
            heartIds = ids;
        }
        java.util.ArrayDeque<View> stack = new java.util.ArrayDeque<>();
        stack.push(root);
        int max = dp(a, HEART_MAX_DP);
        while (!stack.isEmpty()) {
            View v = stack.pop();
            if (v.getVisibility() != View.VISIBLE) continue;
            if (v instanceof ViewGroup g) for (int i = 0; i < g.getChildCount(); i++) stack.push(g.getChildAt(i));
            if (v.getWidth() == 0 || v.getWidth() > max || v.getHeight() > max) continue;
            CharSequence label = v.getContentDescription();
            boolean heart = heartIds.contains(v.getId())
                    || (label != null && heartLabels.contains(label.toString().trim().toLowerCase(Locale.ROOT)));
            if (heart && v.isShown() && v.getGlobalVisibleRect(new android.graphics.Rect())) out.add(v);
        }
    }

    /** Every shown view under a screen point, the one drawn on top (and deepest) first. */
    private static List<View> viewsAt(View root, int x, int y) {
        List<View> out = new ArrayList<>();
        collectAt(root, x, y, out);
        return out;
    }

    private static void collectAt(View v, int x, int y, List<View> out) {
        if (v.getVisibility() != View.VISIBLE || v.getAlpha() == 0f) return;
        int[] r = rect(v);
        if (x < r[0] || x >= r[2] || y < r[1] || y >= r[3]) return;
        if (v instanceof ViewGroup g) {
            for (int i = g.getChildCount() - 1; i >= 0; i--) collectAt(g.getChildAt(i), x, y, out);
        }
        out.add(v);
    }

    /**
     * The double-tapped media: of the views under the tap, top first, the first one most of the
     * window wide and 160 dp tall inside a scrolling list (so never an overlay over the screen).
     */
    private static View mediaOf(List<View> atTouch, View decor) {
        float d = decor.getResources().getDisplayMetrics().density;
        for (View v : atTouch) {
            if (v != decor && v.getWidth() >= decor.getWidth() * 0.6f && v.getHeight() >= 160 * d
                    && scroller(parentOf(v)) != null) return v;
        }
        return null;
    }

    /** Whether both views sit in one scrolling container (a feed list, a post). */
    private static boolean scrollTogether(View a, View b) {
        for (View c = scroller(a); c != null; c = scroller(parentOf(c))) {
            for (View v = b; v != null; v = parentOf(v)) {
                if (v == c) return true;
            }
        }
        return false;
    }

    /** {@code v} or its nearest ancestor that scrolls vertically, or null. */
    private static View scroller(View v) {
        for (; v != null; v = parentOf(v)) {
            if (v.canScrollVertically(1) || v.canScrollVertically(-1)) return v;
        }
        return null;
    }

    private static View parentOf(View v) {
        return v.getParent() instanceof View p ? p : null;
    }

    private static int[] rect(View v) {
        int[] at = new int[2];
        v.getLocationOnScreen(at);
        return new int[]{at[0], at[1], at[0] + v.getWidth(), at[1] + v.getHeight()};
    }

    private static int dp(Activity a, int value) {
        return Math.round(value * a.getResources().getDisplayMetrics().density);
    }
}
