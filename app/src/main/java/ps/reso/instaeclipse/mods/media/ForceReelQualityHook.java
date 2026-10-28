package ps.reso.instaeclipse.mods.media;

import org.luckypray.dexkit.query.FindMethod;
import org.luckypray.dexkit.query.matchers.MethodMatcher;

import java.lang.reflect.Method;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentHashMap;

import ps.reso.instaeclipse.xposedcompat.XC_MethodHook;
import ps.reso.instaeclipse.xposedcompat.XposedBridge;
import ps.reso.instaeclipse.utils.core.DexKitCache;
import ps.reso.instaeclipse.utils.feature.FeatureFlags;
import ps.reso.instaeclipse.utils.feature.FeatureStatusTracker;
import ps.reso.instaeclipse.utils.log.ModuleLog;
import ps.reso.instaeclipse.utils.core.DexKitQueryCache;
import ps.reso.instaeclipse.utils.core.DexKitQueryCache.Found;

/**
 * Forces reel/video playback to a specific quality by hooking LiveTreeMediaDict's
 * video_versions getter and collapsing the list of available qualities down to whichever
 * one best matches the desired height — Instagram's player has no other choice left once
 * only one option remains.
 *
 * An earlier attempt at this feature rewrote raw Tigon network response bytes mid-stream,
 * but never actually fed the rewritten bytes back into what Instagram parses (the mutated
 * JSON was built and then only logged, never applied) — so it silently did nothing. This
 * version instead manipulates Instagram's own already-parsed model object, the same
 * approach HideSuggestedFeedItemsHook already uses successfully elsewhere in this codebase.
 *
 * The per-item height field is found without depending on any obfuscated name at all: the
 * concrete video-version class's height accessor calls Pando's getOptionalIntValueByHashCode
 * with a hardcoded hash constant that is simply Java's standard String.hashCode() of the
 * literal string "height" — a value fixed by the JDK spec, not by Instagram's build, so it's
 * computed locally and matched via DexKit rather than guessed from a stale reference.
 */
public class ForceReelQualityHook {

    private static final String[] DICT_CLASS_CANDIDATES = {
            "com.instagram.feed.media.Media",             // IG 442+ (447)
    };
    private static final String[] VIDEO_VERSION_CLASS_CANDIDATES = {
            "com.instagram.api.schemas.ImmutablePandoVideoVersion",      // IG 442+ (447)
    };
    // The height getter embeds getOptional...ByHashCode / A2E(hash, idx) where hash is the
    // JDK-fixed String.hashCode("height") (== -0x48c76ed9). Identical const in 436 and 447,
    // so it is matched via DexKit rather than by the per-version obfuscated method name or
    // the volatile Pando index.
    private static final int HEIGHT_HASH = "height".hashCode();

    private static final String VIDEO_VERSION_INTF = "com.instagram.api.schemas.VideoVersionIntf";
    private static final Object NO_HEIGHT = new Object();
    private static final Map<Class<?>, Object> HEIGHT_GETTERS = new ConcurrentHashMap<>();

    private static final String CACHE_GETTER_KEY = "ForceReelQuality_VideoVersionsGetter";
    private static final String CACHE_HEIGHT_KEY = "ForceReelQuality_HeightGetterName";

    /** Set while a download reads the qualities: it gets the full list, not the forced one. Use {@link #raw}. */
    static final ThreadLocal<Boolean> RAW = new ThreadLocal<>();
    private static volatile String heightGetter;
    private static volatile Method sharedHeightGetter;

    /**
     * Runs {@code read} with RAW set, then restores the caller's value instead of clearing it:
     * reads nest (the media snapshot's originalVideo reads video_versions inside the snapshot's
     * own scope), and a cleared flag hands the outer read the forced single rendition.
     */
    static <T> T raw(Callable<T> read) throws Exception {
        Boolean previous = RAW.get();
        RAW.set(Boolean.TRUE);
        try { return read.call(); }
        finally { if (previous == null) RAW.remove(); else RAW.set(previous); }
    }

    /** A video version's height in pixels, or -1 when unknown. */
    static int heightOf(Object item) {
        Method getter = item == null ? null : heightMethod(item.getClass());
        if (getter == null) return -1;
        try {
            Object h = getter.invoke(item);
            return h instanceof Integer i ? i : -1;
        } catch (Throwable t) {
            return -1;
        }
    }

    static Method heightMethod(Class<?> type) {
        Method shared = sharedHeightGetter;
        if (shared != null && shared.getDeclaringClass().isAssignableFrom(type)) return shared;
        String name = heightGetter;
        if (name == null) return null;
        Object cached = HEIGHT_GETTERS.get(type);
        if (cached == null) {
            try {
                cached = type.getMethod(name);
            } catch (Throwable t) {
                cached = NO_HEIGHT;
            }
            HEIGHT_GETTERS.put(type, cached);
        }
        return cached instanceof Method method ? method : null;
    }

    static void useHeightGetter(String name, Method shared) {
        HEIGHT_GETTERS.clear();
        sharedHeightGetter = shared;
        heightGetter = name;
    }

    private static Method interfaceHeightGetter(ClassLoader classLoader, String name) {
        try {
            return classLoader.loadClass(VIDEO_VERSION_INTF).getMethod(name);
        } catch (Throwable t) {
            return null;
        }
    }

