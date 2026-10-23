package ps.reso.instaeclipse.mods.ui;

import ps.reso.instaeclipse.xposedcompat.AndroidAppHelper;
import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewTreeObserver;
import android.widget.ImageView;
import android.widget.TextView;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import ps.reso.instaeclipse.R;
import ps.reso.instaeclipse.utils.i18n.I18n;
import ps.reso.instaeclipse.utils.log.ModuleLog;
import ps.reso.instaeclipse.utils.ui.ResIds;

/**
 * Proper icons for the rows we add to Instagram's post / reel "..." menus. Those rows ride on
 * internal debug options, which Instagram draws with the Meta logo. Once the sheet is on screen
 * we find our rows by their label (control_option_text) and set a fitting Instagram icon on the
 * row's control_option_icon; a pre-draw listener re-applies it when the list re-binds or scrolls.
 */
public final class MenuIcons {

    private MenuIcons() {}

    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    // Weak maps rather than view tags: our R.id values could collide with Instagram's tag keys.
    private static final Map<View, Boolean> WATCHED = java.util.Collections.synchronizedMap(new java.util.WeakHashMap<>());
    private static final Map<ImageView, Integer> APPLIED = java.util.Collections.synchronizedMap(new java.util.WeakHashMap<>());
    private static volatile long lastStart;
    // apply() results: no menu rows in the window / menu rows but none of ours / ours found.
    private static final int NO_MENU = 0, MENU = 1, OURS = 2;
    private static Map<String, String> iconsByLabel;

    /** Label -> Instagram drawable name, in the current language. */
    private static Map<String, String> icons() {
        Map<String, String> m = iconsByLabel;
        if (m != null) return m;
        Context c = AndroidAppHelper.currentApplication();
        if (c == null) return new HashMap<>();
        m = new HashMap<>();
        m.put(I18n.t(c, R.string.ig_media_info_title), "instagram_info_pano_outline_24");
        m.put(I18n.t(c, R.string.ig_playback_speed_title), "instagram_circle_play_pano_outline_24");
        m.put(I18n.t(c, R.string.ig_post_counts_title), "instagram_heart_pano_outline_24");
        m.put(I18n.t(c, R.string.ig_download_choice_copy_image), "instagram_photo_pano_outline_24");
        iconsByLabel = m;
        return m;
    }

    /** Call when our rows were just added to a menu; fixes their icons once the sheet shows. */
    public static void fixSoon() {
        try {
            start();
        } catch (Throwable t) {
            ModuleLog.detail("(IE|MenuIcons) start: " + t.getClass().getSimpleName() + ": " + t.getMessage());
        }
    }

    private static void start() {
        long now = SystemClock.uptimeMillis();
        if (now - lastStart < 300) return;
        lastStart = now;
        iconsByLabel = null; // pick up a language change
        final int[] tries = {0};
        MAIN.postDelayed(new Runnable() {
            @Override
            public void run() {
                boolean found = false;
                try {
                    for (View root : rootViews()) found |= watch(root);
                } catch (Throwable t) {
                    ModuleLog.detail("(IE|MenuIcons) watch: " + t.getClass().getSimpleName() + ": " + t.getMessage());
                }
                if (!found && ++tries[0] < 25) MAIN.postDelayed(this, 80);
            }
        }, 60);
    }

    /** Applies once and keeps re-applying while this window is showing. */
    private static boolean watch(View root) {
        if (apply(root) != OURS) return false;
        try {
            listen(root);
        } catch (Throwable t) {
            ModuleLog.detail("(IE|MenuIcons) listen: " + t.getClass().getSimpleName() + ": " + t.getMessage());
        }
        return true;
    }

