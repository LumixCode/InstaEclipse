package ps.reso.instaeclipse.mods.devops;

import org.luckypray.dexkit.query.FindMethod;
import org.luckypray.dexkit.query.matchers.MethodMatcher;

import java.lang.reflect.Method;
import java.util.List;

import ps.reso.instaeclipse.xposedcompat.XC_MethodHook;
import ps.reso.instaeclipse.xposedcompat.XposedBridge;
import ps.reso.instaeclipse.utils.core.DexKitCache;
import ps.reso.instaeclipse.utils.feature.FeatureFlags;
import ps.reso.instaeclipse.utils.feature.FeatureStatusTracker;
import ps.reso.instaeclipse.utils.log.ModuleLog;
import ps.reso.instaeclipse.utils.core.DexKitQueryCache;
import ps.reso.instaeclipse.utils.core.DexKitQueryCache.Found;

public class BuildExpiredPopupHook {

    private static final String CACHE_SHOW  = "BuildExpiredShow";
    private static final String CACHE_SNOOZE = "BuildExpiredSnooze_v1";
    private static final String CACHE_SNOOZE_DISABLED = "BuildExpiredSnoozeDisabled_v1";

    public void install(ClassLoader classLoader) {

        // No-op the method that shows the popup — blocks all three internal paths:
        //   1. lockout_active pref = true  → shows immediately
        //   2. snooze expired              → shows via snooze dialog
        //   3. age threshold exceeded      → shows force-update dialog
        XC_MethodHook noOpHook = new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) {
                if (FeatureFlags.removeBuildExpiredPopup) {
                    param.setResult(null); // void return → method becomes no-op
                }
            }
        };

        // Secondary defence: hook the snooze-expired boolean check.
        // Returns false so even if the show method is not found, the snooze
        // check keeps reporting "not expired".
        XC_MethodHook falseHook = new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) {
                if (FeatureFlags.removeBuildExpiredPopup) {
                    param.setResult(false);
                }
            }
        };

        boolean hookedMain = false;

        // ── Cache path ────────────────────────────────────────────────────────
        if (DexKitCache.isCacheValid()) {
            Method show = DexKitCache.loadMethod(CACHE_SHOW, classLoader);
            if (show != null) {
                XposedBridge.hookMethod(show, noOpHook);
                FeatureStatusTracker.setHooked("RemoveBuildExpiredPopup");
                for (String key : List.of(CACHE_SNOOZE, CACHE_SNOOZE_DISABLED)) {
                    Method check = DexKitCache.loadMethod(key, classLoader);
                    if (check != null) XposedBridge.hookMethod(check, falseHook);
                }
                return;
            }
        }

        // ── DexKit path ───────────────────────────────────────────────────────
        try {
            // Primary: find the show-popup method via "lockout_active" string.
            List<Found> showMethods = DexKitQueryCache.methods(FindMethod.create()
                    .matcher(MethodMatcher.create()
                            .usingStrings("lockout_active")
                            .returnType("void")));

            for (Found md : showMethods) {
                Method method;
                try { method = md.getMethodInstance(classLoader); } catch (Throwable e) { continue; }

                Class<?>[] params = method.getParameterTypes();
                if (params.length < 1) continue;
                // Must take FragmentActivity as first arg; skip boolean-only variants
                if (!params[0].getName().contains("FragmentActivity")) continue;

                XposedBridge.hookMethod(method, noOpHook);
                DexKitCache.saveMethod(CACHE_SHOW, method);
                ModuleLog.ok("(IE|BuildExpired) ready");
                ModuleLog.detail("(IE|BuildExpired) hooked show-popup → "
                        + md.getClassName() + "." + md.getName());
                FeatureStatusTracker.setHooked("RemoveBuildExpiredPopup");
                hookedMain = true;
                break;
            }

            if (!hookedMain) {
                ModuleLog.warn("(IE|BuildExpired) The popup itself was not found in this Instagram version; only its expiry check is turned off");
            }

            boolean snooze = hookCheck(classLoader, "snooze_expiration_lockout_manager", CACHE_SNOOZE, falseHook);
            hookCheck(classLoader, "disabled_snooze_expiration_lockout_manager", CACHE_SNOOZE_DISABLED, falseHook);
            if (!hookedMain && snooze) FeatureStatusTracker.setHooked("RemoveBuildExpiredPopup");
        } catch (Throwable t) {
            ModuleLog.error("(IE|BuildExpired) Could not block the expired-version popup", t);
        }
    }

    private static boolean hookCheck(ClassLoader classLoader, String key, String cacheKey,
                                     XC_MethodHook hook) {
        try {
            List<Found> checks = DexKitQueryCache.methods(FindMethod.create()
                    .matcher(MethodMatcher.create().usingEqStrings(key).returnType("boolean")));
            if (checks.size() != 1) {
                ModuleLog.detail("(IE|BuildExpired) " + key + " check: " + checks.size() + " match(es), not hooked");
                return false;
            }
            Method method = checks.get(0).getMethodInstance(classLoader);
            XposedBridge.hookMethod(method, hook);
            DexKitCache.saveMethod(cacheKey, method);
            ModuleLog.detail("(IE|BuildExpired) hooked " + key + " check → "
                    + method.getDeclaringClass().getName() + "." + method.getName());
            return true;
        } catch (Throwable t) {
            ModuleLog.detail("(IE|BuildExpired) " + key + " check not hooked: " + t);
            return false;
        }
    }
}
