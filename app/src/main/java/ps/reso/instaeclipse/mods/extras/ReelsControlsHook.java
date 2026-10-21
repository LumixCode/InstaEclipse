package ps.reso.instaeclipse.mods.extras;

import org.luckypray.dexkit.DexKitBridge;
import org.luckypray.dexkit.result.MethodData;

import java.lang.reflect.Method;

import ps.reso.instaeclipse.xposedcompat.XC_MethodHook;
import ps.reso.instaeclipse.xposedcompat.XposedBridge;
import ps.reso.instaeclipse.utils.feature.FeatureFlags;
import ps.reso.instaeclipse.utils.feature.FeatureStatusTracker;
import ps.reso.instaeclipse.utils.log.ModuleLog;

/**
 * Reels auto-scroll, keyed on IG's own "android_purge_…" marker: IG's opt-in auto-scroll gate
 * {@code ClipsViewerExperimentUtil.isOptInAutoscrollEnabled} is forced true, which unlocks IG's
 * native "Auto scroll" option in the Reels ⋯ menu.
 * "Lock Reels scrolling" lives in {@link ps.reso.instaeclipse.mods.reels.ReelViewerHook}, which
 * owns the Reels pager input together with the reel limits, so the two never fight over it.
 */
public class ReelsControlsHook {

    private static final String TAG = "(InstaEclipse | Reels): ";

    public void install(DexKitBridge bridge, ClassLoader cl) {
        installAutoScroll(bridge, cl);
    }


    private void installAutoScroll(DexKitBridge bridge, ClassLoader cl) {
        Method gate = ExtrasLookup.method("Extras_ReelsAutoScroll", cl, () -> {
            for (MethodData md : ExtrasLookup.byStrings(bridge,
                    "android_purge_26_q2_ClipsViewerExperimentUtil_isOptInAutoscrollEnabled")) {
                if ("boolean".equals(md.getReturnTypeName())) return md.getMethodInstance(cl);
            }
            return null;
        });
        if (gate == null) return;
        XposedBridge.hookMethod(gate, new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam param) { ps.reso.instaeclipse.utils.core.JitGuard.tick(param.method);
                if (FeatureFlags.reelsAutoScroll) param.setResult(true);
            }
        });
        FeatureStatusTracker.setHooked("reelsAutoScroll");
        ModuleLog.line(TAG + "✅ auto-scroll gate " + gate.getDeclaringClass().getName() + "." + gate.getName());
    }
}
