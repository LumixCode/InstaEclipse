package ps.reso.instaeclipse.mods.ads;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.List;

import org.luckypray.dexkit.query.FindMethod;
import org.luckypray.dexkit.query.matchers.MethodMatcher;

import ps.reso.instaeclipse.utils.core.DexKitCache;
import ps.reso.instaeclipse.utils.core.DexKitQueryCache;
import ps.reso.instaeclipse.utils.core.DexKitQueryCache.Found;
import ps.reso.instaeclipse.utils.feature.FeatureFlags;
import ps.reso.instaeclipse.utils.feature.FeatureStatusTracker;
import ps.reso.instaeclipse.utils.log.ModuleLog;
import ps.reso.instaeclipse.xposedcompat.XC_MethodHook;
import ps.reso.instaeclipse.xposedcompat.XposedBridge;

public final class AnalyticsSkipHook {

    private static final String TAG = "(IE|Analytics) ";
    private static final String STATUS = "BlockAnalytics";
    private static final String CACHE_KEY = "AnalyticsSkip_noopLog_v1";
    private static final String[] ANCHORS = {"SampledOutEventBuilder", "SampledOutEvent is logged: "};
    private static final String NOT_FOUND =
            "Will not work: Instagram's usage-report code was not found in this Instagram version, so reports are still sent";

    public void install(ClassLoader cl) {
        try {
            Method log = commitMethod(cl);
            if (log == null) {
                if (FeatureFlags.isAnalyticsBlocked) FeatureStatusTracker.setBroken(STATUS);
                ModuleLog.error(TAG + NOT_FOUND);
                return;
            }
            XposedBridge.hookMethod(log, new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    if (FeatureFlags.isAnalyticsBlocked) param.setResult(null);
                }
            });
            FeatureStatusTracker.setHooked(STATUS);
            ModuleLog.ok(TAG + "ready");
            ModuleLog.detail(TAG + "hooked " + log.getDeclaringClass().getName() + "." + log.getName());
        } catch (Throwable t) {
            if (FeatureFlags.isAnalyticsBlocked) FeatureStatusTracker.setBroken(STATUS);
            ModuleLog.error(TAG + NOT_FOUND, t);
        }
    }

    private static Method commitMethod(ClassLoader cl) throws Throwable {
        Method noopLog = DexKitCache.isCacheValid() ? DexKitCache.loadMethod(CACHE_KEY, cl) : null;
        if (noopLog == null || commitOf(noopLog) == null) {
            noopLog = findNoopLog(cl);
            if (noopLog == null) return null;
            DexKitCache.saveMethod(CACHE_KEY, noopLog);
        }
        return commitOf(noopLog);
    }

    private static Method findNoopLog(ClassLoader cl) {
        List<Found> found = DexKitQueryCache.methods(FindMethod.create()
                .matcher(MethodMatcher.create().usingStrings(ANCHORS)));
        Method picked = null;
        for (Found f : found) {
            Method m;
            try {
                m = f.getMethodInstance(cl);
            } catch (Throwable t) {
                continue;
            }
            if (commitOf(m) == null || m.equals(picked)) continue;
            if (picked != null) {
                ModuleLog.detail(TAG + "more than one event builder matched");
                return null;
            }
            picked = m;
        }
        if (picked == null) ModuleLog.detail(TAG + "no-op event builder not found (" + found.size() + " candidate(s))");
        return picked;
    }

    static Method commitOf(Method noopLog) {
        if (noopLog == null || noopLog.getReturnType() != void.class || noopLog.getParameterCount() != 0
                || Modifier.isStatic(noopLog.getModifiers())) return null;
        Class<?> noop = noopLog.getDeclaringClass();
        Class<?> base = noop.getSuperclass();
        if (base == null || base == Object.class || !Modifier.isFinal(noop.getModifiers())) return null;
        try {
            Method commit = base.getDeclaredMethod(noopLog.getName());
            if (commit.getReturnType() != void.class || Modifier.isStatic(commit.getModifiers())
                    || Modifier.isAbstract(commit.getModifiers())) return null;
            commit.setAccessible(true);
            return commit;
        } catch (NoSuchMethodException e) {
            return null;
        }
    }
}
