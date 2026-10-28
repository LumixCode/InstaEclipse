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
import ps.reso.instaeclipse.utils.log.ModuleLog;
import ps.reso.instaeclipse.utils.core.DexKitQueryCache;
import ps.reso.instaeclipse.utils.core.DexKitQueryCache.Found;

public class GhostTypingIndicatorHook {

    public void handleTypingBlock() {
        XC_MethodHook hook = new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) { ps.reso.instaeclipse.utils.core.JitGuard.tick(param.method);
                if (FeatureFlags.isGhostTyping) param.setResult(null);
            }
        };

        if (DexKitCache.isCacheValid()) {
            Method cached = DexKitCache.loadMethod("GhostTyping", Module.hostClassLoader);
            if (cached != null) {
                XposedBridge.hookMethod(cached, hook);
                ModuleLog.ok("(InstaEclipse | TypingBlock): ready");
                ModuleLog.detail("(InstaEclipse | TypingBlock): hooked (cached): " + cached.getDeclaringClass().getName() + "." + cached.getName());
                FeatureStatusTracker.setHooked("GhostTyping");
                return;
            }
        }

        try {
            // Step 1: Find methods containing the string "is_typing_indicator_enabled"
            List<Found> methods = DexKitQueryCache.methods(FindMethod.create()
                    .matcher(MethodMatcher.create().usingStrings("is_typing_indicator_enabled")));

            if (methods.isEmpty()) {
                ModuleLog.error("(InstaEclipse | TypingBlock): Could not turn on hiding typing: not supported by this Instagram version (no 'is_typing_indicator_enabled' method)");
                if (FeatureFlags.isGhostTyping) FeatureStatusTracker.setBroken("GhostTyping");
                return;
            }

            for (Found method : methods) {
                List<String> paramTypes = method.getParamTypeNames();
                String returnType = method.getReturnTypeName();

                Method reflectMethod;
                try {
                    reflectMethod = method.getMethodInstance(Module.hostClassLoader);
                } catch (Throwable e) {
                    // Skip method if it can't be resolved
                    continue;
                }

                if (!returnType.contains("void")) continue;
                int modifiers = reflectMethod.getModifiers();

                boolean matchesShape = !Modifier.isStatic(modifiers) &&
                        Modifier.isFinal(modifiers) &&
                        paramTypes.size() == 1 &&
                        paramTypes.get(0).contains("boolean");

                if (matchesShape) {
                    try {
                        DexKitCache.saveMethod("GhostTyping", reflectMethod);
                        XposedBridge.hookMethod(reflectMethod, hook);

                        ModuleLog.ok("(InstaEclipse | TypingBlock): ready");
                        ModuleLog.detail("(InstaEclipse | TypingBlock): hooked: " +
                                method.getClassName() + "." + method.getName());
                        FeatureStatusTracker.setHooked("GhostTyping");
                        return;

                    } catch (Throwable e) {
                        ModuleLog.detail("(InstaEclipse | TypingBlock): candidate failed: " + e.getMessage());
                    }
                }
            }

            ModuleLog.error("(InstaEclipse | TypingBlock): Could not turn on hiding typing: not supported by this Instagram version (no matching method)");
            if (FeatureFlags.isGhostTyping) FeatureStatusTracker.setBroken("GhostTyping");

        } catch (Throwable t) {
            ModuleLog.error("(InstaEclipse | TypingBlock): Could not turn on hiding typing", t);
        }
    }
}
