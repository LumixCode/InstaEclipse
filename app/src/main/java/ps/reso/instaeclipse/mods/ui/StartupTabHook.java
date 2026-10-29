package ps.reso.instaeclipse.mods.ui;

import android.app.Activity;
import android.content.Intent;

import java.lang.reflect.Method;

import ps.reso.instaeclipse.xposedcompat.XC_MethodHook;
import ps.reso.instaeclipse.xposedcompat.XposedBridge;
import ps.reso.instaeclipse.utils.feature.FeatureFlags;
import ps.reso.instaeclipse.utils.feature.FeatureStatusTracker;
import ps.reso.instaeclipse.utils.log.ModuleLog;

public class StartupTabHook {

    private static final String TAG = "(InstaEclipse | StartupTab): ";

    public static final String[] TABS = {"", "FEED", "CLIPS", "DIRECT", "SEARCH", "PROFILE", "NEWS"};

    static final String EXTRA = "MainActivityAccountHelper.STARTUP_TAB";
    static final String ENTRANCE_EXTRA = "INSTAGRAM_MAIN_ACTIVITY_INTENT_ENTRANCE_MODULE_EXTRA_FIELD";
    static final String MARK = "ie_startup_tab";
    private static final String APPLIED = "ie_startup_tab_applied";

    private static final String MAIN = "com.instagram.android.activity.MainTabActivity";
    private static final String MAIN_CLASS = "com.instagram.mainactivity.InstagramMainActivity";

    private static boolean isMainActivity(Activity activity) {
        String cls = activity.getClass().getName();
        if (MAIN_CLASS.equals(cls)) return true;
        android.content.ComponentName cn = activity.getComponentName();
        return cn != null && MAIN.equals(cn.getClassName());
    }

    public void install(ClassLoader cl) {
        XC_MethodHook hook = new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) {
                if (FeatureFlags.startupTab.isEmpty() || !(param.args[0] instanceof Activity)) return;
                if (param.args.length > 1 && param.args[1] != null) return;
                Activity activity = (Activity) param.args[0];
                if (!isMainActivity(activity)) return;
                Intent intent = activity.getIntent();
                if (intent == null || intent.hasExtra(EXTRA) || intent.hasExtra(ENTRANCE_EXTRA)) return;
                if (!Intent.ACTION_MAIN.equals(intent.getAction())) return;
                String tab = StartupTabPolicy.tabToOpen(FeatureFlags.startupTab, StartupTabPolicy::flagOn);
                if (tab.isEmpty()) return;
                intent.putExtra(EXTRA, tab);
                intent.putExtra(ENTRANCE_EXTRA, tab);
                intent.putExtra(MARK, true);
            }
        };
        int hooked = 0;
        for (Method m : ps.reso.instaeclipse.utils.core.SafeReflect.methods(android.app.Instrumentation.class)) {
            if (m.getName().equals("callActivityOnCreate")) {
                XposedBridge.hookMethod(m, hook);
                hooked++;
            }
        }
        if (hooked == 0) {
            ModuleLog.error(TAG + "Will not work: Android's screen start method was not found");
            ModuleLog.detail(TAG + "callActivityOnCreate not found");
            return;
        }
        try {
            java.util.Set<String> coldStart = new java.util.HashSet<>(
                    java.util.Arrays.asList("is_cold_start_feed", "is_cold_start_reel_tab"));
            XposedBridge.hookMethod(Intent.class.getDeclaredMethod("getBooleanExtra", String.class, boolean.class),
                    new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) {
                            if (FeatureFlags.startupTab.isEmpty()) return;
                            if (coldStart.contains(param.args[0]) && ((Intent) param.thisObject).hasExtra(MARK)) {
                                param.setResult(false);
                            }
                        }
                    });
        } catch (NoSuchMethodException e) {
            ModuleLog.warn(TAG + "Instagram may still open on its default tab after a fresh start");
            ModuleLog.detail(TAG + "Intent.getBooleanExtra missing");
        }
        for (Method m : ps.reso.instaeclipse.utils.core.SafeReflect.methods(android.app.Instrumentation.class)) {
            if (!m.getName().equals("callActivityOnResume")) continue;
            XposedBridge.hookMethod(m, new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    if (FeatureFlags.startupTab.isEmpty() || !(param.args[0] instanceof Activity)) return;
                    Activity activity = (Activity) param.args[0];
                    if (!isMainActivity(activity)) return;
                    Intent intent = activity.getIntent();
                    if (intent == null || !intent.hasExtra(MARK) || intent.hasExtra(APPLIED)) return;
                    String tab = StartupTabPolicy.tabToOpen(FeatureFlags.startupTab, StartupTabPolicy::flagOn);
                    if (tab.isEmpty()) return;
                    intent.putExtra(APPLIED, true);
                    activity.getWindow().getDecorView().post(() -> switchTab(activity, tab));
                }
            });
        }
        FeatureStatusTracker.setHooked("StartupTab");
        ModuleLog.ok(TAG + "ready");
        ModuleLog.detail(TAG + "hooked Instrumentation.callActivityOnCreate x" + hooked);
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    static void switchTab(Activity activity, String tab) {
        for (Class<?> c = activity.getClass(); c != null && c != Activity.class; c = c.getSuperclass()) {
            for (java.lang.reflect.Field f : ps.reso.instaeclipse.utils.core.SafeReflect.fields(c)) {
                Class<?> type = f.getType();
                if (type.isPrimitive() || type.getName().startsWith("android.") || type.getName().startsWith("java.")) continue;
                for (Method m : ps.reso.instaeclipse.utils.core.SafeReflect.methods(type)) {
                    Class<?>[] p = m.getParameterTypes();
                    if (m.getReturnType() != void.class || p.length != 3 || !p[0].isEnum()
                            || p[1] != String.class || p[2] != boolean.class) continue;
                    try {
                        Object value = Enum.valueOf((Class<? extends Enum>) p[0], tab);
                        f.setAccessible(true);
                        Object controller = f.get(activity);
                        if (controller == null) continue;
                        m.setAccessible(true);
                        m.invoke(controller, value, null, false);
                        ModuleLog.line(TAG + "Opened on the startup tab: " + tab);
                        return;
                    } catch (IllegalArgumentException notThisEnum) {} catch (Throwable t) {
                        ModuleLog.warn(TAG + "Could not switch to the startup tab: " + t.getClass().getSimpleName() + ": " + t.getMessage());
                        return;
                    }
                }
            }
        }
        ModuleLog.warn(TAG + "Could not switch to the startup tab (tab switcher not found in this Instagram version)");
    }
}
