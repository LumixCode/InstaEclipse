package ps.reso.instaeclipse.mods.extras;

import java.lang.reflect.Method;

import ps.reso.instaeclipse.xposedcompat.XC_MethodHook;
import ps.reso.instaeclipse.xposedcompat.XposedBridge;
import ps.reso.instaeclipse.utils.core.DexKitQueryCache.Found;
import ps.reso.instaeclipse.utils.feature.FeatureFlags;
import ps.reso.instaeclipse.utils.feature.FeatureStatusTracker;
import ps.reso.instaeclipse.utils.log.ModuleLog;

public class ReelsControlsHook {

    private static final String TAG = "(InstaEclipse | Reels): ";

    public void install(ClassLoader cl) {
        installAutoScroll(cl);
    }


    private void installAutoScroll(ClassLoader cl) {
        Method gate = ExtrasLookup.method("Extras_ReelsAutoScroll", "reelsAutoScroll", FeatureFlags.reelsAutoScroll, cl, () -> {
            for (Found md : ExtrasLookup.byStrings(
                    "android_purge_26_q2_ClipsViewerExperimentUtil_isOptInAutoscrollEnabled")) {
                if ("boolean".equals(md.getReturnTypeName())) return md.getMethodInstance(cl);
            }
            return null;
        });
        if (gate == null) return;
        XposedBridge.hookMethod(gate, new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam param) {
                if (FeatureFlags.reelsAutoScroll) param.setResult(true);
            }
        });
        FeatureStatusTracker.setHooked("reelsAutoScroll");
        ModuleLog.ok(TAG + "auto-scroll ready");
        ModuleLog.detail(TAG + "auto-scroll gate " + gate.getDeclaringClass().getName() + "." + gate.getName());
    }
}
