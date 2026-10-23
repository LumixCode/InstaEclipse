package ps.reso.instaeclipse.mods.ui.theme;

import android.app.Activity;
import android.content.Context;
import android.content.res.Resources;
import android.content.res.TypedArray;
import android.graphics.Color;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.TypedValue;
import android.view.View;
import android.view.Window;
import android.view.WindowInsetsController;

import java.lang.reflect.Field;

import ps.reso.instaeclipse.xposedcompat.XC_MethodHook;
import ps.reso.instaeclipse.xposedcompat.XposedHelpers;
import ps.reso.instaeclipse.R;
import ps.reso.instaeclipse.mods.ui.UIHookManager;
import ps.reso.instaeclipse.utils.feature.FeatureFlags;
import ps.reso.instaeclipse.utils.feature.FeatureStatusTracker;
import ps.reso.instaeclipse.utils.log.ModuleLog;

/**
 * Custom Theme: hooks the standard Android resource/color-resolution APIs (Resources.Theme,
 * Resources, Context, TypedArray) plus Activity/PhoneWindow lifecycle, so a custom palette is
 * substituted everywhere Instagram resolves a themed color. Deliberately limited to stable
 * Android SDK surfaces — Instagram's own Compose/React-Native rendering paths need separate,
 * per-version reverse-engineered hooks and are not covered here.
 */
public class IgThemeHook {

    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static Runnable pendingRecreate;   // main thread only
    private static boolean themeShown;         // main thread only: was the current screen themed?
    private static boolean lazyTried;          // main thread only: one live install attempt at most
    private static volatile ClassLoader hostLoader; // lets a live "theme on" install the skipped hooks
    private static volatile boolean installed;
    private static volatile Field typedArrayResourcesField;

    public void install(ClassLoader classLoader) {
        if (installed) return;
        hostLoader = classLoader;
        // First: hooks installed before a later one throws still theme the screen.
        themeShown = FeatureFlags.customThemeEnabled;
        // These hooks sit on very hot framework methods (getColor, resolveAttribute); on some
        // devices (Android 13 / SM-G780G) that makes ART's JIT crash IG, so they are only installed
        // when the custom theme is on (turning it on later installs them in refreshCurrentActivity).
        if (!FeatureFlags.customThemeEnabled) {
            ModuleLog.detail("(InstaEclipse | Theme): off -> hooks not installed");
            return;
        }
        try {
            hookResolveAttribute(classLoader);
            hookGetColor(classLoader);
            hookContextGetColor();
            hookTypedArrayGetColor(classLoader);
            IgThemeColorSourceHooks.install(classLoader);
            hookActivityLifecycle();
            hookPhoneWindowColors(classLoader);
            installed = true;
            FeatureStatusTracker.setHooked("CustomTheme");
            ModuleLog.ok("(InstaEclipse | Theme): ready");
            ModuleLog.detail("(InstaEclipse | Theme): hooks installed enabled=" + FeatureFlags.customThemeEnabled);
        } catch (Throwable t) {
            ModuleLog.error("(InstaEclipse | Theme): Could not fully turn on the custom theme; some screens may keep Instagram's colors", t);
        }
    }

