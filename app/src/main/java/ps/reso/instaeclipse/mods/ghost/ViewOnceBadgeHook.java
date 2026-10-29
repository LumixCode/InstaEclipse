package ps.reso.instaeclipse.mods.ghost;

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
import ps.reso.instaeclipse.utils.ghost.IdKeys;

public class ViewOnceBadgeHook {

    public void install(ClassLoader classLoader) {
        XC_MethodHook hook = buildHook();

        try {
            List<Found> methods = DexKitQueryCache.methods(FindMethod.create()
                    .matcher(MethodMatcher.create()
                            .name("<init>")
                            .usingStrings("once", "replayable")));
            if (methods.isEmpty()) {
                ModuleLog.warn("(IE|VOBadge) The view-once icon is not shown on this Instagram version (the media still stays viewable)");
                ModuleLog.detail("(IE|VOBadge) view-once props ctor not found");
                if (FeatureFlags.permanentViewMode) FeatureStatusTracker.setBroken("ViewOnceBadge");
                return;
            }
            int hooked = 0;
            for (Found md : methods) {
                if (!md.isConstructor()) continue;
                try {
                    XposedBridge.hookMethod(md.getConstructorInstance(classLoader), hook);
                    ModuleLog.detail("(IE|VOBadge) hooked " + md.getClassName() + ".<init>");
                    hooked++;
                } catch (Throwable e) {
                    ModuleLog.detail("(IE|VOBadge) skip " + md.getClassName() + ": " + e);
                }
            }
            if (hooked == 0) {
                ModuleLog.warn("(IE|VOBadge) The view-once icon is not shown on this Instagram version (the media still stays viewable)");
                ModuleLog.detail("(IE|VOBadge) props ctor found but none reflectable");
                if (FeatureFlags.permanentViewMode) FeatureStatusTracker.setBroken("ViewOnceBadge");
                return;
            }
            ModuleLog.ok("(IE|VOBadge) ready");
            FeatureStatusTracker.setHooked("ViewOnceBadge");
        } catch (Throwable t) {
            ModuleLog.warn("(IE|VOBadge) The view-once icon could not be set up (the media still stays viewable)", t);
            if (FeatureFlags.permanentViewMode) FeatureStatusTracker.setBroken("ViewOnceBadge");
        }
    }

    private XC_MethodHook buildHook() {
        return new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) {
                if (!FeatureFlags.permanentViewMode) return;
                Object[] args = param.args;
                if (args == null) return;
                try {
                    int modeIdx = -1;
                    for (int i = 0; i < args.length; i++) {
                        if (GhostPermanentViewHook.PERMANENT.equals(args[i])) { modeIdx = i; break; }
                    }
                    if (modeIdx < 0) return;

                    List<Long> candidates = IdKeys.collect(args, ViewOnceStash::mediaKey);
                    ViewOnceBadgeMode.rewrite(GhostPermanentViewHook.STASH.find(candidates), args, modeIdx);
                } catch (Throwable t) {
                    ModuleLog.detail("(IE|VOBadge) badge update failed: " + t);
                }
            }
        };
    }
}