    public void install(ClassLoader classLoader) {
        try {
            Method videoVersionsGetter;
            String heightGetterName;

            if (DexKitCache.isCacheValid()) {
                videoVersionsGetter = DexKitCache.loadMethod(CACHE_GETTER_KEY, classLoader);
                heightGetterName = DexKitCache.loadString(CACHE_HEIGHT_KEY);
            } else {
                videoVersionsGetter = null;
                heightGetterName = null;
            }

            if (videoVersionsGetter == null || heightGetterName == null) {
                videoVersionsGetter = resolveVideoVersionsGetter(classLoader);
                heightGetterName = resolveHeightGetterName(classLoader);

                if (videoVersionsGetter == null || heightGetterName == null) {
                    ModuleLog.error("(InstaEclipse | ForceReelQuality): Could not turn on reel quality: the video quality list was not found in this Instagram version");
                    ModuleLog.detail("(InstaEclipse | ForceReelQuality): discovery failed getter=" + videoVersionsGetter + " height=" + heightGetterName);
                    return;
                }

                DexKitCache.saveMethod(CACHE_GETTER_KEY, videoVersionsGetter);
                DexKitCache.saveString(CACHE_HEIGHT_KEY, heightGetterName);
            }

            videoVersionsGetter.setAccessible(true);
            useHeightGetter(heightGetterName, interfaceHeightGetter(classLoader, heightGetterName));
            if (FeatureFlags.forceReelQuality <= 0) return;

            XposedBridge.hookMethod(videoVersionsGetter, new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) { ps.reso.instaeclipse.utils.core.JitGuard.tick(param.method);
                    if (FeatureFlags.forceReelQuality <= 0 || RAW.get() != null) return;
                    try {
                        Object result = param.getResult();
                        if (!(result instanceof List)) return;
                        List<?> versions = (List<?>) result;
                        if (versions.size() <= 1) return;

                        Object chosen = pickBestQuality(versions, FeatureFlags.forceReelQuality);
                        if (chosen != null) {
                            param.setResult(Collections.singletonList(chosen));
                        }
                    } catch (Throwable t) {
                        ModuleLog.warn("(InstaEclipse | ForceReelQuality): Could not pick the quality for one reel: " + t);
                    }
                }
            });

            // Mark hooked as soon as the hook is successfully attached, not only once a reel
            // with multiple qualities is actually intercepted — the status toast is built
            // ~1.5s after launch, before any video is guaranteed to have loaded yet.
            FeatureStatusTracker.setHooked("ForceReelQuality");
            ModuleLog.ok("(InstaEclipse | ForceReelQuality): ready");
            ModuleLog.detail("(InstaEclipse | ForceReelQuality): Hooked "
                    + videoVersionsGetter.getDeclaringClass().getName()
                    + "#" + videoVersionsGetter.getName()
                    + " (height=" + heightGetterName + ")");
        } catch (Throwable t) {
            ModuleLog.error("(InstaEclipse | ForceReelQuality): Could not turn on reel quality", t);
        }
    }

    private static Method resolveVideoVersionsGetter(ClassLoader classLoader) {
        for (String dictClass : DICT_CLASS_CANDIDATES) {
            try {
                List<Found> results = DexKitQueryCache.methods(FindMethod.create()
                        .matcher(MethodMatcher.create()
                                .declaredClass(dictClass)
                                .paramCount(0)
                                .usingEqStrings(List.of("video_versions"))));

                for (Found md : results) {
                    try {
                        Method m = md.getMethodInstance(classLoader);
                        if (m.getReturnType() != List.class) continue;
                        return m;
                    } catch (Throwable ignored) {}
                }
            } catch (Throwable t) {
                ModuleLog.detail("(InstaEclipse | ForceReelQuality): resolveVideoVersionsGetter[" + dictClass + "] – " + t);
            }
        }
        return null;
    }

    private static String resolveHeightGetterName(ClassLoader classLoader) {
        for (String versionClass : VIDEO_VERSION_CLASS_CANDIDATES) {
            try {
                List<Found> results = DexKitQueryCache.methods(FindMethod.create()
                        .matcher(MethodMatcher.create()
                                .declaredClass(versionClass)
                                .paramCount(0)
                                .returnType("java.lang.Integer")
                                .usingNumbers(List.of(HEIGHT_HASH))));

                if (!results.isEmpty()) return results.get(0).getName();
            } catch (Throwable t) {
                ModuleLog.detail("(InstaEclipse | ForceReelQuality): resolveHeightGetterName[" + versionClass + "] – " + t);
            }
        }
        return null;
    }

    static Object pickBestQuality(List<?> items, int desired) {
        Object best = null;
        int bestDelta = Integer.MAX_VALUE;
        int bestHeight = -1;
        for (Object item : items) {
            int h = heightOf(item);
            if (h <= 0) continue;
            if (desired == Integer.MAX_VALUE) {
                if (h > bestHeight) { bestHeight = h; best = item; }
            } else {
                int delta = Math.abs(h - desired);
                if (delta < bestDelta) { bestDelta = delta; best = item; }
            }
        }
        return best;
    }
}
