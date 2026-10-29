package ps.reso.instaeclipse.mods.feed;

import org.luckypray.dexkit.query.FindMethod;
import org.luckypray.dexkit.query.matchers.MethodMatcher;
import org.luckypray.dexkit.result.MethodData;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
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

public class ExpandCaptionsHook {

    private static final String TAG = "(InstaEclipse | ExpandCaptions): ";
    private static final String CACHE_KEY = "ExpandCaptions_builder_helper";
    private static final ThreadLocal<Integer> IN_BUILDER = new ThreadLocal<>();

    public void install() {
        List<Method> pair = DexKitCache.isCacheValid() ? DexKitCache.loadMethods(CACHE_KEY, Module.hostClassLoader) : null;
        if (pair == null || pair.size() != 2) {
            pair = find();
            if (pair == null) return;
            DexKitCache.saveMethods(CACHE_KEY, pair);
        }
        Method builder = pair.get(0), helper = pair.get(1);
        XposedBridge.hookMethod(builder, new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) {
                Integer d = IN_BUILDER.get();
                IN_BUILDER.set(d == null ? 1 : d + 1);
            }

            @Override
            protected void afterHookedMethod(MethodHookParam param) {
                Integer d = IN_BUILDER.get();
                if (d == null || d <= 1) IN_BUILDER.remove(); else IN_BUILDER.set(d - 1);
            }
        });
        XposedBridge.hookMethod(helper, new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) {
                if (!FeatureFlags.expandCaptions || IN_BUILDER.get() == null) return;
                if (param.args[4] instanceof Integer) param.args[4] = Integer.MAX_VALUE;
            }
        });
        ModuleLog.ok(TAG + "ready");
        ModuleLog.detail(TAG + "hooked " + builder.getDeclaringClass().getName() + "." + builder.getName()
                + " → " + helper.getDeclaringClass().getName() + "." + helper.getName());
        FeatureStatusTracker.setHooked("ExpandCaptions");
    }

    private static List<Method> find() {
        try {
            for (Found md : DexKitQueryCache.methods(FindMethod.create().matcher(MethodMatcher.create()
                    .usingStrings(" [...]").paramCount(7)))) {
                Method b = md.getMethodInstance(Module.hostClassLoader);
                if (!Modifier.isStatic(b.getModifiers()) || b.getReturnType() == void.class) continue;
                for (MethodData inv : DexKitQueryCache.invokes(md)) {
                    try {
                        if (!inv.isMethod()) continue;
                        Method h = inv.getMethodInstance(Module.hostClassLoader);
                        Class<?>[] p = h.getParameterTypes();
                        if (Modifier.isStatic(h.getModifiers()) && h.getReturnType() == CharSequence.class
                                && p.length == 6 && p[1] == CharSequence.class && p[2] == CharSequence.class
                                && p[3] == CharSequence.class && p[4] == int.class && p[5] == boolean.class) {
                            List<Method> out = new ArrayList<>();
                            out.add(b);
                            out.add(h);
                            return out;
                        }
                    } catch (Throwable ignored) {}
                }
            }
            ModuleLog.error(TAG + "Will not work: the caption code was not found in this Instagram version");
            ModuleLog.detail(TAG + "caption builder / truncation helper not found");
        } catch (Throwable t) {
            ModuleLog.error(TAG + "Will not work: search for the caption code failed", t);
        }
        return null;
    }
}
