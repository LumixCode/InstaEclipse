package ps.reso.instaeclipse.mods.ui;

import android.app.Activity;

import org.luckypray.dexkit.DexKitBridge;
import org.luckypray.dexkit.query.FindMethod;
import org.luckypray.dexkit.query.matchers.MethodMatcher;
import org.luckypray.dexkit.result.MethodData;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import ps.reso.instaeclipse.utils.core.DexKitCache;
import ps.reso.instaeclipse.utils.core.DexKitQueryCache;
import ps.reso.instaeclipse.utils.core.JitGuard;
import ps.reso.instaeclipse.utils.core.ModuleBridge;
import ps.reso.instaeclipse.utils.feature.FeatureFlags;
import ps.reso.instaeclipse.utils.log.ModuleLog;
import ps.reso.instaeclipse.xposedcompat.XC_MethodHook;
import ps.reso.instaeclipse.xposedcompat.XposedBridge;

public final class BackLeavesHomeDispatcher {

    private static final String TAG = "(IE|BackLeavesHome) ";
    private static final String CACHE_KEY = "BackLeavesHome_dispatcher_v1";
    private static final String COMPONENT_ACTIVITY = "androidx.activity.ComponentActivity";
    private static final String NOT_FOUND = "Back on the home feed may not leave Instagram on Android 16 and newer: ";
    private static Method dispatcherGetter;

    private BackLeavesHomeDispatcher() {}

    static void install(ClassLoader cl) {
        try {
            Method dispatch = DexKitCache.isCacheValid() ? DexKitCache.loadMethod(CACHE_KEY, cl) : null;
            if (dispatch == null) {
                dispatch = find(ModuleBridge.get(), cl);
                if (dispatch == null) return;
                DexKitCache.saveMethod(CACHE_KEY, dispatch);
            }
            Method getter = getter(cl.loadClass(COMPONENT_ACTIVITY), dispatch.getDeclaringClass());
            if (getter == null) {
                ModuleLog.warn(TAG + NOT_FOUND + "the activity's Back dispatcher was not found in this Instagram version");
                return;
            }
            dispatcherGetter = getter;
            XposedBridge.hookMethod(dispatch, new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) { JitGuard.tick(param.method);
                    if (!FeatureFlags.backLeavesHome) return;
                    Activity a = UIHookManager.getCurrentActivity();
                    if (a == null || a.isFinishing() || !owns(a, param.thisObject)) return;
                    if (BackLeavesHomeHook.leaveIfHomeFeed(a)) param.setResult(null);
                }
            });
            ModuleLog.detail(TAG + "hooked " + dispatch.getDeclaringClass().getName() + "." + dispatch.getName()
                    + " (Back dispatcher)");
        } catch (Throwable t) {
            ModuleLog.warn(TAG + NOT_FOUND + t.getClass().getSimpleName() + ": " + t.getMessage());
        }
    }

    private static boolean owns(Activity a, Object dispatcher) {
        try {
            return dispatcherGetter.invoke(a) == dispatcher;
        } catch (Throwable ignored) {
            return false;
        }
    }

    private static Method find(DexKitBridge bridge, ClassLoader cl) {
        if (bridge == null) return null;
        List<MethodData> back = DexKitQueryCache.findMethod(bridge, FindMethod.create().matcher(MethodMatcher.create()
                .declaredClass(COMPONENT_ACTIVITY)
                .name("onBackPressed")
                .paramCount(0)));
        if (back.size() != 1) {
            ModuleLog.warn(TAG + NOT_FOUND + "the activity's Back handler was not found in this Instagram version");
            return null;
        }
        List<MethodData> invokes = back.get(0).getInvokes();
        List<String> descriptors = new ArrayList<>();
        for (MethodData m : invokes) descriptors.add(m.getDescriptor());
        String picked = pick(descriptors);
        if (picked == null) {
            ModuleLog.warn(TAG + NOT_FOUND + "the Back dispatcher was not found in this Instagram version");
            ModuleLog.detail(TAG + "onBackPressed invokes " + descriptors);
            return null;
        }
        for (MethodData m : invokes) {
            if (!picked.equals(m.getDescriptor())) continue;
            try {
                return m.getMethodInstance(cl);
            } catch (Throwable t) {
                ModuleLog.warn(TAG + NOT_FOUND + t.getClass().getSimpleName() + ": " + t.getMessage());
                return null;
            }
        }
        return null;
    }

    static String pick(List<String> invokes) {
        Set<String> owners = new HashSet<>();
        for (String d : invokes) {
            String ret = returnType(d);
            if (params(d).isEmpty() && ret.startsWith("L")) owners.add(ret);
        }
        String found = null;
        for (String d : invokes) {
            if (!params(d).isEmpty() || !"V".equals(returnType(d)) || !owners.contains(owner(d))) continue;
            if (found != null && !found.equals(d)) return null;
            found = d;
        }
        return found;
    }

    private static String owner(String descriptor) {
        int arrow = descriptor.indexOf("->");
        return arrow < 0 ? "" : descriptor.substring(0, arrow);
    }

    private static String params(String descriptor) {
        int open = descriptor.indexOf('(');
        int close = descriptor.indexOf(')', open + 1);
        return open < 0 || close < 0 ? "?" : descriptor.substring(open + 1, close);
    }

    private static String returnType(String descriptor) {
        int close = descriptor.indexOf(')');
        return close < 0 ? "" : descriptor.substring(close + 1);
    }

    private static Method getter(Class<?> activity, Class<?> dispatcher) {
        Method found = null;
        for (Method m : activity.getDeclaredMethods()) {
            if (m.getParameterCount() != 0 || m.getReturnType() != dispatcher || Modifier.isStatic(m.getModifiers())) continue;
            if (found != null) return null;
            found = m;
        }
        if (found != null) found.setAccessible(true);
        return found;
    }
}
