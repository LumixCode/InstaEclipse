package ps.reso.instaeclipse.mods.network;

import java.lang.reflect.Method;

import ps.reso.instaeclipse.utils.core.DexKitQueryCache;
import ps.reso.instaeclipse.utils.core.ModuleBridge;
import ps.reso.instaeclipse.utils.log.ModuleLog;
import ps.reso.instaeclipse.xposedcompat.XC_MethodHook;
import ps.reso.instaeclipse.xposedcompat.XposedBridge;

public final class NetworkChainEntry {

    private NetworkChainEntry() {}

    public static void install(ClassLoader classLoader) {
        Class<?>[] params = IGNetworkInterceptor.startRequestParams;
        XC_MethodHook dropHook = IGNetworkInterceptor.dropHook;
        if (params == null || dropHook == null) return;
        Class<?> p1 = params[0], p2 = params[1], p3 = params[2];
        try {
            org.luckypray.dexkit.DexKitBridge bridge = ModuleBridge.get();
            if (bridge == null) return;
            java.util.List<org.luckypray.dexkit.result.MethodData> found =
                    DexKitQueryCache.findMethod(bridge,
                            org.luckypray.dexkit.query.FindMethod.create().matcher(
                                    org.luckypray.dexkit.query.matchers.MethodMatcher.create()
                                            .name("startRequest")
                                            .usingStrings("AsyncHttpService.startRequest")));
            int n = 0;
            for (org.luckypray.dexkit.result.MethodData md : found) {
                Method m = md.getMethodInstance(classLoader);
                Class<?>[] pt = m.getParameterTypes();
                if (pt.length != 3 || pt[0] != p1 || pt[1] != p2 || pt[2] != p3) continue;
                XposedBridge.hookMethod(m, dropHook);
                n++;
            }
            ModuleLog.detail("(InstaEclipse | Interceptor): chain-entry hooks: " + n);
        } catch (Throwable t) {
            ModuleLog.warn("(InstaEclipse | Interceptor): Requests Instagram answers from its own cache may not be filtered; all other requests still are: "
                    + t.getClass().getSimpleName() + ": " + t.getMessage());
        }
    }
}
