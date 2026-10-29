package ps.reso.instaeclipse.mods.ghost;

import org.luckypray.dexkit.query.FindClass;
import org.luckypray.dexkit.query.FindMethod;
import org.luckypray.dexkit.query.matchers.ClassMatcher;
import org.luckypray.dexkit.query.matchers.MethodMatcher;

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
import ps.reso.instaeclipse.utils.core.DexKitQueryCache.Found;
import ps.reso.instaeclipse.utils.core.DexKitQueryCache.FoundClass;

public class GhostScreenshotDetectionHook {

    public void handleScreenshotBlock() {
        XC_MethodHook hook = new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                if (FeatureFlags.isGhostScreenshot) param.setResult(null);
            }
        };

        if (DexKitCache.isCacheValid()) {
            Method cached = DexKitCache.loadMethod("GhostScreenshot", Module.hostClassLoader);
            if (cached != null) {
                XposedBridge.hookMethod(cached, hook);
                ModuleLog.ok("(InstaEclipse | ScreenshotBlock): ready");
                ModuleLog.detail("(InstaEclipse | ScreenshotBlock): hooked (cached): " + cached.getDeclaringClass().getName() + "." + cached.getName());
                FeatureStatusTracker.setHooked("GhostScreenshot");
                return;
            }
        }

        try {
            // Step 1: Find class referencing "ScreenshotNotificationManager"
            List<FoundClass> classes = DexKitQueryCache.classes(FindClass.create()
                    .matcher(ClassMatcher.create().usingStrings("ScreenshotNotificationManager")));

            if (classes.isEmpty()) {
                ModuleLog.error("(InstaEclipse | ScreenshotBlock): Could not turn on hiding screenshots: not supported by this Instagram version (no 'ScreenshotNotificationManager' class)");
                return;
            }

            for (FoundClass classData : classes) {
                String className = classData.getName();

                // Step 2: Find all methods in that class
                List<Found> methods = DexKitQueryCache.methods(FindMethod.create()
                        .matcher(MethodMatcher.create().declaredClass(className)));

                for (Found method : methods) {
                    List<String> paramTypes = method.getParamTypeNames();
                    String returnType = method.getReturnTypeName();

                    // Match: void method(long)
                    if (returnType.contains("void") &&
                            paramTypes.size() == 1 &&
                            paramTypes.get(0).contains("long")) {

                        try {
                            Method targetMethod = method.getMethodInstance(Module.hostClassLoader);
                            DexKitCache.saveMethod("GhostScreenshot", targetMethod);
                            XposedBridge.hookMethod(targetMethod, hook);

                            ModuleLog.ok("(InstaEclipse | ScreenshotBlock): ready");
                            ModuleLog.detail("(InstaEclipse | ScreenshotBlock): hooked: " +
                                    method.getClassName() + "." + method.getName());
                            FeatureStatusTracker.setHooked("GhostScreenshot");
                            return;

                        } catch (Throwable e) {
                            ModuleLog.warn("(InstaEclipse | ScreenshotBlock): Hiding screenshots may not work: " + e.getClass().getSimpleName() + ": " + e.getMessage());
                        }
                    }
                }
            }

        } catch (Throwable e) {
            ModuleLog.error("(InstaEclipse | ScreenshotBlock): Could not turn on hiding screenshots", e);
        }
    }
}
