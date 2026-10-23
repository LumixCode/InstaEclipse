package ps.reso.instaeclipse.mods.extras;

import org.luckypray.dexkit.DexKitBridge;
import org.luckypray.dexkit.query.FindMethod;
import org.luckypray.dexkit.query.matchers.MethodMatcher;
import org.luckypray.dexkit.result.MethodData;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

import ps.reso.instaeclipse.xposedcompat.XC_MethodHook;
import ps.reso.instaeclipse.xposedcompat.XposedBridge;
import ps.reso.instaeclipse.utils.core.DexKitCache;
import ps.reso.instaeclipse.utils.feature.FeatureFlags;
import ps.reso.instaeclipse.utils.feature.FeatureStatusTracker;
import ps.reso.instaeclipse.utils.log.ModuleLog;
import ps.reso.instaeclipse.utils.core.DexKitQueryCache;

public final class HighResolutionImageHook {

    private static final String TAG = "(InstaEclipse | HighResImages): ";
    private static final String CACHE = "Extras_HighResImage";
    private static final String IMAGE = "com.instagram.model.mediasize.ExtendedImageUrl";

    public void install(DexKitBridge bridge, ClassLoader cl) {
        List<Method> pickers = DexKitCache.isCacheValid() ? DexKitCache.loadMethods(CACHE, cl) : null;
        if (pickers == null || pickers.isEmpty()) {
            pickers = new ArrayList<>();
            for (MethodData md : DexKitQueryCache.findMethod(bridge, FindMethod.create().matcher(MethodMatcher.create()
                    .returnType(IMAGE).paramTypes("java.lang.Integer", "java.util.List", "int")))) {
                try {
                    pickers.add(md.getMethodInstance(cl));
                } catch (Throwable ignored) {}
            }
            if (pickers.isEmpty()) {
                notWorking("the image size picker was not found in this Instagram version");
                return;
            }
            DexKitCache.saveMethods(CACHE, pickers);
        }
        Class<?> resultType = pickers.get(0).getReturnType();
        Method width, height;
        try {
            width = resultType.getMethod("getWidth");
            height = resultType.getMethod("getHeight");
        } catch (NoSuchMethodException missing) {
            notWorking("the image size getters were not found in this Instagram version");
            return;
        }
        XC_MethodHook pick = new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam p) throws Throwable { ps.reso.instaeclipse.utils.core.JitGuard.tick(p.method);
                if (!FeatureFlags.highResolutionImages) return;
                if (p.hasThrowable() || !(p.args[1] instanceof List)) return;
                Object picked = p.getResult();
                if (picked == null) return;
                Object[] candidates = ((List<?>) p.args[1]).toArray();
                int[] w = new int[candidates.length], h = new int[candidates.length];
                int igIndex = -1;
                for (int i = 0; i < candidates.length; i++) {
                    Object candidate = candidates[i];
                    if (igIndex < 0 && candidate == picked) igIndex = i;
                    if (!resultType.isInstance(candidate)) continue;
                    Object x = width.invoke(candidate), y = height.invoke(candidate);
                    if (!(x instanceof Number) || !(y instanceof Number)) continue;
                    w[i] = ((Number) x).intValue();
                    h[i] = ((Number) y).intValue();
                }
                int target = p.args[2] instanceof Number ? ((Number) p.args[2]).intValue() : 0;
                int best = HighResPick.choose(w, h, igIndex, target);
                if (best >= 0) p.setResult(candidates[best]);
            }
        };
        int hooked = 0;
        for (Method m : pickers) {
            try {
                XposedBridge.hookMethod(m, pick);
                hooked++;
            } catch (Throwable t) {
                ModuleLog.detail(TAG + "could not hook " + m.getDeclaringClass().getName() + "." + m.getName() + ": " + t);
            }
        }
        if (hooked == 0) {
            notWorking("no image size picker could be hooked");
            return;
        }
        FeatureStatusTracker.setHooked("highResolutionImages");
        ModuleLog.ok(TAG + "ready");
        ModuleLog.detail(TAG + "hooked " + hooked + " picker(s)");
    }

    private static void notWorking(String reason) {
        String message = TAG + "Could not turn on high-resolution images: " + reason;
        if (FeatureFlags.highResolutionImages) {
            ModuleLog.error(message);
            FeatureStatusTracker.setBroken("highResolutionImages");
        } else {
            ModuleLog.detail(message);
        }
    }
}
