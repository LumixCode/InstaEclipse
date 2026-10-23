package ps.reso.instaeclipse.mods.ads;

import org.luckypray.dexkit.DexKitBridge;
import org.luckypray.dexkit.query.FindMethod;
import org.luckypray.dexkit.query.matchers.MethodMatcher;
import org.luckypray.dexkit.result.MethodData;

import java.lang.reflect.Method;
import java.util.List;

import ps.reso.instaeclipse.xposedcompat.XC_MethodHook;
import ps.reso.instaeclipse.xposedcompat.XposedBridge;
import ps.reso.instaeclipse.utils.core.DexKitCache;
import ps.reso.instaeclipse.utils.feature.FeatureFlags;
import ps.reso.instaeclipse.utils.feature.FeatureStatusTracker;
import ps.reso.instaeclipse.utils.log.ModuleLog;
import ps.reso.instaeclipse.utils.core.DexKitQueryCache;

public class AdBlocker {

    // Marker string referenced inside the ad-insertion decision method.
    // IG >= 437 refactored SponsoredContentController and dropped the old
    // "SponsoredContentController.insertItem" trace tag, so we try the new
    // marker first and fall back to the legacy one for older installs.
    private static final String[] INSERT_ITEM_MARKERS = {
            "Is ad pod",
            "SponsoredContentController.insertItem"
    };

    public void disableSponsoredContent(DexKitBridge bridge, ClassLoader classLoader) {
        XC_MethodHook hook = new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) throws Throwable { ps.reso.instaeclipse.utils.core.JitGuard.tick(param.method);
                if (FeatureFlags.isAdBlockEnabled) param.setResult(false);
            }
        };

        if (DexKitCache.isCacheValid()) {
            Method cached = DexKitCache.loadMethod("AdBlocker", classLoader);
            if (cached != null) {
                XposedBridge.hookMethod(cached, hook);
                ModuleLog.ok("(InstaEclipse | AdBlocker): ready");
                FeatureStatusTracker.setHooked("AdBlocker");
                return;
            }
        }

        try {
            for (String marker : INSERT_ITEM_MARKERS) {
                List<MethodData> methods = DexKitQueryCache.findMethod(bridge, 
                        FindMethod.create().matcher(
                                MethodMatcher.create().usingStrings(marker)
                        )
                );

                if (methods.isEmpty()) {
                    ModuleLog.detail("(InstaEclipse | AdBlocker): no methods found referencing '" + marker + "'");
                    continue;
                }

                for (MethodData method : methods) {
                    String returnType = String.valueOf(method.getReturnType());
                    if (!returnType.contains("boolean")) continue;

                    try {
                        Method targetMethod = method.getMethodInstance(classLoader);
                        DexKitCache.saveMethod("AdBlocker", targetMethod);
                        XposedBridge.hookMethod(targetMethod, hook);

                        ModuleLog.ok("(InstaEclipse | AdBlocker): ready");
                        ModuleLog.detail("(InstaEclipse | AdBlocker): hooked (marker='" + marker + "'): " +
                                method.getClassName() + "." + method.getName());
                        FeatureStatusTracker.setHooked("AdBlocker");
                        return; // Stop after first successful hook

                    } catch (Throwable hookEx) {
                        ModuleLog.detail("(InstaEclipse | AdBlocker): candidate failed: " +
                                method.getName() + " → " + hookEx.getMessage());
                    }
                }
            }

            ModuleLog.error("(InstaEclipse | AdBlocker): Could not turn on ad blocking: not supported by this Instagram version");
            if (FeatureFlags.isAdBlockEnabled) FeatureStatusTracker.setBroken("AdBlocker");

        } catch (Throwable t) {
            ModuleLog.error("(InstaEclipse | AdBlocker): Could not turn on ad blocking", t);
        }
    }
}
