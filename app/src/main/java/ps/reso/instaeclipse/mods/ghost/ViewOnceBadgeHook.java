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

/**
 * Restores Instagram's NATIVE view-once / view-twice corner icon that the "Show View-Once/Twice
 * as Normal Media" feature (permanentViewMode) hides by rewriting view_mode -> "permanent".
 *
 * Render path (447): the visual message's render-props object (X/0E4S) carries the view_mode
 * String; the composable X/0YKc.A00 (RavenCornerIcon) draws the once vs twice icon based on it.
 * Because permanentViewMode rewrites the model's view_mode to "permanent" at parse, by the time
 * the props are built the mode is "permanent" and no icon is drawn.
 *
 * Fix: GhostPermanentViewHook stashes the ORIGINAL "once"/"replayable" (keyed by media id) before
 * the rewrite. Here we hook the props constructor (anchored on a ctor that inlines BOTH the "once"
 * and "replayable" strings — that is 0E4S), and when its mode is "permanent" and one of its
 * id-like String fields matches the stash, we put the original mode back so IG draws its own icon.
 *
 * Gated on permanentViewMode (the icon is only missing when that feature is active).
 */
public class ViewOnceBadgeHook {

    public void install(ClassLoader classLoader) {
        XC_MethodHook hook = buildHook();

        try {
            // The render-props ctor inlines BOTH "once" and "replayable" to compute its
            // "isRaven" flag. RavenCornerIcon (0YKc.A00) also references both, so require <init>.
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
                    // GhostPermanentViewHook rewrote the model's view_mode to "permanent" (both
                    // types, so IG keeps it re-viewable). Map it BACK to the real ephemeral value
                    // here (render props only) so IG's own ctor computes the ephemeral-gate boolean
                    // and draws the correct native 1-vs-2 badge. The original once/twice type is
                    // looked up from ORIGINAL_BY_KEY via an id/timestamp long shared with the model.
                    int modeIdx = -1;
                    for (int i = 0; i < args.length; i++) {
                        if (GhostPermanentViewHook.PERMANENT.equals(args[i])) { modeIdx = i; break; }
                    }
                    if (modeIdx < 0) return; // not a rewritten view-once bubble

                    List<Long> candidates = IdKeys.collect(args, ViewOnceStash::mediaKey);
                    ViewOnceBadgeMode.rewrite(GhostPermanentViewHook.STASH.find(candidates), args, modeIdx);
                } catch (Throwable t) {
                    ModuleLog.detail("(IE|VOBadge) badge update failed: " + t);
                }
            }
        };
    }
}
