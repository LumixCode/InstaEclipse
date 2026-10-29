package ps.reso.instaeclipse.mods.feed;

import org.luckypray.dexkit.query.FindMethod;
import org.luckypray.dexkit.query.matchers.MethodMatcher;

import java.lang.reflect.Method;
import java.util.List;

import ps.reso.instaeclipse.xposedcompat.XC_MethodHook;
import ps.reso.instaeclipse.xposedcompat.XposedBridge;
import ps.reso.instaeclipse.utils.core.DexKitCache;
import ps.reso.instaeclipse.utils.feature.FeatureFlags;
import ps.reso.instaeclipse.utils.feature.FeatureStatusTracker;
import ps.reso.instaeclipse.utils.log.ModuleLog;
import ps.reso.instaeclipse.utils.core.DexKitQueryCache;
import ps.reso.instaeclipse.utils.core.DexKitQueryCache.Found;

public class HideCommunityNotesHook {

    private static final String TAG = "(InstaEclipse | CommunityNotes): ";
    private static final String CACHE_KEY = "CommunityNotesGetter";
    private static final String PANDO = "com.instagram.api.schemas.ImmutablePandoMediaOverlayPayloadSchema";
    private static final String MUTABLE = "com.instagram.api.schemas.MediaOverlayPayloadSchema";
    private static final int NOTES_BLOKS_DATA = 0xc2d475a;

    public void install(ClassLoader cl) {
        String name = DexKitCache.isCacheValid() ? DexKitCache.loadString(CACHE_KEY) : null;
        if (name == null) {
            try {
                List<Found> found = DexKitQueryCache.methods(FindMethod.create().matcher(MethodMatcher.create()
                        .declaredClass(PANDO).paramCount(0).usingNumbers(NOTES_BLOKS_DATA)));
                if (found.size() != 1) {
                    ModuleLog.error(TAG + "Will not work: community notes were not found in this Instagram version");
                    ModuleLog.detail(TAG + "notes getter not found (" + found.size() + ")");
                    return;
                }
                name = found.get(0).getName();
                DexKitCache.saveString(CACHE_KEY, name);
            } catch (Throwable t) {
                ModuleLog.error(TAG + "Will not work: search for community notes failed", t);
                return;
            }
        }
        XC_MethodHook hide = new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) {
                if (FeatureFlags.hideCommunityNotes) param.setResult(null);
            }
        };
        int n = 0;
        for (String cls : new String[]{PANDO, MUTABLE}) {
            try {
                Method m = cl.loadClass(cls).getDeclaredMethod(name);
                XposedBridge.hookMethod(m, hide);
                n++;
            } catch (Throwable t) {
                ModuleLog.warn(TAG + "Community notes may still show on some posts: " + t.getClass().getSimpleName());
                ModuleLog.detail(TAG + cls + "." + name + ": " + t);
            }
        }
        if (n > 0) ModuleLog.ok(TAG + "ready");
        else ModuleLog.error(TAG + "Will not work: community notes could not be hidden in this Instagram version");
        ModuleLog.detail(TAG + "notes getter " + name + " hooked on " + n + " class(es)");
        if (n > 0) FeatureStatusTracker.setHooked("HideCommunityNotes");
    }
}
