package ps.reso.instaeclipse.mods.core;

import org.luckypray.dexkit.DexKitBridge;
import org.luckypray.dexkit.query.FindMethod;
import org.luckypray.dexkit.query.matchers.MethodMatcher;
import org.luckypray.dexkit.result.MethodData;

import java.util.Set;
import java.util.TreeSet;

import ps.reso.instaeclipse.utils.core.DexKitCache;
import ps.reso.instaeclipse.utils.core.DexKitQueryCache;
import ps.reso.instaeclipse.utils.core.LegacyPaths;
import ps.reso.instaeclipse.utils.log.ModuleLog;

public final class ExecGuardDispatchers {

    private static final String CACHE_KEY = "ExecGuardDispatchers";
    private static final String ANCHOR = "The task was rejected";

    private ExecGuardDispatchers() {}

    public static void install(DexKitBridge bridge) {
        if (LegacyPaths.has(LegacyPaths.EXECGUARD)) return;
        String cached = DexKitCache.isCacheValid() ? DexKitCache.loadString(CACHE_KEY) : null;
        Set<String> classes = new TreeSet<>();
        if (cached != null) {
            for (String name : cached.split(",")) if (!name.isEmpty()) classes.add(name);
        } else {
            for (MethodData md : DexKitQueryCache.findMethod(bridge, FindMethod.create().matcher(MethodMatcher.create()
                    .usingStrings(ANCHOR)))) {
                classes.add(md.getClassName());
            }
            DexKitCache.saveString(CACHE_KEY, String.join(",", classes));
        }
        TerminatedExecutorGuard.setDispatcherClasses(classes);
        ModuleLog.detail("(IE|ExecGuard) rejections left to Instagram's coroutine dispatchers: " + classes);
    }
}
