package ps.reso.instaeclipse.mods.extras;

import android.os.Looper;
import android.view.View;
import android.view.ViewStub;

import java.lang.ref.WeakReference;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.Collections;
import java.util.Map;
import java.util.WeakHashMap;

import ps.reso.instaeclipse.utils.core.DexKitQueryCache.Found;
import ps.reso.instaeclipse.utils.core.SafeReflect;
import ps.reso.instaeclipse.utils.feature.FeatureFlags;
import ps.reso.instaeclipse.utils.feature.FeatureStatusTracker;
import ps.reso.instaeclipse.utils.log.ModuleLog;
import ps.reso.instaeclipse.utils.ui.ResIds;
import ps.reso.instaeclipse.xposedcompat.XC_MethodHook;
import ps.reso.instaeclipse.xposedcompat.XposedBridge;

public class ReelsCleanupHook {

    private static final String TAG = "(IE|ReelsCleanup) ";
    private static final String[] FOLLOW = {
            "android_purge_26_q3_ClipsFollowButtonComponent_render",
            "android_purge_26_q2_ClipsFollowButtonComponentLegacy_render",
    };
    private static final String[] PILLS = {
            "android_purge_26_q3_ClipsMusicSubtitleComponent_render",
            "android_purge_26_q3_ClipsBaselAttributionPillComponent_render",
            "android_purge_26_q2_ClipsBaselCreativeToolAttributionPillComponent_render",
            "android_purge_26_q2_ClipsBaselPlatformizedCreativeToolAttributionComponent_render",
    };
    private static final String[] PILL_TYPES = {"TEMPLATE", "STORIES_TEMPLATE"};
    private static final String COMMENT_BAR = "android_purge_26_q3_ClipsViewerInViewerCommentBarComponent_render";
    private static final String BAR_CREATED = "android_purge_26_q2_ClipsViewerCommentBarController_onViewCreated";
    private static final String BAR_CACHE = "Extras_ReelsCommentBar";
    private static final String BAR_STUB = "clips_comment_composer_stub";

    private static final Map<Object, WeakReference<View>> BARS = Collections.synchronizedMap(new WeakHashMap<>());

    private interface Part {
        int hide() throws Throwable;
    }

    public void install(ClassLoader cl) {
        if (FeatureFlags.reelsHideFollow) {
            install("reelsHideFollow", "Hide the Follow button", FOLLOW.length,
                    () -> ReelsOverlay.hide(cl, () -> FeatureFlags.reelsHideFollow, FOLLOW));
        }
        if (FeatureFlags.reelsHidePills) {
            install("reelsHidePills", "Hide template and audio pills", PILLS.length + 1,
                    () -> ReelsOverlay.hide(cl, () -> FeatureFlags.reelsHidePills, PILLS)
                            + (ReelsOverlay.hidePills(cl, () -> FeatureFlags.reelsHidePills, PILL_TYPES) ? 1 : 0));
        }
        if (FeatureFlags.reelsHideCommentBar) {
            install("reelsHideCommentBar", "Hide the comment bar", 2,
                    () -> ReelsOverlay.hide(cl, () -> FeatureFlags.reelsHideCommentBar, COMMENT_BAR)
                            + (installBar(cl) ? 1 : 0));
        }
    }

    private static void install(String status, String label, int parts, Part part) {
        int hidden = 0;
        try {
            hidden = part.hide();
        } catch (Throwable t) {
            ModuleLog.detail(TAG + label + ": " + t);
        }
        if (hidden == 0) {
            FeatureStatusTracker.setBroken(status);
            ModuleLog.error(TAG + "Will not work: " + label + " (not found in this Instagram version)");
            return;
        }
        FeatureStatusTracker.setHooked(status);
        ModuleLog.ok(TAG + label + ": ready");
        if (hidden < parts) {
            ModuleLog.warn(TAG + label + ": it may still show in places, " + (parts - hidden) + " of " + parts
                    + " parts not found in this Instagram version");
        }
    }

    private static boolean installBar(ClassLoader cl) {
        Method created = ExtrasLookup.method(BAR_CACHE, "reelsHideCommentBar", false, cl, () -> {
            for (Found md : ExtrasLookup.byStrings(BAR_CREATED)) {
                if (!md.isMethod()) continue;
                Method m = md.getMethodInstance(cl);
                if (takesRootView(m) && ReelsOverlay.owns(md.data().getUsingStrings(), BAR_CREATED)) return m;
            }
            return null;
        });
        if (created == null || !takesRootView(created)) return false;
        Class<?> controller = created.getDeclaringClass();
        try {
            XposedBridge.hookMethod(created, new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    if (FeatureFlags.reelsHideCommentBar) watchStub(param.thisObject, param.args);
                }

                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    keepHidden(controller, param);
                }
            });
        } catch (Throwable t) {
            ModuleLog.detail(TAG + "comment bar controller: " + t);
            return false;
        }
        XC_MethodHook afterEach = new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam param) {
                keepHidden(controller, param);
            }
        };
        int kept = 0;
        for (Method m : SafeReflect.methods(controller)) {
            if (m.equals(created) || Modifier.isAbstract(m.getModifiers()) || m.isSynthetic() || !reaches(m, controller)) continue;
            try {
                XposedBridge.hookMethod(m, afterEach);
                kept++;
            } catch (Throwable ignored) {}
        }
        ModuleLog.detail(TAG + "comment bar controller " + controller.getName() + ": " + kept + " method(s)");
        return true;
    }

    private static boolean takesRootView(Method m) {
        return !Modifier.isStatic(m.getModifiers()) && m.getParameterCount() > 0
                && View.class.isAssignableFrom(m.getParameterTypes()[0]);
    }

    private static boolean reaches(Method m, Class<?> controller) {
        if (!Modifier.isStatic(m.getModifiers())) return true;
        for (Class<?> type : m.getParameterTypes()) {
            if (type == controller) return true;
        }
        return false;
    }

    private static void keepHidden(Class<?> controller, XC_MethodHook.MethodHookParam param) {
        if (!FeatureFlags.reelsHideCommentBar) return;
        try {
            Object owner = param.thisObject;
            for (int i = 0; owner == null && param.args != null && i < param.args.length; i++) {
                if (controller.isInstance(param.args[i])) owner = param.args[i];
            }
            if (owner != null) hideBar(owner);
        } catch (Throwable ignored) {}
    }

    private static void watchStub(Object controller, Object[] args) {
        try {
            if (args == null || args.length == 0 || !(args[0] instanceof View view)) return;
            int id = ResIds.id(view.getContext(), BAR_STUB);
            View stub = id == 0 ? null : view.findViewById(id);
            if (!(stub instanceof ViewStub)) return;
            ((ViewStub) stub).setOnInflateListener((source, bar) -> {
                try {
                    BARS.put(controller, new WeakReference<>(bar));
                } catch (Throwable ignored) {}
            });
        } catch (Throwable ignored) {}
    }

    private static void hideBar(Object controller) {
        if (!FeatureFlags.reelsHideCommentBar) return;
        WeakReference<View> ref = BARS.get(controller);
        View bar = ref == null ? null : ref.get();
        if (bar == null || bar.getVisibility() == View.GONE) return;
        if (Looper.myLooper() == Looper.getMainLooper()) {
            bar.setVisibility(View.GONE);
            return;
        }
        bar.post(() -> {
            try {
                hideBar(controller);
            } catch (Throwable ignored) {}
        });
    }
}
