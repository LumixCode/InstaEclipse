package ps.reso.instaeclipse.mods.ghost;

import org.luckypray.dexkit.DexKitBridge;
import org.luckypray.dexkit.query.FindMethod;
import org.luckypray.dexkit.query.matchers.MethodMatcher;
import org.luckypray.dexkit.result.MethodData;

import java.lang.reflect.Field;
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

/**
 * Makes view-once and view-twice (replayable) media behave like permanent media.
 *
 * Instagram parses the server's JSON "view_mode" field in unsafeParseFromJson
 * (class X/1Ui in build 423) and stores it as a plain String on the media model.
 *
 * Possible values:
 *   "once"       — view once (disappears after one open)
 *   "replayable" — view twice (one extra replay allowed)
 *   "permanent"  — normal media, always accessible
 *
 * We hook the parser method after it runs and replace any non-permanent
 * view_mode value with "permanent" so Instagram renders the media normally.
 *
 * DexKit fingerprint: method using both "archived_media_timestamp" AND "view_mode"
 * with exactly 1 parameter (the JSON reader). This distinguishes it from the
 * companion serializer method in the same class which has 2 parameters.
 */
public class GhostPermanentViewHook {

    public static final String PERMANENT = "permanent";
    private static final String MEDIA = "com.instagram.feed.media.Media";
    static final ViewOnceStash STASH = new ViewOnceStash(ViewOnceStash.CAPACITY);

    public void install(DexKitBridge bridge, ClassLoader classLoader) {
        if (DexKitCache.isCacheValid()) {
            Method cached = DexKitCache.loadMethod("ViewOnceMedia", classLoader);
            if (cached != null) {
                XposedBridge.hookMethod(cached, buildHook());
                ModuleLog.ok("(IE|ViewOnceMedia) ready");
                FeatureStatusTracker.setHooked("PermanentViewMode");
                return;
            }
        }

        try {
            List<MethodData> methods = DexKitQueryCache.findMethod(bridge, FindMethod.create()
                    .matcher(MethodMatcher.create()
                            .usingStrings("archived_media_timestamp", "view_mode")
                            .paramCount(1)));

            if (methods.isEmpty()) {
                ModuleLog.error("(IE|ViewOnceMedia) Could not turn on permanent view: not supported by this Instagram version (view mode parser not found)");
                if (FeatureFlags.permanentViewMode) FeatureStatusTracker.setBroken("PermanentViewMode");
                return;
            }

            // Pick the method whose return type is not void (the parser returns the model object;
            // the serializer returns void). Fall back to the first candidate if none match.
            Method target = null;
            for (MethodData md : methods) {
                try {
                    Method m = md.getMethodInstance(classLoader);
                    if (m.getReturnType() != void.class) {
                        target = m;
                        break;
                    }
                } catch (Throwable ignored) {}
            }
            if (target == null) {
                // Fallback: just use the first found
                try {
                    target = methods.get(0).getMethodInstance(classLoader);
                } catch (Throwable t) {
                    ModuleLog.error("(IE|ViewOnceMedia) Could not turn on permanent view", t);
                    if (FeatureFlags.permanentViewMode) FeatureStatusTracker.setBroken("PermanentViewMode");
                    return;
                }
            }

            ModuleLog.detail("(IE|ViewOnceMedia) hooking "
                    + target.getDeclaringClass().getName() + "." + target.getName());

            DexKitCache.saveMethod("ViewOnceMedia", target);
            XposedBridge.hookMethod(target, buildHook());

            FeatureStatusTracker.setHooked("PermanentViewMode");
            ModuleLog.ok("(IE|ViewOnceMedia) ready");

        } catch (Throwable t) {
            ModuleLog.error("(IE|ViewOnceMedia) Could not turn on permanent view", t);
            if (FeatureFlags.permanentViewMode) FeatureStatusTracker.setBroken("PermanentViewMode");
        }
    }

    private static XC_MethodHook buildHook() {
        return new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam param) { ps.reso.instaeclipse.utils.core.JitGuard.tick(param.method);
                if (!FeatureFlags.permanentViewMode) return;
                Object result = param.getResult();
                if (result == null) return;

                int seenCount = -1;
                for (Class<?> cls = result.getClass(); cls != null && cls != Object.class; cls = cls.getSuperclass()) {
                    for (Field f : ps.reso.instaeclipse.utils.core.SafeReflect.fields(cls)) {
                        if (f.getType() != String.class) continue;
                        f.setAccessible(true);
                        try {
                            String original = ViewOnceStash.original((String) f.get(result));
                            if (original == null) continue;
                            if (seenCount < 0) seenCount = seenCount(result);
                            if (seenCount >= ("once".equals(original) ? 1 : 2)) return;
                            String mediaId = mediaId(result);
                            STASH.remember(mediaId, original);
                            ModuleLog.detail("(IE|ViewOnceMedia) mode=" + original + " media=" + mediaId);
                            f.set(result, PERMANENT);
                        } catch (Throwable ignored) {}
                    }
                }
            }
        };
    }

    private static int seenCount(Object result) {
        int seenCount = 0;
        for (Class<?> cls = result.getClass(); cls != null && cls != Object.class; cls = cls.getSuperclass()) {
            for (Field f : ps.reso.instaeclipse.utils.core.SafeReflect.fields(cls)) {
                if (f.getType() != int.class) continue;
                f.setAccessible(true);
                try { seenCount = f.getInt(result); } catch (Throwable ignored) {}
            }
        }
        return seenCount;
    }

    private static String mediaId(Object result) {
        for (Class<?> cls = result.getClass(); cls != null && cls != Object.class; cls = cls.getSuperclass()) {
            for (Field f : ps.reso.instaeclipse.utils.core.SafeReflect.fields(cls)) {
                if (Modifier.isStatic(f.getModifiers()) || !f.getType().getName().equals(MEDIA)) continue;
                try {
                    f.setAccessible(true);
                    Object media = f.get(result);
                    if (media == null) continue;
                    if (media.getClass().getMethod("getId").invoke(media) instanceof String id) return id;
                } catch (Throwable ignored) {}
            }
        }
        return null;
    }
}
