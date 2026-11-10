package ps.reso.instaeclipse.mods.ghost;

import org.luckypray.dexkit.query.FindMethod;
import org.luckypray.dexkit.query.matchers.MethodMatcher;

import java.lang.reflect.Method;

import ps.reso.instaeclipse.xposedcompat.XC_MethodHook;
import ps.reso.instaeclipse.xposedcompat.XposedBridge;
import ps.reso.instaeclipse.Xposed.Module;
import ps.reso.instaeclipse.utils.core.DexKitCache;
import ps.reso.instaeclipse.utils.feature.FeatureFlags;
import ps.reso.instaeclipse.utils.feature.FeatureStatusTracker;
import ps.reso.instaeclipse.utils.log.ModuleLog;
import ps.reso.instaeclipse.utils.core.DexKitQueryCache;
import ps.reso.instaeclipse.utils.core.DexKitQueryCache.Found;

public class GhostViewOnceHook {

    private static final String ITEM_CACHE = "GhostViewOnce_item_v1";
    private static final String ITEM_ANCHOR = "direct_v2/visual_threads/%s/visual_items/%s/seen/";

    public void handleViewOnceBlock() {
        if (hookOpened()) hookItemSeen();
    }

    private void hookItemSeen() {
        try {
            Method reporter = DexKitCache.isCacheValid() ? DexKitCache.loadMethod(ITEM_CACHE, Module.hostClassLoader) : null;
            if (reporter == null || !isReporter(reporter)) {
                reporter = singleReporter(FindMethod.create().matcher(MethodMatcher.create().usingStrings(ITEM_ANCHOR)));
                if (reporter != null) DexKitCache.saveMethod(ITEM_CACHE, reporter);
            }
            if (reporter == null) {
                ModuleLog.warn("(InstaEclipse | ViewOnce): Opening media that Replay view-once media keeps may still be reported: that report was not found in this Instagram version");
                return;
            }
            XposedBridge.hookMethod(reporter, buildViewOnceHook());
            ModuleLog.detail("(InstaEclipse | ViewOnce): hooked (kept media): "
                    + reporter.getDeclaringClass().getName() + "." + reporter.getName());
        } catch (Throwable t) {
            ModuleLog.warn("(InstaEclipse | ViewOnce): Opening media that Replay view-once media keeps may still be reported: "
                    + t.getClass().getSimpleName() + ": " + t.getMessage());
        }
    }

    private static Method singleReporter(FindMethod query) {
        Method reporter = null;
        for (Found method : DexKitQueryCache.methods(query)) {
            Method candidate;
            try {
                candidate = method.getMethodInstance(Module.hostClassLoader);
            } catch (Throwable e) {
                continue;
            }
            if (!isReporter(candidate) || candidate.equals(reporter)) continue;
            if (reporter != null) return null;
            reporter = candidate;
        }
        return reporter;
    }

    private boolean hookOpened() {
        if (DexKitCache.isCacheValid()) {
            Method cached = DexKitCache.loadMethod("GhostViewOnce", Module.hostClassLoader);
            if (cached != null && isReporter(cached)) {
                XposedBridge.hookMethod(cached, buildViewOnceHook());
                ModuleLog.ok("(InstaEclipse | ViewOnce): ready");
                ModuleLog.detail("(InstaEclipse | ViewOnce): hooked (cached): "
                        + cached.getDeclaringClass().getName() + "." + cached.getName());
                FeatureStatusTracker.setHooked("GhostViewOnce");
                return true;
            }
        }

        if (tryHookViewOnce(
                FindMethod.create().matcher(MethodMatcher.create()
                        .usingStrings("direct_v2/visual_threads/%s/item_seen/", "raven_media")),
                "endpoint+raven_media")) {
            return true;
        }

        ModuleLog.error("(InstaEclipse | ViewOnce): Could not turn on hiding view-once opens: not supported by this Instagram version");
        return false;
    }

    private boolean tryHookViewOnce(FindMethod query, String tag) {
        try {
            Method reporter = null;
            for (Found method : DexKitQueryCache.methods(query)) {
                Method candidate;
                try {
                    candidate = method.getMethodInstance(Module.hostClassLoader);
                } catch (Throwable e) {
                    continue;
                }
                if (!isReporter(candidate) || candidate.equals(reporter)) continue;
                if (reporter != null) {
                    ModuleLog.detail("(InstaEclipse | ViewOnce): lookup (" + tag + ") ambiguous");
                    return false;
                }
                reporter = candidate;
            }
            if (reporter == null) return false;
            DexKitCache.saveMethod("GhostViewOnce", reporter);
            XposedBridge.hookMethod(reporter, buildViewOnceHook());
            ModuleLog.ok("(InstaEclipse | ViewOnce): ready");
            ModuleLog.detail("(InstaEclipse | ViewOnce): hooked (" + tag + "): "
                    + reporter.getDeclaringClass().getName() + "." + reporter.getName());
            FeatureStatusTracker.setHooked("GhostViewOnce");
            return true;
        } catch (Throwable e) {
            ModuleLog.detail("(InstaEclipse | ViewOnce): lookup (" + tag + ") failed: " + e);
        }
        return false;
    }

    static boolean isReporter(Method method) {
        Class<?>[] params = method.getParameterTypes();
        return method.getReturnType() == void.class && params.length == 3
                && !java.lang.reflect.Modifier.isStatic(method.getModifiers())
                && completionMethod(params[1]) != null;
    }

    private static XC_MethodHook buildViewOnceHook() {
        return new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) {
                if (FeatureFlags.isGhostViewOnce) skipMutation(param, "ViewOnce");
            }
        };
    }

    static void skipMutation(XC_MethodHook.MethodHookParam param, String tag) {
        param.setResult(null);
        try {
            if (param.args == null || param.args.length != 3 || param.args[1] == null
                    || !(param.method instanceof Method)) return;
            Method done = completionMethod(((Method) param.method).getParameterTypes()[1]);
            if (done == null) return;
            done.setAccessible(true);
            done.invoke(param.args[1], new Object[]{null, null});
        } catch (Throwable t) {
            ModuleLog.warn("(IE|" + tag + ") Blocked the report, but could not finish it cleanly; later messages in this chat may wait to send", t);
        }
    }

    static Method completionMethod(Class<?> callback) {
        if (callback == null || !callback.isInterface()) return null;
        Method found = null;
        for (Method m : ps.reso.instaeclipse.utils.core.SafeReflect.methods(callback)) {
            if (java.lang.reflect.Modifier.isStatic(m.getModifiers()) || m.getReturnType() != void.class) continue;
            Class<?>[] p = m.getParameterTypes();
            if (p.length != 2 || p[0].isPrimitive() || p[0] == String.class || p[1] != String.class) continue;
            if (found != null) return null;
            found = m;
        }
        return found;
    }
}
