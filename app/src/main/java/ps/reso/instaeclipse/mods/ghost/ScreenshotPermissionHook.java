package ps.reso.instaeclipse.mods.ghost;

import android.view.Window;
import android.view.WindowManager;

import ps.reso.instaeclipse.xposedcompat.XC_MethodHook;
import ps.reso.instaeclipse.xposedcompat.XposedBridge;
import ps.reso.instaeclipse.xposedcompat.XposedHelpers;
import ps.reso.instaeclipse.utils.feature.FeatureFlags;
import ps.reso.instaeclipse.utils.feature.FeatureStatusTracker;
import ps.reso.instaeclipse.utils.log.ModuleLog;

public class ScreenshotPermissionHook {

    public void install(ClassLoader classLoader) {
        try {
            XposedHelpers.findAndHookMethod(Window.class, "setFlags",
                    int.class, int.class, new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) {
                            if (!FeatureFlags.allowScreenshots) return;
                            int[] stripped = SecureFlags.strip((int) param.args[0], (int) param.args[1]);
                            param.args[0] = stripped[0];
                            param.args[1] = stripped[1];
                        }
                    });

            XposedHelpers.findAndHookMethod(Window.class, "addFlags",
                    int.class, new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) {
                            if (!FeatureFlags.allowScreenshots) return;
                            param.args[0] = (int) param.args[0] & ~WindowManager.LayoutParams.FLAG_SECURE;
                        }
                    });

            ModuleLog.ok("(InstaEclipse | ScreenshotPermission): ready");
            FeatureStatusTracker.setHooked("AllowScreenshots");

        } catch (Throwable e) {
            ModuleLog.error("(InstaEclipse | ScreenshotPermission): Could not turn on screenshots in protected screens", e);
        }
    }
}
