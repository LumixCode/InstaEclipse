package ps.reso.instaeclipse.mods.ui.theme;

import android.app.Activity;
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

public class IgThemeHook {

    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static Runnable pendingRecreate;
    private static boolean themeShown;
    private static boolean lazyTried;
    private static volatile ClassLoader hostLoader;
    private static volatile boolean installed;
    private static volatile Field typedArrayResourcesField;

    public void install(ClassLoader classLoader) {
        if (installed) return;
        hostLoader = classLoader;
        themeShown = FeatureFlags.customThemeEnabled;
        if (!FeatureFlags.customThemeEnabled) {
            ModuleLog.detail("(InstaEclipse | Theme): off -> hooks not installed");
            return;
        }
        try {
            hookResolveAttribute(classLoader);
            hookGetColor();
            hookTypedArrayGetColor();
            IgThemeColorSourceHooks.install(classLoader);
            hookActivityLifecycle();
            if (Build.VERSION.SDK_INT < 35) hookPhoneWindowColors(classLoader);
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
                    protected void afterHookedMethod(MethodHookParam param) {
                        if (IgColorRemapEngine.isBypassing() || !IgThemeEngine.themeEnabled()) return;
                        int attrId = (Integer) param.args[0];
                        TypedValue out = (TypedValue) param.args[1];
                        if (out == null) return;
                        if (!IgThemeEngine.isInitialized()) {
                            IgThemeEngine.ensureInitialized(((Resources.Theme) param.thisObject).getResources());
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

    private void hookGetColor() {
        XC_MethodHook hook = new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) {
                if (!IgThemeEngine.themeEnabled() || IgColorRemapEngine.isBypassing()) return;
                if (!IgThemeEngine.isInitialized()) IgThemeEngine.ensureInitialized((Resources) param.thisObject);
                Integer override = IgThemeEngine.colorForResource((Integer) param.args[0]);
                if (override != null) param.setResult(override);
            }

            @Override
            protected void afterHookedMethod(MethodHookParam param) {
                if (!IgThemeEngine.themeEnabled() || IgColorRemapEngine.isBypassing()
                        || param.getThrowable() != null || !IgColorRemapEngine.isReady()) return;
                if (param.getResult() instanceof Integer resolved) {
                    int themed = IgThemeEngine.themedColor((Integer) param.args[0], resolved,
                            IgThemeEngine::colorForResource, IgColorRemapEngine::remap);
                    if (themed != resolved) param.setResult(themed);
                }
            }
        };
        try {
            XposedHelpers.findAndHookMethod(Resources.class, "getColor", int.class, Resources.Theme.class, hook);
        } catch (Throwable ignored) {}
    }

    private void hookTypedArrayGetColor() {
        XposedHelpers.findAndHookMethod(TypedArray.class, "getColor", int.class, int.class, new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam param) {
                if (IgColorRemapEngine.isBypassing() || !IgThemeEngine.themeEnabled()
                        || param.getThrowable() != null) return;
                try {
                    TypedArray ta = (TypedArray) param.thisObject;
                    int index = (Integer) param.args[0];
                    if (!IgThemeEngine.isInitialized()) {
                        Resources res = typedArrayResources(ta);
                        IgThemeEngine.ensureInitialized(res);
                    }
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
            protected void afterHookedMethod(MethodHookParam param) {
                Activity activity = (Activity) param.thisObject;
                IgThemeEngine.refreshNight(activity);
                if (!IgThemeEngine.isActive()) return;
                IgThemeEngine.ensureInitialized(activity);
                IgColorRemapEngine.ensureBuilt(activity);
                applyWindowColors(activity);
            }
        });
        XposedHelpers.findAndHookMethod(Activity.class, "onCreate", Bundle.class, new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) {
                Activity activity = (Activity) param.thisObject;
                IgThemeEngine.refreshNight(activity);
                if (!IgThemeEngine.isActive()) return;
                IgThemeEngine.ensureInitialized(activity);
                IgColorRemapEngine.ensureBuilt(activity);
            }

            @Override
            protected void afterHookedMethod(MethodHookParam param) {
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
            protected void beforeHookedMethod(MethodHookParam param) {
                if (IgThemeEngine.isActive()) param.args[0] = IgThemeEngine.getActivePalette().statusBar;
            }
        };
        XC_MethodHook navHook = new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) {
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

    public static boolean refreshCurrentActivity() {
        boolean wasShown = themeShown;
        themeShown = FeatureFlags.customThemeEnabled;
        if (themeShown && !installed && !lazyTried && hostLoader != null) {
            lazyTried = true;
            new IgThemeHook().install(hostLoader);
        }
        Activity activity = UIHookManager.getCurrentActivity();
        if (activity == null || activity.isFinishing() || activity.isDestroyed()) return false;
        IgThemeEngine.refreshNight(activity);
        if (!wasShown && !themeShown) return false;
        IgThemeEngine.ensureInitialized(activity);
        if (themeShown) {
            IgColorRemapEngine.ensureBuilt(activity);
            applyWindowColors(activity);
        }
        if (pendingRecreate != null) MAIN.removeCallbacks(pendingRecreate);
        pendingRecreate = () -> {
            pendingRecreate = null;
            if (!activity.isFinishing() && !activity.isDestroyed()) activity.recreate();
        };
        MAIN.postDelayed(pendingRecreate, 300);
        return true;
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