    private void hookResolveAttribute(final ClassLoader cl) {
        XposedHelpers.findAndHookMethod("android.content.res.Resources$Theme", cl, "resolveAttribute",
                int.class, TypedValue.class, boolean.class, new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) { ps.reso.instaeclipse.utils.core.JitGuard.tick(param.method);
                        if (IgColorRemapEngine.isBypassing() || !IgThemeEngine.themeEnabled()) return;
                        int attrId = (Integer) param.args[0];
                        TypedValue out = (TypedValue) param.args[1];
                        if (out == null) return;
                        if (!IgThemeEngine.isInitialized()) {
                            try {
                                Resources res = (Resources) XposedHelpers.getObjectField(param.thisObject, "mResources");
                                if (res != null) IgThemeEngine.ensureInitialized(res, cl);
                            } catch (Throwable ignored) {}
                        }
                        Integer override = IgThemeEngine.colorForAttr(attrId);
                        if (override != null) {
                            IgThemeEngine.applyAttrOverride(attrId, out);
                            param.setResult(true);
                        } else if (IgColorRemapEngine.isReady()) {
                            if ((out.type == TypedValue.TYPE_INT_COLOR_ARGB8 || out.type == TypedValue.TYPE_INT_COLOR_RGB8)) {
                                int remapped = IgColorRemapEngine.remap(out.data);
                                if (remapped != out.data) {
                                    out.data = remapped;
                                    param.setResult(true);
                                }
                            }
                        }
                    }
                });
    }

    private void hookGetColor(final ClassLoader cl) {
        XC_MethodHook hook = new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) { ps.reso.instaeclipse.utils.core.JitGuard.tick(param.method);
                if (!IgThemeEngine.themeEnabled() || IgColorRemapEngine.isBypassing()) return;
                int resId = (Integer) param.args[0];
                if (IgThemeEngine.looksLikeDirectColor(resId)) {
                    param.setResult(IgColorRemapEngine.remap(resId));
                    return;
                }
                if (IgThemeEngine.looksLikeResourceId(resId)) {
                    if (!IgThemeEngine.isInitialized()) {
                        Resources res = (Resources) param.thisObject;
                        IgThemeEngine.ensureInitialized(res, cl);
                    }
                    Integer override = IgThemeEngine.colorForResource(resId);
                    if (override != null) param.setResult(override);
                }
            }

            @Override
            protected void afterHookedMethod(MethodHookParam param) { ps.reso.instaeclipse.utils.core.JitGuard.tick(param.method);
                if (!IgThemeEngine.themeEnabled() || IgColorRemapEngine.isBypassing()
                        || param.getThrowable() != null || !IgColorRemapEngine.isReady()) return;
                int resId = (Integer) param.args[0];
                if (!IgThemeEngine.looksLikeDirectColor(resId) && IgThemeEngine.looksLikeResourceId(resId)
                        && IgThemeEngine.colorForResource(resId) == null) {
                    Object result = param.getResult();
                    if (result instanceof Integer) {
                        int resolved = (Integer) result;
                        int remapped = IgColorRemapEngine.remap(resolved);
                        if (remapped != resolved) param.setResult(remapped);
                    }
                }
            }
        };
        try {
            XposedHelpers.findAndHookMethod(Resources.class, "getColor", int.class, Resources.Theme.class, hook);
        } catch (Throwable ignored) {}
        try {
            XposedHelpers.findAndHookMethod(Resources.class, "getColor", int.class, hook);
        } catch (Throwable ignored) {}
    }

    private void hookContextGetColor() {
        XC_MethodHook hook = new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) { ps.reso.instaeclipse.utils.core.JitGuard.tick(param.method);
                if (!IgThemeEngine.themeEnabled() || IgColorRemapEngine.isBypassing()) return;
                int resId = (Integer) param.args[0];
                if (IgThemeEngine.looksLikeDirectColor(resId)) {
                    param.setResult(IgColorRemapEngine.remap(resId));
                    return;
                }
                if (IgThemeEngine.looksLikeResourceId(resId)) {
                    if (!IgThemeEngine.isInitialized()) {
                        Context ctx = (Context) param.thisObject;
                        IgThemeEngine.ensureInitialized(ctx);
                    }
                    Integer override = IgThemeEngine.colorForResource(resId);
                    if (override != null) param.setResult(override);
                }
            }

            @Override
            protected void afterHookedMethod(MethodHookParam param) { ps.reso.instaeclipse.utils.core.JitGuard.tick(param.method);
                if (!IgThemeEngine.themeEnabled() || IgColorRemapEngine.isBypassing()
                        || param.getThrowable() != null || !IgColorRemapEngine.isReady()) return;
                int resId = (Integer) param.args[0];
                if (!IgThemeEngine.looksLikeDirectColor(resId) && IgThemeEngine.looksLikeResourceId(resId)
                        && IgThemeEngine.colorForResource(resId) == null) {
                    Object result = param.getResult();
                    if (result instanceof Integer) {
                        int resolved = (Integer) result;
                        int remapped = IgColorRemapEngine.remap(resolved);
                        if (remapped != resolved) param.setResult(remapped);
                    }
                }
            }
        };
        try {
            XposedHelpers.findAndHookMethod(Context.class, "getColor", int.class, hook);
        } catch (Throwable ignored) {}
    }

    private void hookTypedArrayGetColor(final ClassLoader cl) {
        XposedHelpers.findAndHookMethod(TypedArray.class, "getColor", int.class, int.class, new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam param) { ps.reso.instaeclipse.utils.core.JitGuard.tick(param.method);
                if (IgColorRemapEngine.isBypassing() || !IgThemeEngine.themeEnabled()
                        || param.getThrowable() != null) return;
                try {
                    TypedArray ta = (TypedArray) param.thisObject;
                    int index = (Integer) param.args[0];
                    if (!IgThemeEngine.isInitialized()) {
                        Resources res = typedArrayResources(ta);
                        if (res != null) IgThemeEngine.ensureInitialized(res, cl);
                    }
                    // A TypedArray keeps no attribute ids, only the color resource each value came
                    // from: theme by that resource's name as Resources.getColor does, else by value.
                    Integer override = IgThemeEngine.colorForResource(ta.getResourceId(index, 0));
                    if (override != null) {
                        param.setResult(override);
                        return;
                    }
                    if (IgColorRemapEngine.isReady()) {
                        Object result = param.getResult();
                        if (result instanceof Integer) {
                            int resolved = (Integer) result;
                            int remapped = IgColorRemapEngine.remap(resolved);
                            if (remapped != resolved) param.setResult(remapped);
                        }
                    }
                } catch (Throwable ignored) {}
            }
        });
    }

    private void hookActivityLifecycle() {
        XposedHelpers.findAndHookMethod(Activity.class, "onResume", new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam param) { ps.reso.instaeclipse.utils.core.JitGuard.tick(param.method);
                if (!IgThemeEngine.isActive()) return;
                Activity activity = (Activity) param.thisObject;
                IgThemeEngine.ensureInitialized(activity);
                IgColorRemapEngine.ensureBuilt(activity);
                applyWindowColors(activity);
            }
        });
        XposedHelpers.findAndHookMethod(Activity.class, "onCreate", Bundle.class, new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) { ps.reso.instaeclipse.utils.core.JitGuard.tick(param.method);
                // Build the remap table before the activity inflates its layout (setContentView runs
                // inside onCreate); otherwise the first header/feed backgrounds are created unthemed
                // and then reused from Android's drawable cache.
                if (!IgThemeEngine.isActive()) return;
                Activity activity = (Activity) param.thisObject;
                IgThemeEngine.ensureInitialized(activity);
                IgColorRemapEngine.ensureBuilt(activity);
            }

            @Override
            protected void afterHookedMethod(MethodHookParam param) { ps.reso.instaeclipse.utils.core.JitGuard.tick(param.method);
                if (!IgThemeEngine.isActive()) return;
                Activity activity = (Activity) param.thisObject;
                IgThemeEngine.ensureInitialized(activity);
                IgColorRemapEngine.ensureBuilt(activity);
                applyWindowColors(activity);
            }
        });
    }

    private void hookPhoneWindowColors(ClassLoader cl) {
        XC_MethodHook statusHook = new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) { ps.reso.instaeclipse.utils.core.JitGuard.tick(param.method);
                if (IgThemeEngine.isActive()) param.args[0] = IgThemeEngine.getActivePalette().statusBar;
            }
        };
        XC_MethodHook navHook = new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) { ps.reso.instaeclipse.utils.core.JitGuard.tick(param.method);
                if (IgThemeEngine.isActive()) param.args[0] = IgThemeEngine.getActivePalette().navigation;
            }
        };
        if (!tryHookPhoneWindow(cl, statusHook, navHook)) tryHookPhoneWindow(null, statusHook, navHook);
    }

    private boolean tryHookPhoneWindow(ClassLoader cl, XC_MethodHook statusHook, XC_MethodHook navHook) {
        try {
            XposedHelpers.findAndHookMethod("com.android.internal.policy.PhoneWindow", cl, "setStatusBarColor", int.class, statusHook);
            XposedHelpers.findAndHookMethod("com.android.internal.policy.PhoneWindow", cl, "setNavigationBarColor", int.class, navHook);
            ModuleLog.detail("(InstaEclipse | Theme): PhoneWindow color hooks installed");
            return true;
        } catch (Throwable ignored) {
            return false;
        }
    }

    /**
     * Called after a theme setting changes so it is visible immediately, rather than
     * waiting for the user to navigate away and back (which is the only other time the
     * Activity onResume/onCreate hooks would naturally re-run). Main thread only.
     */
    public static void refreshCurrentActivity() {
        boolean wasShown = themeShown;
        themeShown = FeatureFlags.customThemeEnabled;
        // Theme was off at startup, so install() skipped the hooks: add them now. Only once, since
        // a partly failed install() would otherwise stack duplicate hooks on every retry.
        if (themeShown && !installed && !lazyTried && hostLoader != null) {
            lazyTried = true;
            new IgThemeHook().install(hostLoader);
        }
        Activity activity = UIHookManager.getCurrentActivity();
        if (activity == null || activity.isFinishing() || activity.isDestroyed()) return;
        // Off before and after (a restore or reset re-sending the keys): nothing on screen changes.
        if (!wasShown && !themeShown) return;
        IgThemeEngine.ensureInitialized(activity);
        if (themeShown) {
            IgColorRemapEngine.ensureBuilt(activity);
            applyWindowColors(activity);
        }
        // Recreate after disabling too: the bar and decor colors applied above outlive the switch.
        // One theme save arrives as up to three broadcasts, so coalesce them into a single recreate.
        if (pendingRecreate != null) MAIN.removeCallbacks(pendingRecreate);
        pendingRecreate = () -> {
            pendingRecreate = null;   // must not keep the old Activity alive after it is rebuilt
            if (!activity.isFinishing() && !activity.isDestroyed()) activity.recreate();
        };
        MAIN.postDelayed(pendingRecreate, 300);
    }

    static void applyWindowColors(Activity activity) {
        if (activity == null || !IgThemeEngine.isActive()) return;
        try {
            IgThemePalette palette = IgThemeEngine.getActivePalette();
            Window window = activity.getWindow();
            if (window == null) return;
            window.setStatusBarColor(palette.statusBar);
            window.setNavigationBarColor(palette.navigation);
            View decor = window.getDecorView();
            if (decor != null) decor.setBackgroundColor(palette.background);
            if (Build.VERSION.SDK_INT >= 29) {
                window.setStatusBarContrastEnforced(false);
                window.setNavigationBarContrastEnforced(false);
            }
            if (Build.VERSION.SDK_INT >= 30) {
                WindowInsetsController controller = window.getInsetsController();
                if (controller != null) {
                    boolean lightBg = Color.red(palette.background) + Color.green(palette.background) + Color.blue(palette.background) > 382;
                    controller.setSystemBarsAppearance(lightBg ? WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS
                            | WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS : 0,
                            WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS | WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS);
                }
                return;
            }
            applyLegacySystemUiVisibility(window, palette.background);
        } catch (Throwable ignored) {}
    }

    @SuppressWarnings("deprecation")
    private static void applyLegacySystemUiVisibility(Window window, int backgroundColor) {
        int flags = window.getDecorView().getSystemUiVisibility();
        boolean lightBg = Color.red(backgroundColor) + Color.green(backgroundColor) + Color.blue(backgroundColor) > 382;
        if (lightBg) {
            flags = flags | View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR | View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR;
        } else {
            flags = flags & ~View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR & ~View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR;
        }
        window.getDecorView().setSystemUiVisibility(flags);
    }

    private static Resources typedArrayResources(TypedArray ta) {
        try {
            Field field = typedArrayResourcesField;
            if (field == null) {
                field = TypedArray.class.getDeclaredField("mResources");
                field.setAccessible(true);
                typedArrayResourcesField = field;
            }
            return (Resources) field.get(ta);
        } catch (Throwable ignored) {
            return null;
        }
    }
}
