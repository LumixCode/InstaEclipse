package ps.reso.instaeclipse.mods.ui;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.graphics.Rect;
import android.view.View;
import android.view.ViewGroup;

import java.lang.reflect.Method;

import ps.reso.instaeclipse.utils.feature.FeatureFlags;
import ps.reso.instaeclipse.utils.feature.FeatureStatusTracker;
import ps.reso.instaeclipse.utils.log.ModuleLog;
import ps.reso.instaeclipse.xposedcompat.XC_MethodHook;
import ps.reso.instaeclipse.xposedcompat.XposedBridge;

public final class BackLeavesHomeHook {

    private static final String TAG = "(IE|BackLeavesHome) ";
    private static final String MAIN_CLASS = "com.instagram.mainactivity.InstagramMainActivity";
    private static final String FEED_LIST = "MainFeedRecyclerView";
    private static int sheetId = -1;
    private static Class<?> mainActivity;

    public void install(ClassLoader cl) {
        hookActivityBack(cl);
    }

    private static void hookActivityBack(ClassLoader cl) {
        try {
            Method back = null;
            mainActivity = cl.loadClass(MAIN_CLASS);
            for (Class<?> c = mainActivity; c != null && back == null; c = c.getSuperclass()) {
                try { back = c.getDeclaredMethod("onBackPressed"); } catch (NoSuchMethodException ignored) { }
            }
            if (back == null) {
                ModuleLog.error(TAG + "Will not work: the Back button handler was not found in this Instagram version");
                return;
            }
            XposedBridge.hookMethod(back, new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    if (param.thisObject instanceof Activity a && leaveIfHomeFeed(a)) param.setResult(null);
                }
            });
            FeatureStatusTracker.setHooked("BackLeavesHome");
            ModuleLog.ok(TAG + "ready");
            ModuleLog.detail(TAG + "hooked " + back.getDeclaringClass().getName() + ".onBackPressed");
        } catch (Throwable t) {
            ModuleLog.error(TAG + "Will not work: setup failed", t);
        }
    }

    static boolean leaveIfHomeFeed(Activity a) {
        if (!FeatureFlags.backLeavesHome || mainActivity == null || !mainActivity.isInstance(a)) return false;
        try {
            if (!homeFeedInFront(a)) return false;
            a.moveTaskToBack(true);
            return true;
        } catch (Throwable ignored) {
            return false;
        }
    }

    @SuppressLint("DiscouragedApi")
    private static boolean homeFeedInFront(Activity a) {
        View decor = a.getWindow() != null ? a.getWindow().getDecorView() : null;
        if (!(decor instanceof ViewGroup root)) return false;
        if (sheetId == -1) sheetId = a.getResources().getIdentifier("bottom_sheet_container_view", "id", a.getPackageName());
        if (sheetId != 0 && anyShown(root, sheetId)) return false;
        View top = topViewAt(root, root.getWidth() / 2, (int) (root.getHeight() * 0.45f));
        for (View v = top; v != null; v = v.getParent() instanceof View p ? p : null) {
            if (v.getClass().getName().endsWith(FEED_LIST)) return v.canScrollVertically(-1);
        }
        return false;
    }

    private static View topViewAt(ViewGroup group, int x, int y) {
        Rect r = new Rect();
        for (int i = group.getChildCount() - 1; i >= 0; i--) {
            View child = group.getChildAt(i);
            if (!child.isShown() || child.getAlpha() <= 0f || !child.getGlobalVisibleRect(r) || !r.contains(x, y)) continue;
            if (child instanceof ViewGroup g) {
                View inner = topViewAt(g, x, y);
                if (inner != null) return inner;
                if (g.getBackground() == null && !g.isClickable()) continue;
                return child;
            }
            return child;
        }
        return null;
    }

    private static boolean anyShown(ViewGroup group, int id) {
        for (int i = 0; i < group.getChildCount(); i++) {
            View child = group.getChildAt(i);
            if (child.getId() == id && child.isShown() && child.getHeight() > 0) return true;
            if (child instanceof ViewGroup g && anyShown(g, id)) return true;
        }
        return false;
    }
}
