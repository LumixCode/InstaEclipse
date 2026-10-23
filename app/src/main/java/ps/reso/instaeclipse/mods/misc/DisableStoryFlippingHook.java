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
import ps.reso.instaeclipse.utils.feature.FeatureStatusTracker;
import ps.reso.instaeclipse.utils.log.ModuleLog;
import ps.reso.instaeclipse.utils.core.DexKitQueryCache;

public class DisableStoryFlippingHook {

    private static final String REEL_VIEWER = "instagram.features.stories.fragment.ReelViewerFragment";
    private static final String REEL_ITEM = "com.instagram.model.reels.ReelItem";

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
                FeatureStatusTracker.setHooked("DisableStoryFlipping");
                ModuleLog.ok("(InstaEclipse | StoryFlipping): Ready");
                ModuleLog.detail("(InstaEclipse | StoryFlipping): Hooked (dynamic check): " + cached.getDeclaringClass().getName() + "." + cached.getName());
                return;
            }
        }
        boolean hooked = false;
        try {
            hooked = findAndHookMethod(bridge);
        } catch (Exception e) {
            ModuleLog.error("(InstaEclipse | StoryFlipping): Could not turn on disable story flipping: " + e.getClass().getSimpleName() + ": " + e.getMessage());
        }
        if (!hooked && FeatureFlags.disableStoryFlipping) FeatureStatusTracker.setBroken("DisableStoryFlipping");
    }

    private boolean findAndHookMethod(DexKitBridge bridge) {
        List<MethodData> methods = DexKitQueryCache.findMethod(bridge, FindMethod.create().matcher(MethodMatcher.create()
                .declaredClass(REEL_VIEWER)
                .returnType("void")
                .usingEqStrings("end_scene")));
        MethodData target = null;
        for (MethodData method : methods) {
            List<String> params = method.getParamTypeNames();
            if (params.size() != 1) continue;
            if (params.get(0).equals(REEL_ITEM)) {
                target = method;
                break;
            }
            if (target == null && params.get(0).equals("java.lang.Object")) target = method;
        }
        if (target == null) {
            ModuleLog.error("(InstaEclipse | StoryFlipping): Could not turn on disable story flipping: the story auto-advance was not found in this Instagram version");
            ModuleLog.detail("(InstaEclipse | StoryFlipping): " + methods.size() + " end_scene methods, none taking a ReelItem or an Object");
            return false;
        }
        try {
            Method targetMethod = target.getMethodInstance(Module.hostClassLoader);
            DexKitCache.saveMethod("StoryFlipping", targetMethod);
            XposedBridge.hookMethod(targetMethod, HOOK);
            FeatureStatusTracker.setHooked("DisableStoryFlipping");
            ModuleLog.ok("(InstaEclipse | StoryFlipping): Ready");
            ModuleLog.detail("(InstaEclipse | StoryFlipping): Hooked (dynamic check): " +
                    target.getClassName() + "." + target.getName());
            return true;
        } catch (Exception e) {
            ModuleLog.error("(InstaEclipse | StoryFlipping): Could not turn on disable story flipping: " + e.getClass().getSimpleName() + ": " + e.getMessage());
            return false;
        }
    }
}
