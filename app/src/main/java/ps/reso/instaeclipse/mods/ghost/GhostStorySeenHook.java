package ps.reso.instaeclipse.mods.ghost;

import org.luckypray.dexkit.query.FindMethod;
import org.luckypray.dexkit.query.matchers.MethodMatcher;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import ps.reso.instaeclipse.xposedcompat.XC_MethodHook;
import ps.reso.instaeclipse.xposedcompat.XposedBridge;
import ps.reso.instaeclipse.Xposed.Module;
import ps.reso.instaeclipse.utils.core.DexKitCache;
import ps.reso.instaeclipse.utils.feature.FeatureFlags;
import ps.reso.instaeclipse.utils.feature.FeatureStatusTracker;
import ps.reso.instaeclipse.utils.log.ModuleLog;
import ps.reso.instaeclipse.utils.core.DexKitQueryCache;
import ps.reso.instaeclipse.utils.core.DexKitQueryCache.Found;

public class GhostStorySeenHook {

    private static final String CACHE_KEY = "GhostStorySeen_v3";

    public void handleStorySeenBlock() {
        if (DexKitCache.isCacheValid()) {
            String storeName = DexKitCache.loadString(CACHE_KEY);
            if (storeName != null) {
                try {
                    Class<?> storeClass = Class.forName(storeName, false, Module.hostClassLoader);
                    int n = hookStoreSendersScopedTo(storeClass);
                    ModuleLog.detail("(InstaEclipse | StoryBlock): hooked (cached) " + n + " sender(s) on " + storeName);
                    if (n > 0) {
                        ModuleLog.ok("(InstaEclipse | StoryBlock): ready");
                        FeatureStatusTracker.setHooked("GhostStories");
                        return;
                    }
                } catch (Throwable t) {
                    ModuleLog.detail("(InstaEclipse | StoryBlock): cached modern resolve failed: " + t.getMessage());
                }
            }
        }

        if (tryModernBatchBlock()) return;
        ModuleLog.error("(InstaEclipse | StoryBlock): Could not turn on hiding story views: not supported by this Instagram version");
    }

    private boolean tryModernBatchBlock() {
        try {
            List<Found> seenMethods = DexKitQueryCache.methods(FindMethod.create()
                    .matcher(MethodMatcher.create().usingStrings("media/seen/")));
            if (seenMethods.isEmpty()) return false;

            Found builder = null;
            for (Found m : seenMethods) {
                if (m.getParamCount() == 1 && !"void".equals(m.getReturnTypeName())) {
                    builder = m;
                    break;
                }
            }
            if (builder == null) return false;

            String itemClassName = builder.getClassName();
            Set<String> names = new LinkedHashSet<>();
            for (Found caller : DexKitQueryCache.callers(builder)) {
                if (caller.getClassName() != null) names.add(caller.getClassName());
            }
            List<StorySeenStorePick.Caller> callers = new ArrayList<>();
            for (String name : names) {
                try {
                    callers.add(StorySeenStorePick.shape(Class.forName(name, false, Module.hostClassLoader)));
                } catch (Throwable t) {
                    ModuleLog.detail("(InstaEclipse | StoryBlock): caller " + name + " unreadable: " + t);
                }
            }
            String storeClassName = StorySeenStorePick.pick(itemClassName, callers);
            if (storeClassName == null) {
                ModuleLog.detail("(InstaEclipse | StoryBlock): no single store among callers " + names);
                return false;
            }

            Class<?> storeClass = Class.forName(storeClassName, false, Module.hostClassLoader);
            int n = hookStoreSendersScopedTo(storeClass);
            if (n == 0) return false;

            DexKitCache.saveString(CACHE_KEY, storeClassName);
            ModuleLog.ok("(InstaEclipse | StoryBlock): ready");
            ModuleLog.detail("(InstaEclipse | StoryBlock): hooked " + n + " sender(s) on store " + storeClassName
                    + " (item builder: " + itemClassName + "." + builder.getName() + ")");
            FeatureStatusTracker.setHooked("GhostStories");
            return true;
        } catch (Throwable t) {
            ModuleLog.detail("(InstaEclipse | StoryBlock): lookup failed: " + t.getMessage());
            return false;
        }
    }

    private int hookStoreSendersScopedTo(final Class<?> storeClass) {
        XC_MethodHook gate = new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) {
                if (FeatureFlags.isGhostStory
                        && param.thisObject != null
                        && storeClass.isInstance(param.thisObject)) {
                    if (param.args.length == 1 && !(param.args[0] instanceof Map)
                            && ManualStorySeenHook.isExplicitItem(param.args[0])) return;
                    param.setResult(null);
                }
            }
        };

        int hooked = 0;

        for (Class<?> c = storeClass.getSuperclass(); c != null && c != Object.class; c = c.getSuperclass()) {
            boolean found = false;
            for (Method m : ps.reso.instaeclipse.utils.core.SafeReflect.methods(c)) {
                if (m.getReturnType() != void.class) continue;
                Class<?>[] p = m.getParameterTypes();
                boolean perItem = p.length == 2 && p[0] == String.class && p[1] == Object.class;
                boolean bulk    = p.length == 1 && Map.class.isAssignableFrom(p[0]);
                if (!perItem && !bulk) continue;
                try { m.setAccessible(true); XposedBridge.hookMethod(m, gate); hooked++; found = true; }
                catch (Throwable t) { ModuleLog.warn("(InstaEclipse | StoryBlock): Some story views may still be sent", t); }
            }
            if (found) break;
        }

        for (Method m : ps.reso.instaeclipse.utils.core.SafeReflect.methods(storeClass)) {
            if (m.getReturnType() != void.class) continue;
            Class<?>[] p = m.getParameterTypes();
            if (p.length != 1) continue;
            Class<?> pt = p[0];
            if (pt.isPrimitive() || pt == String.class || Map.class.isAssignableFrom(pt)
                    || java.util.Collection.class.isAssignableFrom(pt)) continue;
            try { m.setAccessible(true); XposedBridge.hookMethod(m, gate); hooked++; }
            catch (Throwable t) { ModuleLog.warn("(InstaEclipse | StoryBlock): Some story views may still be sent right away", t); }
        }

        return hooked;
    }
}
