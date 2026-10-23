package ps.reso.instaeclipse.mods.misc;

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
import ps.reso.instaeclipse.utils.log.ModuleLog;
import ps.reso.instaeclipse.utils.core.DexKitQueryCache;

public class DisableStoryFlippingHook {

    private static final XC_MethodHook HOOK = new XC_MethodHook() {
        @Override
        protected void beforeHookedMethod(MethodHookParam param) throws Throwable { ps.reso.instaeclipse.utils.core.JitGuard.tick(param.method);
            if (FeatureFlags.disableStoryFlipping) param.setResult(null);
        }
    };

    public void handleStoryFlippingDisable(DexKitBridge bridge) {
        if (DexKitCache.isCacheValid()) {
            Method cached = DexKitCache.loadMethod("StoryFlipping", Module.hostClassLoader);
            if (cached != null) {
                XposedBridge.hookMethod(cached, HOOK);
                ps.reso.instaeclipse.utils.feature.FeatureStatusTracker.setHooked("DisableStoryFlipping");
                ModuleLog.ok("(InstaEclipse | StoryFlipping): Ready");
                ModuleLog.detail("(InstaEclipse | StoryFlipping): Hooked (dynamic check): " + cached.getDeclaringClass().getName() + "." + cached.getName());
                return;
            }
        }
        try {
            findAndHookMethod(bridge);
        } catch (Exception e) {
            ModuleLog.error("(InstaEclipse | StoryFlipping): Could not turn on disable story flipping: " + e.getClass().getSimpleName() + ": " + e.getMessage());
        }
    }

    private void findAndHookMethod(DexKitBridge bridge) {
        try {
            // Step 1: the "story item finished → advance" callback on ReelViewerFragment.
            // IG 452+: typed A1L(ReelItem)V holding the "end_scene" check; the (Object)V
            // interface method became a bare bridge to it, and the progress callback also calls
            // it at 100%, so hooking it covers both paths.
            String rvf = "instagram.features.stories.fragment.ReelViewerFragment";
            List<MethodData> methods = DexKitQueryCache.findMethod(bridge, 
                    FindMethod.create().matcher(
                            MethodMatcher.create()
                                    .declaredClass(rvf)
                                    .paramTypes("com.instagram.model.reels.ReelItem")
                                    .returnType("void")
                                    .usingStrings("end_scene")
                    )
            );
            // IG <=451: the (Object)V interface method itself held the body.
            if (methods.isEmpty()) {
                methods = DexKitQueryCache.findMethod(bridge, 
                        FindMethod.create().matcher(
                                MethodMatcher.create()
                                        .declaredClass(rvf)
                                        .paramTypes("java.lang.Object")
                                        .returnType("void")
                                        .usingStrings("userSession")
                        )
                );
            }

            if (methods.isEmpty()) {
                ModuleLog.error("(InstaEclipse | StoryFlipping): Could not turn on disable story flipping: the story auto-advance was not found in this Instagram version");
                return;
            }

            // Step 2: Hook the correct method
            for (MethodData method : methods) {
                try {
                    Method targetMethod = method.getMethodInstance(Module.hostClassLoader);
                    DexKitCache.saveMethod("StoryFlipping", targetMethod);
                    XposedBridge.hookMethod(targetMethod, HOOK);

                    ps.reso.instaeclipse.utils.feature.FeatureStatusTracker.setHooked("DisableStoryFlipping");
                    ModuleLog.ok("(InstaEclipse | StoryFlipping): Ready");
                    ModuleLog.detail("(InstaEclipse | StoryFlipping): Hooked (dynamic check): " +
                            method.getClassName() + "." + method.getName());
                    return;

                } catch (Exception e) {
                    ModuleLog.error("(InstaEclipse | StoryFlipping): Could not turn on disable story flipping: " + e.getClass().getSimpleName() + ": " + e.getMessage());
                }
            }
        } catch (Exception e) {
            ModuleLog.error("(InstaEclipse | StoryFlipping): Could not turn on disable story flipping: " + e.getClass().getSimpleName() + ": " + e.getMessage());
        }
    }
}
