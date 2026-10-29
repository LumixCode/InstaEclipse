package ps.reso.instaeclipse.mods.ghost;

import org.luckypray.dexkit.query.FindMethod;
import org.luckypray.dexkit.query.matchers.MethodMatcher;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.List;

import ps.reso.instaeclipse.xposedcompat.XC_MethodHook;
import ps.reso.instaeclipse.xposedcompat.XposedBridge;
import ps.reso.instaeclipse.utils.core.DexKitCache;
import ps.reso.instaeclipse.utils.feature.FeatureFlags;
import ps.reso.instaeclipse.utils.feature.FeatureStatusTracker;
import ps.reso.instaeclipse.utils.log.ModuleLog;
import ps.reso.instaeclipse.utils.core.DexKitQueryCache;
import ps.reso.instaeclipse.utils.core.DexKitQueryCache.Found;

public final class SearchHistoryHook {

    private static final String TAG = "(IE|SearchHistory) ";
    private static final String CACHE_KEY = "RecentSearchCache_add";

    private SearchHistoryHook() {}

    public static void install(ClassLoader cl) {
        try {
            Method add = DexKitCache.isCacheValid() ? DexKitCache.loadMethod(CACHE_KEY, cl) : null;
            if (add == null) {
                List<Found> owners = DexKitQueryCache.methods(FindMethod.create().matcher(MethodMatcher.create()
                        .usingStrings("Error saving recent searches. Clearing results.")));
                if (owners.isEmpty()) {
                    ModuleLog.error(TAG + "Could not stop Instagram from saving searches: not supported by this Instagram version (recent search cache not found)");
                    return;
                }
                for (Found md : DexKitQueryCache.methods(FindMethod.create().matcher(MethodMatcher.create()
                        .declaredClass(owners.get(0).getClassName())
                        .paramCount(1).returnType("void")
                        .addInvoke("Ljava/util/List;->add(ILjava/lang/Object;)V")))) {
                    Method m = md.getMethodInstance(cl);
                    if (Modifier.isStatic(m.getModifiers()) || m.getParameterTypes()[0] == List.class) continue;
                    add = m;
                    break;
                }
                if (add == null) {
                    ModuleLog.error(TAG + "Could not stop Instagram from saving searches: not supported by this Instagram version");
                    ModuleLog.detail(TAG + "add method not found in " + owners.get(0).getClassName());
                    return;
                }
                DexKitCache.saveMethod(CACHE_KEY, add);
            }
            XposedBridge.hookMethod(add, new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    if (FeatureFlags.disableSearchHistory) param.setResult(null);
                }
            });
            FeatureStatusTracker.setHooked("DisableSearchHistory");
            ModuleLog.ok(TAG + "ready");
            ModuleLog.detail(TAG + "hooked " + add.getDeclaringClass().getName() + "." + add.getName());
        } catch (Throwable t) {
            ModuleLog.error(TAG + "Could not stop Instagram from saving searches", t);
        }
    }
}
