package ps.reso.instaeclipse.mods.extras;

import android.Manifest;
import android.app.Activity;
import android.content.Context;
import android.content.pm.PackageManager;
import android.os.Handler;
import android.os.Looper;

import org.luckypray.dexkit.DexKitBridge;
import org.luckypray.dexkit.query.FindMethod;
import org.luckypray.dexkit.query.matchers.MethodMatcher;
import org.luckypray.dexkit.result.MethodData;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import ps.reso.instaeclipse.xposedcompat.XC_MethodHook;
import ps.reso.instaeclipse.xposedcompat.XposedBridge;
import ps.reso.instaeclipse.xposedcompat.XposedHelpers;
import ps.reso.instaeclipse.utils.core.DexKitCache;
import ps.reso.instaeclipse.utils.core.JitGuard;
import ps.reso.instaeclipse.utils.feature.FeatureFlags;
import ps.reso.instaeclipse.utils.feature.FeatureStatusTracker;
import ps.reso.instaeclipse.utils.log.ModuleLog;

/**
 * Skips Instagram's server-sent "Set up on new device" pages for contacts sync and location
 * services, and, while Instagram has no location access, answers its location asks as denied
 * without showing anything (the former "Block location permission prompts" switch).
 *
 * The pages: the server sends them again on every start while their "seen" event never arrives
 * (Block Analytics drops it), so they are cancelled where Instagram opens Bloks screens, by app id:
 * the screen opener's {@code (Context, IgBloksScreenConfig)} launchers (full screen, which logs
 * "BKDataFetcher.fetch", bottom sheet and CDS sheet) and the CDS sheet presenter, which Bloks
 * "open screen" actions also call directly. The approach follows HushGram's SetupScreens routes.
 *
 * The asks: LocationPluginImpl owns Instagram's own pre-prompt sheet and the "go to settings"
 * dialog that comes back after a permanent denial; Activity.requestPermissions catches the OS
 * dialog from every other surface (maps, Friend Map, onboarding, React Native screens). Both step
 * aside once location is granted.
 */
public final class OnboardingPromptHook {

    private static final String TAG = "(InstaEclipse | Onboarding): ";
    private static final String FLAG = "hideOnboardingPrompts";
    // v2: the opener's other launchers and the CDS presenter joined the BKDataFetcher launcher.
    private static final String CACHE = "Extras_OnboardingPrompt_v2";
    private static final String BLOKS_CONFIG = "com.instagram.bloks.hosting.IgBloksScreenConfig";
    // Not obfuscated; its Activity-taking request* overloads (1 + 4) are the same on 446 and 451.
    private static final String LOCATION_PLUGIN = "com.instagram.location.impl.LocationPluginImpl";

    // Activity.requestPermissions is a framework method: hooked twice it would answer twice.
    private static volatile boolean installed;
    private static final ThreadLocal<Boolean> answeringSilently = new ThreadLocal<>();

    public void install(DexKitBridge bridge, ClassLoader cl) {
        if (installed) return;
        installed = true;
        List<Method> targets = DexKitCache.isCacheValid() ? DexKitCache.loadMethods(CACHE, cl) : null;
        if (targets == null || targets.isEmpty()) {
            targets = findTargets(bridge, cl);
            if (!targets.isEmpty()) DexKitCache.saveMethods(CACHE, targets);
        }
        int hooked = 0;
        for (Method m : targets) {
            try {
                XposedBridge.hookMethod(m, SKIP_ONBOARDING);
                hooked++;
            } catch (Throwable t) {
                ModuleLog.line(TAG + "could not hook " + m.getName() + ": " + t);
            }
        }
        int asks = hookLocationPlugin(cl) + hookPermissionRequests();
        ModuleLog.line(TAG + "location ask hooks: " + asks);
        // The setup pages are what the switch promises; the ask layers only add to them.
        if (hooked == 0) {
            ModuleLog.line(TAG + "❌ Bloks screen opener not found");
            if (FeatureFlags.hideOnboardingPrompts) FeatureStatusTracker.setBroken(FLAG);
            return;
        }
        FeatureStatusTracker.setHooked(FLAG);
        ModuleLog.line(TAG + "✅ hooked " + hooked + " Bloks screen opener(s)");
    }

    // A launcher's opener holds the app id; the static presenter gets it in its screen-data argument.
    private static final XC_MethodHook SKIP_ONBOARDING = new XC_MethodHook() {
        @Override
        protected void beforeHookedMethod(MethodHookParam p) {
            JitGuard.tick(p.method);
            if (!FeatureFlags.hideOnboardingPrompts) return;
            String id = OnboardingPromptPolicy.skippedScreen(p.thisObject);
            // args[0] is the Context in every hooked shape, often an Instagram activity: not read.
            for (int i = 1; id == null && i < p.args.length; i++) id = OnboardingPromptPolicy.skippedScreen(p.args[i]);
            if (id == null) return;
            p.setResult(null);
            ModuleLog.line(TAG + "skipped " + id + " at "
                    + p.method.getDeclaringClass().getName() + "." + p.method.getName());
        }
    };

    private static List<Method> findTargets(DexKitBridge bridge, ClassLoader cl) {
        List<Method> targets = new ArrayList<>();
        for (MethodData md : bridge.findMethod(FindMethod.create().matcher(MethodMatcher.create()
                .usingStrings("BKDataFetcher.fetch")
                .paramTypes("android.content.Context", BLOKS_CONFIG)
                .returnType("void")))) {
            try {
                for (Method m : md.getMethodInstance(cl).getDeclaringClass().getDeclaredMethods()) {
                    if (isLauncher(m) && !targets.contains(m)) targets.add(m);
                }
            } catch (Throwable ignored) {}
        }
        for (MethodData md : bridge.findMethod(FindMethod.create().matcher(MethodMatcher.create()
                .usingStrings("FragmentActivity is required to open CDS bottom sheet", "cds_bloks")
                .returnType("void")))) {
            try {
                Method m = md.getMethodInstance(cl);
                if (isPresenter(m)) targets.add(m);
            } catch (Throwable ignored) {}
        }
        return targets;
    }

