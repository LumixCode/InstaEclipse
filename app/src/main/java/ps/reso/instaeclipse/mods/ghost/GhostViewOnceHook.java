package ps.reso.instaeclipse.mods.ghost;

import org.luckypray.dexkit.DexKitBridge;
import org.luckypray.dexkit.query.FindMethod;
import org.luckypray.dexkit.query.matchers.MethodMatcher;
import org.luckypray.dexkit.result.ClassDataList;
import org.luckypray.dexkit.result.MethodData;

import java.lang.reflect.Method;
import java.util.List;

import ps.reso.instaeclipse.xposedcompat.XC_MethodHook;
import ps.reso.instaeclipse.xposedcompat.XposedBridge;
import ps.reso.instaeclipse.Xposed.Module;
import ps.reso.instaeclipse.utils.core.DexKitCache;
import ps.reso.instaeclipse.utils.feature.FeatureFlags;
import ps.reso.instaeclipse.utils.feature.FeatureStatusTracker;
import ps.reso.instaeclipse.utils.log.ModuleLog;
import ps.reso.instaeclipse.utils.core.DexKitQueryCache;

public class GhostViewOnceHook {

    public void handleViewOnceBlock(DexKitBridge bridge) {
        if (DexKitCache.isCacheValid()) {
            Method cached = DexKitCache.loadMethod("GhostViewOnce", Module.hostClassLoader);
            if (cached != null) {
                XposedBridge.hookMethod(cached, buildViewOnceHook());
                ModuleLog.line("(InstaEclipse | ViewOnce): ✅ Hooked (cached): "
                        + cached.getDeclaringClass().getName() + "." + cached.getName());
                FeatureStatusTracker.setHooked("GhostViewOnce");
                return;
            }
        }

        // Primary anchor (IG 447+): the visual view-once "item seen" reporter was inlined
        // into the handler. It contains BOTH the stable REST path and the visual media type
        // "raven_media". The voice sibling uses the SAME path but "voice_media", so ANDing
        // "raven_media" selects only the view-once (visual) reporter — never voice.
        if (tryHookViewOnce(bridge,
                FindMethod.create().matcher(MethodMatcher.create()
                        .usingStrings("direct_v2/visual_threads/%s/item_seen/", "raven_media")),
                "endpoint+raven_media")) {
            return;
        }

        // Legacy fallback (IG 436 and older): the reporter inlined the marker name
        // "visual_item_seen" rather than the REST path. Same (3-param, void) shape.
        if (tryHookViewOnce(bridge,
                FindMethod.create().matcher(MethodMatcher.create()
                        .usingStrings("visual_item_seen")),
                "visual_item_seen")) {
            return;
        }

        ModuleLog.line("(InstaEclipse | ViewOnce): ❌ No view-once seen-reporter method found");
    }

    /**
     * Finds a (3-param, void) method matching the query and hooks it. Both anchors resolve
     * to a visual-only "report seen" handler, so unconditional setResult(null) is safe.
     */
    private boolean tryHookViewOnce(DexKitBridge bridge, FindMethod query, String tag) {
        try {
            List<MethodData> methods = DexKitQueryCache.findMethod(bridge, query);
            for (MethodData method : methods) {
                if (method.getParamTypes().size() != 3) continue;
                if (!String.valueOf(method.getReturnType()).contains("void")) continue;

                Method reflectMethod;
                try {
                    reflectMethod = method.getMethodInstance(Module.hostClassLoader);
                } catch (Throwable e) {
                    continue;
                }

                DexKitCache.saveMethod("GhostViewOnce", reflectMethod);
                XposedBridge.hookMethod(reflectMethod, buildViewOnceHook());
                ModuleLog.line("(InstaEclipse | ViewOnce): ✅ Hooked (" + tag + "): "
                        + method.getClassName() + "." + method.getName());
                FeatureStatusTracker.setHooked("GhostViewOnce");
                return true;
            }
        } catch (Throwable e) {
            ModuleLog.line("(InstaEclipse | ViewOnce): ❌ Exception (" + tag + "): " + e.getMessage());
        }
        return false;
    }

    private static XC_MethodHook buildViewOnceHook() {
        return new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) { ps.reso.instaeclipse.utils.core.JitGuard.tick(param.method);
                // The hooked method is the visual/view-once seen-reporter only (selected via
                // "raven_media" on 447 / hardcoded "visual_item_seen" on 436). Skipping it
                // suppresses just the view-once "seen/opened" report; voice/other sends are
                // different methods and are untouched.
                if (FeatureFlags.isGhostViewOnce) skipMutation(param, "ViewOnce");
            }
        };
    }

    /**
     * Skips a DM mutation processor's send, Gx1(context, callback, mutation) on 451, and still
     * completes the mutation. DirectMutationManager (X.04xt.A03) marks the mutation "executing"
     * before calling the processor and only the callback completes it; the next-mutation picker
     * (X.04xt.A00) stops at an executing entry, so a bare skip stalled every later mutation of that
     * queue (the chat's seen/voice markers, or its text sends). IG's own fire-and-forget processor
     * X.05uj.Gx1 ends with callback.FpP(null, null): the success path, which marks the mutation
     * "uploaded" and schedules the next one without sending anything. Without such a callback
     * (legacy anchors) this is a plain skip.
     */
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
            ModuleLog.line("(IE|" + tag + ") ⚠️ mutation skipped but not completed: " + t);
        }
    }

    /**
     * The mutation callback's completion method: the interface's only instance void (error, String)
     * method (X.0lfj.FpP on 451, null error = success). Null for a class (the 451 replay of the
     * legacy anchor gets a message model here) or when the pick is ambiguous.
     */
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
