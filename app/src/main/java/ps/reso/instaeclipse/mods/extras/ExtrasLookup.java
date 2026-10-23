package ps.reso.instaeclipse.mods.extras;

import org.luckypray.dexkit.DexKitBridge;
import org.luckypray.dexkit.query.FindMethod;
import org.luckypray.dexkit.query.matchers.MethodMatcher;
import org.luckypray.dexkit.result.MethodData;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.List;
import java.util.function.Predicate;

import ps.reso.instaeclipse.utils.core.DexKitCache;
import ps.reso.instaeclipse.utils.feature.FeatureStatusTracker;
import ps.reso.instaeclipse.utils.log.ModuleLog;
import ps.reso.instaeclipse.utils.core.DexKitQueryCache;

/**
 * DexKit lookups shared by the "Extras" hooks: resolve once, cache the result per IG version,
 * and remember misses so a feature IG removed doesn't cost a DexKit scan on every launch.
 */
final class ExtrasLookup {

    interface Finder {
        Method find() throws Throwable;
    }

    private ExtrasLookup() {}

    static Method method(String key, String feature, boolean enabled, ClassLoader cl, Finder finder) {
        if (DexKitCache.isCacheValid()) {
            Method cached = DexKitCache.loadMethod(key, cl);
            if (cached != null) return cached;
            if ("missing".equals(DexKitCache.loadString(key))) return missing(feature, enabled);
        }
        Method m = null;
        try {
            m = finder.find();
        } catch (Throwable t) {
            ModuleLog.detail("(InstaEclipse | Extras): " + key + " lookup failed: " + t);
        }
        if (m != null) {
            DexKitCache.saveMethod(key, m);
            return m;
        }
        DexKitCache.saveString(key, "missing");
        String message = "(InstaEclipse | Extras): " + key + " is not available in this Instagram version, so that feature will not work";
        if (enabled) ModuleLog.error(message);
        else ModuleLog.detail(message);
        return missing(feature, enabled);
    }

    private static Method missing(String feature, boolean enabled) {
        if (enabled) FeatureStatusTracker.setBroken(feature);
        return null;
    }

    /** Methods whose body references every given string. */
    static List<MethodData> byStrings(DexKitBridge bridge, String... strings) {
        return DexKitQueryCache.findMethod(bridge, FindMethod.create().matcher(MethodMatcher.create().usingStrings(strings)));
    }

    /** First method invoked by {@code owner} that is static and satisfies {@code test}. */
    static Method firstStaticInvoke(MethodData owner, ClassLoader cl, Predicate<Method> test) {
        for (MethodData inv : DexKitQueryCache.invokes(owner)) {
            if (!inv.isMethod()) continue;
            try {
                Method m = inv.getMethodInstance(cl);
                if (Modifier.isStatic(m.getModifiers()) && test.test(m)) return m;
            } catch (Throwable ignored) {
                // unresolvable framework/synthetic reference
            }
        }
        return null;
    }
}
