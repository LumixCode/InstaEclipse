package ps.reso.instaeclipse.mods.ghost;

import org.luckypray.dexkit.query.FindMethod;
import org.luckypray.dexkit.query.matchers.MethodMatcher;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.List;

import ps.reso.instaeclipse.xposedcompat.XC_MethodHook;
import ps.reso.instaeclipse.xposedcompat.XposedBridge;
import ps.reso.instaeclipse.Xposed.Module;
import ps.reso.instaeclipse.utils.core.DexKitCache;
import ps.reso.instaeclipse.utils.feature.FeatureFlags;
import ps.reso.instaeclipse.utils.feature.FeatureStatusTracker;
import ps.reso.instaeclipse.utils.ghost.MarkSeen;
import ps.reso.instaeclipse.utils.ghost.ReadReceiptWhitelist;
import ps.reso.instaeclipse.utils.ghost.SeenPause;
import ps.reso.instaeclipse.utils.log.ModuleLog;
import ps.reso.instaeclipse.utils.core.DexKitQueryCache;
import ps.reso.instaeclipse.utils.core.DexKitQueryCache.Found;

public class GhostDMSeenHook {
    public void handleSeenBlock() {
        XC_MethodHook hook = new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) {
                if (!MarkSeen.asking()) {
                    if (!SeenPause.blocks(FeatureFlags.isGhostSeen)) return;
                    for (Object a : param.args) {
                        if (a instanceof String s) {
                            if (ReadReceiptWhitelist.isAllowed(s)) return;
                            break;
                        }
                    }
                }
                param.setResult(null);
                MarkSeen.remember(param.args);
            }
        };

        if (DexKitCache.isCacheValid()) {
            Method cached = DexKitCache.loadMethod("GhostSeen", Module.hostClassLoader);
            if (cached != null) {
                XposedBridge.hookMethod(cached, hook);
                ModuleLog.ok("(InstaEclipse | GhostModeSeen): ready");
                ModuleLog.detail("(InstaEclipse | GhostModeSeen): hooked (cached): " + cached.getDeclaringClass().getName() + "." + cached.getName());
                FeatureStatusTracker.setHooked("GhostSeen");
                bindMarkSeen(cached);
                return;
            }
        }

        try {
            List<Found> methods = DexKitQueryCache.methods(FindMethod.create()
                    .matcher(MethodMatcher.create().usingStrings("mark_thread_seen-")));

            if (methods.isEmpty()) {
                ModuleLog.error("(InstaEclipse | GhostModeSeen): Could not turn on hiding seen in chats: not supported by this Instagram version (no 'mark_thread_seen-' method)");
                return;
            }

            for (Found method : methods) {
                Method reflectMethod;
                try {
                    reflectMethod = method.getMethodInstance(Module.hostClassLoader);
                } catch (Throwable e) {
                    continue;
                }

                int modifiers = reflectMethod.getModifiers();
                String returnType = method.getReturnTypeName();
                List<String> paramTypes = method.getParamTypeNames();

                if (Modifier.isStatic(modifiers)
                        && Modifier.isFinal(modifiers)
                        && returnType.contains("void")
                        && paramTypes.size() >= 3) {

                    try {
                        DexKitCache.saveMethod("GhostSeen", reflectMethod);
                        XposedBridge.hookMethod(reflectMethod, hook);

                        ModuleLog.ok("(InstaEclipse | GhostModeSeen): ready");
                        ModuleLog.detail("(InstaEclipse | GhostModeSeen): hooked: " +
                                method.getClassName() + "." + method.getName());
                        FeatureStatusTracker.setHooked("GhostSeen");
                        bindMarkSeen(reflectMethod);
                        return;

                    } catch (Throwable e) {
                        ModuleLog.warn("(InstaEclipse | GhostModeSeen): Hiding seen in chats may not work: " + e.getClass().getSimpleName() + ": " + e.getMessage());
                    }
                }
            }

        } catch (Throwable e) {
            ModuleLog.error("(InstaEclipse | GhostModeSeen): Could not turn on hiding seen in chats", e);
        }
    }

    private static void bindMarkSeen(Method sender) {
        MarkSeen.useSender(sender);
        try {
            Method decider = callerOf(sender, 4, boolean.class);
            Method dispatcher = decider == null ? null : callerOf(decider, 3, int.class);
            Method check = dispatcher == null ? null : callerOf(dispatcher, 0, null);
            if (check == null) {
                ModuleLog.warn("(InstaEclipse | GhostModeSeen): Mark as seen can only send the last message Instagram tried to mark: its seen check was not found");
                return;
            }
            check.setAccessible(true);
            XposedBridge.hookMethod(dispatcher, new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    if (MarkSeen.asking()) param.args[param.args.length - 1] = 0;
                }
            });
            XposedBridge.hookMethod(check, new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    MarkSeen.useChecker(param.thisObject);
                }
            });
            MarkSeen.useCheck(check);
            ModuleLog.detail("(InstaEclipse | GhostModeSeen): seen check: " + check.getDeclaringClass().getName() + "." + check.getName());
        } catch (Throwable e) {
            ModuleLog.warn("(InstaEclipse | GhostModeSeen): Mark as seen can only send the last message Instagram tried to mark: " + e);
        }
    }

    private static Method callerOf(Method callee, int params, Class<?> lastParam) {
        for (Found found : DexKitQueryCache.methods(FindMethod.create().matcher(MethodMatcher.create()
                .returnType("void").paramCount(params).addInvoke(MethodMatcher.create(callee))))) {
            if (!found.isMethod()) continue;
            try {
                Method method = found.getMethodInstance(Module.hostClassLoader);
                if (Modifier.isStatic(method.getModifiers())) continue;
                if (lastParam == null || method.getParameterTypes()[params - 1] == lastParam) return method;
            } catch (Throwable ignored) {
            }
        }
        return null;
    }
}
