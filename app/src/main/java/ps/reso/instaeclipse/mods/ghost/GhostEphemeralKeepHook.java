package ps.reso.instaeclipse.mods.ghost;

import org.luckypray.dexkit.DexKitBridge;
import org.luckypray.dexkit.query.FindMethod;
import org.luckypray.dexkit.query.matchers.MethodMatcher;
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

public class GhostEphemeralKeepHook {

    public void install(DexKitBridge bridge, ClassLoader classLoader) {
        hookVanishLocalDelete(bridge, classLoader);
        hookServerPing(bridge);
    }

    private void hookVanishLocalDelete(DexKitBridge bridge, ClassLoader classLoader) {
        // IG 447+ (verified 447/451/452): the removal itself lives in two methods of the thread
        // model (X.01eV on 452, X.04r2 on 451, X.01gH on 447), called on chat exit, by the
        // per-thread expiry timer and when a thread is loaded from disk:
        //   (Z)Z  remove expired disappearing messages — unique string below
        //   ()Z   remove vanish-mode ("is_shh_mode") messages — the class's only ()boolean
        // Both return "anything removed"; callers only commit on true, so false = keep all.
        XC_MethodHook keep = new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) { ps.reso.instaeclipse.utils.core.JitGuard.tick(param.method);
                if (FeatureFlags.keepEphemeralMessages) param.setResult(false);
            }
        };
        if (DexKitCache.isCacheValid()) {
            List<Method> cached = DexKitCache.loadMethods("Ephemeral_vanish_v2", classLoader);
            if (cached != null && !cached.isEmpty()) {
                for (Method m : cached) XposedBridge.hookMethod(m, keep);
                ModuleLog.ok("(IE|Ephemeral) ready");
                FeatureStatusTracker.setHooked("KeepEphemeralMessages");
                return;
            }
        }
        try {
            List<Method> found = new java.util.ArrayList<>();
            for (MethodData md : DexKitQueryCache.findMethod(bridge, FindMethod.create()
                    .matcher(MethodMatcher.create()
                            .usingStrings("Unexpected null timestamp for disappearing message, removed message")
                            .paramTypes("boolean")
                            .returnType("boolean")))) {
                Method expired = md.getMethodInstance(classLoader);
                found.add(expired);
                Method shh = null;
                int candidates = 0;
                for (Method m : ps.reso.instaeclipse.utils.core.SafeReflect.methods(expired.getDeclaringClass())) {
                    if (m.getParameterCount() == 0 && m.getReturnType() == boolean.class
                            && !java.lang.reflect.Modifier.isStatic(m.getModifiers())) { shh = m; candidates++; }
                }
                if (candidates == 1) found.add(shh);
                else {
                    ModuleLog.warn("(IE|Ephemeral) Vanish mode messages may not be kept on this Instagram version (other disappearing messages are)");
                    ModuleLog.detail("(IE|Ephemeral) vanish-mode purge ambiguous (" + candidates + " ()Z methods)");
                }
                break;
            }
            if (!found.isEmpty()) {
                for (Method m : found) {
                    XposedBridge.hookMethod(m, keep);
                    ModuleLog.detail("(IE|Ephemeral) local purge hook → "
                            + m.getDeclaringClass().getName() + "." + m.getName());
                }
                ModuleLog.ok("(IE|Ephemeral) ready");
                DexKitCache.saveMethods("Ephemeral_vanish_v2", found);
                FeatureStatusTracker.setHooked("KeepEphemeralMessages");
                return;
            }
            ModuleLog.error("(IE|Ephemeral) Could not turn on keeping disappearing messages: not supported by this Instagram version");
        } catch (Throwable t) {
            ModuleLog.error("(IE|Ephemeral) Could not turn on keeping disappearing messages", t);
        }
    }

    /**
     * Blocks any void method that dispatches mark_ephemeral_item_ranges_viewed. On 451 that is a
     * DM mutation processor (X.05uj.Gx1) on the chat's "thread-<id>" queue, which text sends share,
     * so the skip also completes the mutation (see GhostViewOnceHook.skipMutation).
     */
    private void hookServerPing(DexKitBridge bridge) {
        XC_MethodHook hook = new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) { ps.reso.instaeclipse.utils.core.JitGuard.tick(param.method);
                if (FeatureFlags.keepEphemeralMessages) GhostViewOnceHook.skipMutation(param, "Ephemeral");
            }
        };

        if (DexKitCache.isCacheValid()) {
            Method cached = DexKitCache.loadMethod("Ephemeral_ping", Module.hostClassLoader);
            if (cached != null) { XposedBridge.hookMethod(cached, hook); return; }
        }

        try {
            List<MethodData> methods = DexKitQueryCache.findMethod(bridge, FindMethod.create()
                    .matcher(MethodMatcher.create()
                            .usingStrings("mark_ephemeral_item_ranges_viewed")));

            for (MethodData md : methods) {
                Method method;
                try {
                    method = md.getMethodInstance(Module.hostClassLoader);
                } catch (Throwable e) {
                    continue;
                }
                if (method.getReturnType() != void.class) continue;

                DexKitCache.saveMethod("Ephemeral_ping", method);
                XposedBridge.hookMethod(method, hook);
                ModuleLog.detail("(IE|Ephemeral) server-ping hook → "
                        + md.getClassName() + "." + md.getName());
                return;
            }
            ModuleLog.warn("(IE|Ephemeral) The extra block for the \"viewed\" report is not available on this Instagram version, so the viewed report may still be sent");
        } catch (Throwable t) {
            ModuleLog.warn("(IE|Ephemeral) The extra block for the \"viewed\" report could not be set up, so the viewed report may still be sent", t);
        }
    }
}
