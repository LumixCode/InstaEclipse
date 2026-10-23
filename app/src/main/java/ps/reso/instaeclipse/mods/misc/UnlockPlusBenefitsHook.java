package ps.reso.instaeclipse.mods.misc;

import java.lang.reflect.Method;
import java.util.LinkedHashSet;
import java.util.Set;

import org.luckypray.dexkit.DexKitBridge;
import org.luckypray.dexkit.query.FindMethod;
import org.luckypray.dexkit.query.matchers.MethodMatcher;

import ps.reso.instaeclipse.xposedcompat.XC_MethodHook;
import ps.reso.instaeclipse.xposedcompat.XposedBridge;
import ps.reso.instaeclipse.utils.core.DexKitCache;
import ps.reso.instaeclipse.utils.core.DexKitQueryCache;
import ps.reso.instaeclipse.utils.core.JitGuard;
import ps.reso.instaeclipse.utils.feature.FeatureFlags;
import ps.reso.instaeclipse.utils.feature.FeatureStatusTracker;
import ps.reso.instaeclipse.utils.log.ModuleLog;

public final class UnlockPlusBenefitsHook {
    private static final String CACHE_KEY = "UnlockPlus_benefitCheck_v1";
    private static final String ABSENT_KEY = "UnlockPlus_benefitCheck_absent";

    public void install(DexKitBridge bridge, ClassLoader loader) {
        try {
            Method method = DexKitCache.isCacheValid() ? DexKitCache.loadMethod(CACHE_KEY, loader) : null;
            if (method == null) {
                if (AbsentMarker.knownAbsent(DexKitCache.isCacheValid(), DexKitCache.loadString(ABSENT_KEY))) {
                    ModuleLog.error("(IE|Plus) Could not turn on Plus benefits: the benefit check was not found in this Instagram version");
                    if (FeatureFlags.unlockPlusBenefits) FeatureStatusTracker.setBroken("UnlockPlusBenefits");
                    return;
                }
                Set<Method> candidates = new LinkedHashSet<>();
                for (var logger : DexKitQueryCache.findMethod(bridge, FindMethod.create().matcher(MethodMatcher.create()
                        .usingStrings("is_benefit_active", "SUBSBenefitDataProvider")
                        .paramTypes(String.class, String.class).returnType(void.class)))) {
                    for (var caller : DexKitQueryCache.callersUncached(logger)) {
                        if (!caller.getClassName().equals(logger.getClassName())) continue;
                        Method candidate = caller.getMethodInstance(loader);
                        if (isBenefitCheck(candidate)) candidates.add(candidate);
                    }
                }
                if (candidates.isEmpty()) {
                    for (var data : DexKitQueryCache.findMethod(bridge, FindMethod.create().matcher(MethodMatcher.create()
                            .usingStrings("is_benefit_active").paramTypes(String.class).returnType(boolean.class)))) {
                        candidates.add(data.getMethodInstance(loader));
                    }
                }
                if (candidates.size() != 1) {
                    ModuleLog.error("(IE|Plus) Could not turn on Plus benefits: the benefit check was not found in this Instagram version");
                    ModuleLog.detail("(IE|Plus) Expected one benefit checker; found " + candidates.size());
                    DexKitCache.saveString(ABSENT_KEY, AbsentMarker.ABSENT);
                    if (FeatureFlags.unlockPlusBenefits) FeatureStatusTracker.setBroken("UnlockPlusBenefits");
                    return;
                }
                method = candidates.iterator().next();
                DexKitCache.saveMethod(CACHE_KEY, method);
            }
            XposedBridge.hookMethod(method, new XC_MethodHook() {
                @Override protected void beforeHookedMethod(MethodHookParam param) {
                    JitGuard.tick(param.method);
                    if (FeatureFlags.unlockPlusBenefits) param.setResult(true);
                }
            });
            FeatureStatusTracker.setHooked("UnlockPlusBenefits");
            ModuleLog.ok("(IE|Plus) Ready");
            ModuleLog.detail("(IE|Plus) Installed local benefit check: " + method);
        } catch (Throwable t) {
            ModuleLog.error("(IE|Plus) Could not turn on Plus benefits", t);
            if (FeatureFlags.unlockPlusBenefits) FeatureStatusTracker.setBroken("UnlockPlusBenefits");
        }
    }

    private static boolean isBenefitCheck(Method method) {
        return java.lang.reflect.Modifier.isPublic(method.getModifiers())
                && !java.lang.reflect.Modifier.isStatic(method.getModifiers())
                && method.getReturnType() == boolean.class
                && java.util.Arrays.equals(method.getParameterTypes(), new Class<?>[]{String.class});
    }
}