    private static void listen(View root) {
        if (WATCHED.put(root, Boolean.TRUE) != null) return;
        // Pre-draw (throttled), not global layout: rows scrolled into view are bound without a
        // layout pass on the window, so a layout listener misses them.
        final long[] last = {0};
        final Runnable catchUp = () -> apply(root); // the frame skipped by the throttle
        final View.OnAttachStateChangeListener[] detach = new View.OnAttachStateChangeListener[1];
        ViewTreeObserver.OnPreDrawListener l = new ViewTreeObserver.OnPreDrawListener() {
            @Override public boolean onPreDraw() {
                long t = SystemClock.uptimeMillis();
                if (t - last[0] > 80) {
                    last[0] = t;
                    // On IG 446 sheets live in the activity window, so "removed on detach" would
                    // keep walking the main tree every frame; stop once the menu has closed.
                    if (apply(root) == NO_MENU) stop(root, this, detach[0], catchUp);
                } else { root.removeCallbacks(catchUp); root.postDelayed(catchUp, 100); }
                return true;
            }
        };
        detach[0] = new View.OnAttachStateChangeListener() {
            @Override public void onViewAttachedToWindow(View v) {}
            @Override public void onViewDetachedFromWindow(View v) { stop(v, l, this, catchUp); }
        };
        root.getViewTreeObserver().addOnPreDrawListener(l);
        root.addOnAttachStateChangeListener(detach[0]);
    }

    private static void stop(View root, ViewTreeObserver.OnPreDrawListener l,
                             View.OnAttachStateChangeListener detach, Runnable catchUp) {
        root.getViewTreeObserver().removeOnPreDrawListener(l);
        root.removeOnAttachStateChangeListener(detach);
        root.removeCallbacks(catchUp);
        WATCHED.remove(root);
    }

    /** OURS if at least one of our rows is in this window, MENU if only Instagram's menu rows are.
     *  Never throws: this also runs from a pre-draw listener on Instagram's UI thread, where an
     *  exception would crash the app; on any failure the row simply keeps Instagram's own icon. */
    private static int apply(View root) {
        try {
            return applyUnsafe(root);
        } catch (Throwable t) {
            ModuleLog.detail("(IE|MenuIcons) apply: " + t.getClass().getSimpleName() + ": " + t.getMessage());
            return NO_MENU;
        }
    }

    private static int applyUnsafe(View root) {
        Context c = root.getContext();
        int textId = ResIds.id(c, "control_option_text");
        int iconId = ResIds.id(c, "control_option_icon");
        if (textId == 0 || iconId == 0) return NO_MENU;
        Map<String, String> map = icons();
        boolean menu = false;
        boolean any = false;
        java.util.ArrayDeque<View> st = new java.util.ArrayDeque<>();
        st.push(root);
        while (!st.isEmpty()) {
            View v = st.pop();
            if (v.getId() == textId && v instanceof TextView tv) {
                menu = true;
                String icon = tv.getText() == null ? null : map.get(tv.getText().toString());
                if (icon == null) continue;
                any = true;
                if (!(tv.getParent() instanceof ViewGroup row)) continue;
                View iv = row.findViewById(iconId);
                if (!(iv instanceof ImageView img)) continue;
                int res = c.getResources().getIdentifier(icon, "drawable", c.getPackageName());
                if (res == 0) res = c.getResources().getIdentifier(icon.replace("_pano", ""), "drawable", c.getPackageName());
                if (res == 0 || Integer.valueOf(res).equals(APPLIED.get(img))) continue;
                img.setImageResource(res); // keeps the row's tint
                APPLIED.put(img, res);
            } else if (v instanceof ViewGroup g) {
                for (int i = 0; i < g.getChildCount(); i++) st.push(g.getChildAt(i));
            }
        }
        return any ? OURS : menu ? MENU : NO_MENU;
    }

    /** Every window's root view (activity, dialogs, bottom sheets). */
    @SuppressWarnings("unchecked")
    private static List<View> rootViews() {
        try {
            Class<?> wmg = Class.forName("android.view.WindowManagerGlobal");
            Object inst = wmg.getMethod("getInstance").invoke(null);
            java.lang.reflect.Field f = wmg.getDeclaredField("mViews");
            f.setAccessible(true);
            synchronized (inst) {
                return new java.util.ArrayList<>((List<View>) f.get(inst));
            }
        } catch (Throwable t) {
            List<View> out = new java.util.ArrayList<>();
            android.app.Activity a = UIHookManager.getCurrentActivity();
            if (a != null && a.getWindow() != null) out.add(a.getWindow().getDecorView());
            return out;
        }
    }
}
