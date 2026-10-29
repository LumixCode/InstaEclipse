package ps.reso.instaeclipse.mods.media;

import org.luckypray.dexkit.query.FindMethod;
import org.luckypray.dexkit.query.matchers.MethodMatcher;

import java.util.List;

import ps.reso.instaeclipse.xposedcompat.XC_MethodHook;
import ps.reso.instaeclipse.xposedcompat.XposedBridge;
import ps.reso.instaeclipse.utils.feature.FeatureFlags;
import ps.reso.instaeclipse.utils.feature.FeatureStatusTracker;
import ps.reso.instaeclipse.utils.log.ModuleLog;
import ps.reso.instaeclipse.utils.core.DexKitQueryCache;
import ps.reso.instaeclipse.utils.core.DexKitQueryCache.Found;

public class MaxUploadQualityHook {

    private static final String TAG = "(IE|MaxUploadQuality) ";
    private static final String PREF_KEY = "high_quality_media_upload";

    public void install(ClassLoader classLoader) {
        try {
            List<Found> results = DexKitQueryCache.methods(FindMethod.create()
                    .matcher(MethodMatcher.create()
                            .usingEqStrings(List.of(PREF_KEY))
                            .returnType("boolean")
                            .paramCount(1)));

            int n = 0;
            for (Found md : results) {
                try {
                    XposedBridge.hookMethod(md.getMethodInstance(classLoader), new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            if (!FeatureFlags.maxUploadQuality) return;
                            param.setResult(Boolean.TRUE);
                        }
                    });
                    n++;
                } catch (Throwable ignored) {}
            }

            if (n == 0) {
                ModuleLog.error(TAG + "Could not turn on highest-quality uploads: the upload quality setting was not found in this Instagram version");
                if (FeatureFlags.maxUploadQuality) FeatureStatusTracker.setBroken("MaxUploadQuality");
                return;
            }

            FeatureStatusTracker.setHooked("MaxUploadQuality");
            ModuleLog.ok(TAG + "ready");
            ModuleLog.detail(TAG + "hooked " + n + " method(s)");
        } catch (Throwable t) {
            ModuleLog.error(TAG + "Could not turn on highest-quality uploads", t);
            if (FeatureFlags.maxUploadQuality) FeatureStatusTracker.setBroken("MaxUploadQuality");
        }
    }
}
