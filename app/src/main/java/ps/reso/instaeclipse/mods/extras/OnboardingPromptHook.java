package ps.reso.instaeclipse.mods.extras;

import android.Manifest;
import android.app.Activity;
import android.content.Context;
import android.content.pm.PackageManager;
import android.os.Handler;
import android.os.Looper;

import org.luckypray.dexkit.query.FindMethod;
import org.luckypray.dexkit.query.matchers.MethodMatcher;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import ps.reso.instaeclipse.xposedcompat.XC_MethodHook;
import ps.reso.instaeclipse.xposedcompat.XposedBridge;
import ps.reso.instaeclipse.xposedcompat.XposedHelpers;
import ps.reso.instaeclipse.utils.core.DexKitCache;
import ps.reso.instaeclipse.utils.core.DexKitQueryCache;
import ps.reso.instaeclipse.utils.core.DexKitQueryCache.Found;
import ps.reso.instaeclipse.utils.feature.FeatureFlags;
import ps.reso.instaeclipse.utils.feature.FeatureStatusTracker;
import ps.reso.instaeclipse.utils.log.ModuleLog;

public final class OnboardingPromptHook {

    private static final String TAG = "(InstaEclipse | Onboarding): ";
    private static final String FLAG = "hideOnboardingPrompts";
    private static final String CACHE = "Extras_OnboardingPrompt_v2";
    private static final String BLOKS_CONFIG = "com.instagram.bloks.hosting.IgBloksScreenConfig";
    private static final String LOCATION_PLUGIN = "com.instagram.location.impl.LocationPluginImpl";

    private static volatile boolean installed;
    private static final ThreadLocal<Boolean> answeringSilently = new ThreadLocal<>();

    public void install(ClassLoader cl) {
        if (installed) return;
        installed = true;
        List<Method> targets = DexKitCache.isCacheValid() ? DexKitCache.loadMethods(CACHE, cl) : null;
        if (targets == null || targets.isEmpty()) {
            targets = findTargets(cl);
            if (!targets.isEmpty()) DexKitCache.saveMethods(CACHE, targets);
        }
        int hooked = 0;
        for (Method m : targets) {
            try {
                XposedBridge.hookMethod(m, SKIP_ONBOARDING);
                hooked++;
            } catch (Throwable t) {
                ModuleLog.warn(TAG + "One of the ways Instagram opens setup pages could not be blocked; the others still are");
                ModuleLog.detail(TAG + "could not hook " + m.getName() + ": " + t);
            }
        }
        int asks = hookLocationPlugin(cl) + hookPermissionRequests();
        ModuleLog.detail(TAG + "location ask hooks: " + asks);
        if (hooked == 0) {
            ModuleLog.error(TAG + "Could not skip setup pages: the screen opener was not found in this Instagram version");
            if (FeatureFlags.hideOnboardingPrompts) FeatureStatusTracker.setBroken(FLAG);
            return;
        }
        FeatureStatusTracker.setHooked(FLAG);
        ModuleLog.ok(TAG + "ready");
        ModuleLog.detail(TAG + "hooked " + hooked + " Bloks screen opener(s)");
    }

    private static final XC_MethodHook SKIP_ONBOARDING = new XC_MethodHook() {
        @Override
        protected void beforeHookedMethod(MethodHookParam p) {
            if (!FeatureFlags.hideOnboardingPrompts) return;
            String id = OnboardingPromptPolicy.skippedScreen(p.thisObject);
            for (int i = 1; id == null && i < p.args.length; i++) id = OnboardingPromptPolicy.skippedScreen(p.args[i]);
            if (id == null) return;
            p.setResult(null);
            ModuleLog.detail(TAG + "skipped " + id + " at "
                    + p.method.getDeclaringClass().getName() + "." + p.method.getName());
        }
    };

    private static List<Method> findTargets(ClassLoader cl) {
        List<Method> targets = new ArrayList<>();
        for (Found md : DexKitQueryCache.methods(FindMethod.create().matcher(MethodMatcher.create()
                .usingStrings("BKDataFetcher.fetch")
                .paramTypes("android.content.Context", BLOKS_CONFIG)
                .returnType("void")))) {
            try {
                for (Method m : md.getMethodInstance(cl).getDeclaringClass().getDeclaredMethods()) {
                    if (isLauncher(m) && !targets.contains(m)) targets.add(m);
                }
            } catch (Throwable ignored) {}
        }
        for (Found md : DexKitQueryCache.methods(FindMethod.create().matcher(MethodMatcher.create()
                .usingStrings("FragmentActivity is required to open CDS bottom sheet", "cds_bloks")
                .returnType("void")))) {
            try {
                Method m = md.getMethodInstance(cl);
                if (isPresenter(m)) targets.add(m);
            } catch (Throwable ignored) {}
        }
        return targets;
    }

    private static boolean isLauncher(Method m) {
        Class<?>[] types = m.getParameterTypes();
        return !Modifier.isStatic(m.getModifiers()) && m.getReturnType() == void.class && types.length == 2
                && types[0] == Context.class && types[1].getName().equals(BLOKS_CONFIG);
    }

    private static boolean isPresenter(Method m) {
        Class<?>[] types = m.getParameterTypes();
        if (types.length < 2 || types[0] != Context.class) return false;
        for (Class<?> type : types) if (type.getName().equals(BLOKS_CONFIG)) return true;
        return false;
    }

