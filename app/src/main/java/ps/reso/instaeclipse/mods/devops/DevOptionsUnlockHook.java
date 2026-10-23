package ps.reso.instaeclipse.mods.devops;

import org.luckypray.dexkit.DexKitBridge;
import org.luckypray.dexkit.query.FindMethod;
import org.luckypray.dexkit.query.matchers.MethodMatcher;
import org.luckypray.dexkit.result.MethodData;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import ps.reso.instaeclipse.xposedcompat.XC_MethodHook;
import ps.reso.instaeclipse.xposedcompat.XposedBridge;
import ps.reso.instaeclipse.Xposed.Module;
import ps.reso.instaeclipse.utils.core.DexKitCache;
import ps.reso.instaeclipse.utils.feature.FeatureFlags;
import ps.reso.instaeclipse.utils.feature.FeatureStatusTracker;
import ps.reso.instaeclipse.utils.log.ModuleLog;
import ps.reso.instaeclipse.utils.core.DexKitQueryCache;

public class DevOptionsUnlockHook {

    private static final String CACHE_KEY = "DevOptionsMethod_v2";
    private static final String GATE_ANCHOR = "caller is not an employee";
    private static final String CROSS_CHECK_ANCHOR = "Enabling rageshake for employee";

    public void handleDevOptions(DexKitBridge bridge) {
        if (DexKitCache.isCacheValid()) {
            Method cachedMethod = DexKitCache.loadMethod(CACHE_KEY, Module.hostClassLoader);
            if (cachedMethod != null) {
                hookExactMethod(cachedMethod);
                return;
            }
        }
        try {
            Method gate = findEmployeeGate(bridge);
            if (gate == null) return;
            DexKitCache.saveMethod(CACHE_KEY, gate);
            hookExactMethod(gate);
        } catch (Throwable e) {
            ModuleLog.error("(InstaEclipse | DevOptionsEnable): Could not unlock developer options", e);
        }
    }

    private Method findEmployeeGate(DexKitBridge bridge) throws NoSuchMethodException {
        Map<String, MethodData> invoked = new LinkedHashMap<>();
        for (MethodData anchor : usingString(bridge, GATE_ANCHOR)) {
            for (MethodData call : anchor.getInvokes()) invoked.put(call.getDescriptor(), call);
        }
        String gate = DevGatePick.choose(new ArrayList<>(invoked.keySet()), descriptor -> isStatic(invoked.get(descriptor)));
        if (gate == null) {
            ModuleLog.error("(InstaEclipse | DevOptionsEnable): Could not unlock developer options: the check was not found in this Instagram version");
            return null;
        }
        crossCheck(bridge, gate);
        return invoked.get(gate).getMethodInstance(Module.hostClassLoader);
    }

    private void crossCheck(DexKitBridge bridge, String gate) {
        try {
            for (MethodData other : usingString(bridge, CROSS_CHECK_ANCHOR)) {
                for (MethodData call : other.getInvokes()) {
                    if (gate.equals(call.getDescriptor())) {
                        ModuleLog.detail("(InstaEclipse | DevOptionsEnable): " + gate + " is also checked by " + other.getClassName() + "." + other.getName());
                        return;
                    }
                }
            }
            ModuleLog.warn("(InstaEclipse | DevOptionsEnable): The rageshake employee check does not call " + gate);
        } catch (Throwable e) {
            ModuleLog.detail("(InstaEclipse | DevOptionsEnable): Cross-check failed: " + e);
        }
    }

    private static List<MethodData> usingString(DexKitBridge bridge, String text) {
        return DexKitQueryCache.findMethod(bridge, FindMethod.create()
                .matcher(MethodMatcher.create().usingStrings(text)));
    }

    private static boolean isStatic(MethodData data) {
        try {
            return Modifier.isStatic(data.getMethodInstance(Module.hostClassLoader).getModifiers());
        } catch (Throwable e) {
            return false;
        }
    }

    private void hookExactMethod(Method m) {
        try {
            m.setAccessible(true);
            XposedBridge.hookMethod(m, new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) { ps.reso.instaeclipse.utils.core.JitGuard.tick(param.method);
                    if (FeatureFlags.isDevEnabled) {
                        param.setResult(true);
                        FeatureStatusTracker.setHooked("DevOptions");
                    }
                }
            });
            ModuleLog.ok("(InstaEclipse | DevOptionsEnable): ready");
            ModuleLog.detail("(InstaEclipse | DevOptionsEnable): Hooked: " + m.getDeclaringClass().getName() + "." + m.getName());
        } catch (Throwable e) {
            ModuleLog.error("(InstaEclipse | DevOptionsEnable): Could not unlock developer options", e);
        }
    }
}