    /** The opener's {@code void (Context, IgBloksScreenConfig)} instance methods, one per route. */
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

    /** Instagram's own location sheet and "go to settings" dialog: returns silently, then DENIED. */
    private static int hookLocationPlugin(ClassLoader loader) {
        Class<?> plugin;
        try {
            plugin = loader.loadClass(LOCATION_PLUGIN);
        } catch (Throwable t) {
            ModuleLog.line(TAG + "LocationPluginImpl not found; OS-dialog layer only");
            return 0;
        }
        int count = 0;
        for (Method method : plugin.getDeclaredMethods()) {
            String name = method.getName();
            if (!name.equals("requestLocationPermission") && !name.equals("requestLocationUpdates")) continue;
            Class<?>[] types = method.getParameterTypes();
            // Overloads without an Activity only read an existing grant; they never prompt.
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
                        JitGuard.tick(param.method);
                        if (!FeatureFlags.hideOnboardingPrompts) return;
                        try {
                            if (!(param.args[activityIndex] instanceof Activity activity) || hasLocationGrant(activity)) return;
                            param.setResult(null);
                            ModuleLog.line(TAG + "blocked " + name + " prompt from " + appCaller());
                            Object callback = cbIndex >= 0 ? param.args[cbIndex] : null;
                            if (callback == null) return;
                            // Posted: the real flow answers after user input, never re-entrantly.
                            new Handler(Looper.getMainLooper()).post(() -> {
                                try {
                                    onResult.invoke(callback, denied);
                                } catch (Throwable t) {
                                    ModuleLog.line(TAG + "denial callback failed: " + t.getClass().getSimpleName());
                                }
                            });
                        } catch (Throwable t) {
                            ModuleLog.line(TAG + "plugin block failed: " + t.getClass().getSimpleName());
                        }
                    }
                });
                count++;
            } catch (Throwable t) {
                ModuleLog.line(TAG + "could not hook " + method + ": " + t);
            }
        }
        ModuleLog.line(TAG + "LocationPluginImpl hooks: " + count);
        return count;
    }

    /** The OS location dialog from every other surface: a location-only ask is answered DENIED. */
    private static int hookPermissionRequests() {
        try {
            XposedHelpers.findAndHookMethod(Activity.class, "requestPermissions", String[].class, int.class,
                    new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) {
                            JitGuard.tick(param.method);
                            if (!FeatureFlags.hideOnboardingPrompts) return;
                            try {
                                Activity activity = (Activity) param.thisObject;
                                String[] requested = (String[]) param.args[0];
                                String[] remaining = OnboardingPromptPolicy.withoutUngrantedLocation(requested,
                                        p -> activity.checkSelfPermission(p) == PackageManager.PERMISSION_GRANTED);
                                if (remaining == requested) return;
                                if (remaining.length > 0) {
                                    // A request that also asks for something else goes to Android as
                                    // made: a caller may read grantResults by position, or unbox a
                                    // missing entry of a by-name result map, so dropping its location
                                    // entry could crash Instagram's permission callback.
                                    ModuleLog.line(TAG + "left mixed permission request "
                                            + Arrays.toString(requested) + " from " + appCaller());
                                    return;
                                }
                                param.setResult(null);
                                ModuleLog.line(TAG + "blocked OS location dialog "
                                        + Arrays.toString(requested) + " from " + appCaller());
                                int requestCode = (int) param.args[1];
                                int[] results = new int[requested.length];
                                Arrays.fill(results, PackageManager.PERMISSION_DENIED);
                                new Handler(Looper.getMainLooper()).post(() -> {
                                    if (activity.isDestroyed()) return;
                                    // Outside the hook callback, so a throwing handler would crash IG.
                                    answeringSilently.set(true);
                                    try {
                                        activity.onRequestPermissionsResult(requestCode, requested, results);
                                    } catch (Throwable t) {
                                        ModuleLog.line(TAG + "denial delivery failed: " + t.getClass().getSimpleName());
                                    } finally {
                                        answeringSilently.remove();
                                    }
                                });
                            } catch (Throwable t) {
                                ModuleLog.line(TAG + "OS-dialog block failed: " + t.getClass().getSimpleName());
                            }
                        }
                    });
            // A permanently denied permission has no rationale, so Instagram reads our silent denial
            // as "never ask again" and opens its own "go to settings" dialog, the very nag being
            // blocked. Only while our answer is delivered does it read as an ordinary denial.
            XposedHelpers.findAndHookMethod(Activity.class, "shouldShowRequestPermissionRationale", String.class,
                    new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            JitGuard.tick(param.method);
                            if (Boolean.TRUE.equals(answeringSilently.get())
                                    && OnboardingPromptPolicy.isLocationPermission((String) param.args[0])) {
                                param.setResult(true);
                            }
                        }
                    });
            return 1;
        } catch (Throwable t) {
            ModuleLog.line(TAG + "could not hook Activity.requestPermissions: " + t);
            return 0;
        }
    }

    // Framework, Kotlin, hook-framework and module frames; the frame after them is Instagram's.
    private static final String[] NON_APP_FRAMES = {
            "java.", "android.", "androidx.", "dalvik.", "com.android.", "kotlin.",
            "de.robv.", "io.github.libxposed.", "org.lsposed.", "ps.reso.", "LSPHooker", "Vector_", "J."};

    /** First Instagram frame on the stack, so a report names which feature asked. Blocks are rare. */
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