    private static int hookLocationPlugin(ClassLoader loader) {
        Class<?> plugin;
        try {
            plugin = loader.loadClass(LOCATION_PLUGIN);
        } catch (Throwable t) {
            ModuleLog.warn(TAG + "Instagram's own location prompt was not found in this version; Android's location dialog is still blocked");
            ModuleLog.detail(TAG + "LocationPluginImpl not found; OS-dialog layer only");
            return 0;
        }
        int count = 0;
        for (Method method : plugin.getDeclaredMethods()) {
            String name = method.getName();
            if (!name.equals("requestLocationPermission") && !name.equals("requestLocationUpdates")) continue;
            Class<?>[] types = method.getParameterTypes();
            final int activityIndex = Arrays.asList(types).indexOf(Activity.class);
            if (activityIndex < 0) continue;

            int callbackIndex = -1;
            Method resultMethod = null;
            for (int i = 0; i < types.length && resultMethod == null; i++) {
                resultMethod = OnboardingPromptPolicy.resultMethod(types[i]);
                if (resultMethod != null) callbackIndex = i;
            }
            final int cbIndex = callbackIndex;
            final Method onResult = resultMethod;
            final Object denied = onResult != null
                    ? OnboardingPromptPolicy.deniedConstant(onResult.getParameterTypes()[0]) : null;
            try {
                XposedBridge.hookMethod(method, new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) {
                        if (!FeatureFlags.hideOnboardingPrompts) return;
                        try {
                            if (!(param.args[activityIndex] instanceof Activity activity) || hasLocationGrant(activity)) return;
                            param.setResult(null);
                            ModuleLog.detail(TAG + "blocked " + name + " prompt from " + appCaller());
                            Object callback = cbIndex >= 0 ? param.args[cbIndex] : null;
                            if (callback == null) return;
                            new Handler(Looper.getMainLooper()).post(() -> {
                                try {
                                    onResult.invoke(callback, denied);
                                } catch (Throwable t) {
                                    ModuleLog.warn(TAG + "Blocked a location prompt but could not tell Instagram it was declined: " + t.getClass().getSimpleName());
                                }
                            });
                        } catch (Throwable t) {
                            ModuleLog.warn(TAG + "Could not block a location prompt from Instagram: " + t.getClass().getSimpleName());
                        }
                    }
                });
                count++;
            } catch (Throwable t) {
                ModuleLog.warn(TAG + "One of Instagram's location prompts cannot be blocked; the others still are");
                ModuleLog.detail(TAG + "could not hook " + method + ": " + t);
            }
        }
        ModuleLog.detail(TAG + "LocationPluginImpl hooks: " + count);
        return count;
    }

    private static int hookPermissionRequests() {
        try {
            XposedHelpers.findAndHookMethod(Activity.class, "requestPermissions", String[].class, int.class,
                    new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) {
                            if (!FeatureFlags.hideOnboardingPrompts) return;
                            try {
                                Activity activity = (Activity) param.thisObject;
                                String[] requested = (String[]) param.args[0];
                                String[] remaining = OnboardingPromptPolicy.withoutUngrantedLocation(requested,
                                        p -> activity.checkSelfPermission(p) == PackageManager.PERMISSION_GRANTED);
                                if (remaining == requested) return;
                                if (remaining.length > 0) {
                                    ModuleLog.detail(TAG + "left mixed permission request "
                                            + Arrays.toString(requested) + " from " + appCaller());
                                    return;
                                }
                                param.setResult(null);
                                ModuleLog.detail(TAG + "blocked OS location dialog "
                                        + Arrays.toString(requested) + " from " + appCaller());
                                int requestCode = (int) param.args[1];
                                int[] results = new int[requested.length];
                                Arrays.fill(results, PackageManager.PERMISSION_DENIED);
                                new Handler(Looper.getMainLooper()).post(() -> {
                                    if (activity.isDestroyed()) return;
                                    answeringSilently.set(true);
                                    try {
                                        activity.onRequestPermissionsResult(requestCode, requested, results);
                                    } catch (Throwable t) {
                                        ModuleLog.warn(TAG + "Blocked Android's location dialog but could not tell Instagram it was declined: " + t.getClass().getSimpleName());
                                    } finally {
                                        answeringSilently.remove();
                                    }
                                });
                            } catch (Throwable t) {
                                ModuleLog.warn(TAG + "Could not block Android's location dialog: " + t.getClass().getSimpleName());
                            }
                        }
                    });
            XposedHelpers.findAndHookMethod(Activity.class, "shouldShowRequestPermissionRationale", String.class,
                    new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            if (Boolean.TRUE.equals(answeringSilently.get())
                                    && OnboardingPromptPolicy.isLocationPermission((String) param.args[0])) {
                                param.setResult(true);
                            }
                        }
                    });
            return 1;
        } catch (Throwable t) {
            ModuleLog.warn(TAG + "Android's location dialog cannot be blocked; setup pages are still skipped", t);
            return 0;
        }
    }

    private static final String[] NON_APP_FRAMES = {
            "java.", "android.", "androidx.", "dalvik.", "com.android.", "kotlin.",
            "de.robv.", "io.github.libxposed.", "org.lsposed.", "ps.reso.", "LSPHooker", "Vector_", "J."};

    private static String appCaller() {
        for (StackTraceElement frame : Thread.currentThread().getStackTrace()) {
            if (isAppFrame(frame.getClassName())) return frame.getClassName() + "." + frame.getMethodName();
        }
        return "unknown";
    }

    private static boolean isAppFrame(String cls) {
        if (cls.equals(LOCATION_PLUGIN)) return false;
        for (String prefix : NON_APP_FRAMES) if (cls.startsWith(prefix)) return false;
        return true;
    }

    private static boolean hasLocationGrant(Context context) {
        return context.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
                || context.checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED;
    }
}
