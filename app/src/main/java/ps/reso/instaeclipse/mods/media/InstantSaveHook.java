package ps.reso.instaeclipse.mods.media;

import ps.reso.instaeclipse.xposedcompat.AndroidAppHelper;
import android.content.Context;
import android.widget.Toast;

import java.lang.reflect.Proxy;

import ps.reso.instaeclipse.xposedcompat.XC_MethodHook;
import ps.reso.instaeclipse.xposedcompat.XposedBridge;
import ps.reso.instaeclipse.xposedcompat.XposedHelpers;
import ps.reso.instaeclipse.R;
import ps.reso.instaeclipse.mods.ui.UIHookManager;
import ps.reso.instaeclipse.utils.feature.FeatureFlags;
import ps.reso.instaeclipse.utils.feature.FeatureStatusTracker;
import ps.reso.instaeclipse.utils.i18n.I18n;
import ps.reso.instaeclipse.utils.log.ModuleLog;
import ps.reso.instaeclipse.utils.core.DexKitQueryCache;
import ps.reso.instaeclipse.utils.core.DexKitQueryCache.FoundClass;

/**
 * Save a received Instant (#184). "Instants" is internally `quicksnap`, and its viewer is Jetpack
 * Compose (so no button can be injected). An Instant item is the model whose Media lives in field
 * A01; the module's existing downloader understands that Media directly.
 *
 * Two literal-named Compose classes (stable across obfuscation — Kotlin package names survive):
 *  - QuickSnapMediaViewerScreenKt$QuickSnapMediaViewerScreen$16$1$8$1 — the double-tap coroutine
 *    for the CURRENT card; its constructor receives the current Instant item as arg[6]. We hook
 *    the ctor (after) and cache that item's Media.
 *  - PointerTouchKt$handleTapAndLongPressWithRelease$2$1$1$1 — the viewer's tap/long-press pointer
 *    handler (package com.instagram.quicksnap.viewer.compose, so it only fires inside the Instant
 *    viewer). Its constructor stores the onLongPress callback (a kotlin Function1) in a field.
 *    We wrap that Function1 so a long-press saves the cached Instant, then still runs IG's original.
 *
 * No obfuscated class OR field name is hardcoded: the two hooked classes are literal-named, the
 * Media is found by field type and the long-press callback by constructor-argument order.
 */
public class InstantSaveHook {

    // Compose renumbers this lambda between builds ($16$1$8$1 on 447, $16$1$11$1 on 451). Known
    // names first (newest first), then a DexKit search by the ctor's stable non-obfuscated types.
    private static final String VIEWER_PREFIX =
            "com.instagram.quicksnap.viewer.compose.QuickSnapMediaViewerScreenKt$QuickSnapMediaViewerScreen$";
    private static final String[] VIEWER_LAMBDAS = {
            VIEWER_PREFIX + "16$1$11$1",
            VIEWER_PREFIX + "16$1$8$1",
    };
    private static final String LONGPRESS_LAMBDA =
            "com.instagram.quicksnap.viewer.compose.PointerTouchKt$handleTapAndLongPressWithRelease$2$1$1$1";

    // The current on-screen Instant's Media (com.instagram.feed.media.Media), cached at bind time.
    private static volatile Object currentMedia;

    public void install(ClassLoader cl) {
        boolean viewer = cacheCurrentInstant(cl);
        boolean longPress = wrapLongPress(cl);
        if (viewer && longPress) {
            FeatureStatusTracker.setHooked("SaveInstants");
            ModuleLog.ok("(IE|InstantSave) ready");
        } else if (FeatureFlags.saveInstants) {
            FeatureStatusTracker.setBroken("SaveInstants");
        }
    }

    /** Hook the current-card coroutine ctor and cache arg[6]'s Media (field A01). */
    private boolean cacheCurrentInstant(ClassLoader cl) {
        try {
            Class<?> lambda = resolveViewerLambda(cl);
            if (lambda == null) {
                ModuleLog.error("(IE|InstantSave) Could not turn on saving Instants: the Instant viewer was not found in this Instagram version");
                return false;
            }
            int hooked = 0;
            for (java.lang.reflect.Constructor<?> c : ps.reso.instaeclipse.utils.core.SafeReflect.constructors(lambda)) {
                XposedBridge.hookMethod(c, new XC_MethodHook() {
                    @Override protected void afterHookedMethod(MethodHookParam p) { ps.reso.instaeclipse.utils.core.JitGuard.tick(p.method);
                        try {
                            Object media = findInstantMedia(p.args);
                            if (media != null) currentMedia = media;
                        } catch (Throwable ignored) {}
                    }
                });
                hooked++;
            }
            if (hooked == 0) {
                ModuleLog.error("(IE|InstantSave) Could not turn on saving Instants: the Instant viewer has no constructor to watch");
                return false;
            }
            ModuleLog.detail("(IE|InstantSave) viewer cache hooked: " + lambda.getName());
            return true;
        } catch (Throwable t) {
            ModuleLog.error("(IE|InstantSave) Could not turn on saving Instants: " + t.getClass().getSimpleName() + ": " + t.getMessage());
            return false;
        }
    }

    private static Class<?> resolveViewerLambda(ClassLoader cl) {
        for (String name : VIEWER_LAMBDAS) {
            try {
                Class<?> c = Class.forName(name, false, cl);
                if (isViewerLambda(c)) return c;
            } catch (Throwable ignored) {}
        }
        try {
            org.luckypray.dexkit.DexKitBridge bridge = ps.reso.instaeclipse.utils.core.ModuleBridge.get();
            if (bridge == null) return null;
            for (FoundClass cd : DexKitQueryCache.classes(
                    org.luckypray.dexkit.query.FindClass.create().matcher(
                            org.luckypray.dexkit.query.matchers.ClassMatcher.create().className(
                                    VIEWER_PREFIX, org.luckypray.dexkit.query.enums.StringMatchType.StartsWith)))) {
                if (cd.getName().contains("cardCenter")) continue;
                try {
                    Class<?> c = cd.getInstance(cl);
                    if (isViewerLambda(c)) return c;
                } catch (Throwable ignored) {}
            }
        } catch (Throwable t) {
            ModuleLog.detail("(IE|InstantSave) viewer lambda search: " + t.getMessage());
        }
        return null;
    }

    /** The current-card coroutine: a ctor taking UserSession + AnimatedParticleEffectState (447/451). */
    private static boolean isViewerLambda(Class<?> c) {
        for (java.lang.reflect.Constructor<?> k : ps.reso.instaeclipse.utils.core.SafeReflect.constructors(c)) {
            boolean session = false, particles = false;
            for (Class<?> t : k.getParameterTypes()) {
                String n = t.getName();
                if (n.equals("com.instagram.common.session.UserSession")) session = true;
                if (n.endsWith(".AnimatedParticleEffectState")) particles = true;
            }
            if (session && particles) return true;
        }
        return false;
    }

    /**
     * Finds the current Instant's Media among the ctor args by FIELD TYPE, not by the obfuscated
     * field letter — the arg is the Instant item, and its Media lives in the (only) field whose
     * type is com.instagram.feed.media.Media. This survives field-letter/arg-order drift across
     * versions (nothing here is hardcoded to "A01"/index 6).
     */
    private static Object findInstantMedia(Object[] args) {
        if (args == null) return null;
        for (Object a : args) {
            if (a == null) continue;
            String cn = a.getClass().getName();
            if (cn.startsWith("java.") || cn.startsWith("android") || cn.startsWith("kotlin")) continue;
            for (java.lang.reflect.Field f : ps.reso.instaeclipse.utils.core.SafeReflect.fields(a.getClass())) {
                if (!"com.instagram.feed.media.Media".equals(f.getType().getName())) continue;
                try {
                    f.setAccessible(true);
                    Object media = f.get(a);
                    if (media != null) return media;
                } catch (Throwable ignored) {}
            }
        }
        return null;
    }

    private static final InstantSavePolicy POLICY = new InstantSavePolicy();

    /**
     * Hook the viewer pointer handler and wrap its onLongPress callback (A0A on 452 — verified
     * on-device: it fires exactly once per gesture at the long-press threshold, while the other
     * Function1 is the ordinary press/tap callback that fires repeatedly). On long-press we save the
     * cached Instant, then still run IG's original callback so its own behaviour (pause, etc.) is
     * preserved. The field is picked by {@link #longPressField}, not by its obfuscated letter.
     */
    private boolean wrapLongPress(ClassLoader cl) {
        try {
            final Class<?> function1 = XposedHelpers.findClass("kotlin.jvm.functions.Function1", cl);
            Class<?> handler = XposedHelpers.findClass(LONGPRESS_LAMBDA, cl);
            int hooked = 0;
            for (java.lang.reflect.Constructor<?> c : ps.reso.instaeclipse.utils.core.SafeReflect.constructors(handler)) {
                XposedBridge.hookMethod(c, new XC_MethodHook() {
                    @Override protected void afterHookedMethod(MethodHookParam p) { ps.reso.instaeclipse.utils.core.JitGuard.tick(p.method);
                        try {
                            java.lang.reflect.Field field = longPressField(p, function1);
                            if (field == null) return;
                            final Object orig = field.get(p.thisObject);
                            if (orig == null || !function1.isInstance(orig)) return;
                            if (orig.getClass().getName().startsWith("$Proxy")) return; // already wrapped
                            Object wrapper = Proxy.newProxyInstance(cl, new Class[]{function1}, (proxy, method, a) -> {
                                if ("invoke".equals(method.getName()) && FeatureFlags.saveInstants) saveCurrent();
                                return orig != null ? method.invoke(orig, a) : null;
                            });
                            field.set(p.thisObject, wrapper);
                        } catch (Throwable ignored) {}
                    }
                });
                hooked++;
            }
            if (hooked == 0) {
                ModuleLog.error("(IE|InstantSave) Could not turn on holding an Instant to save it: the hold gesture has no constructor to watch");
                return false;
            }
            return true;
        } catch (Throwable t) {
            ModuleLog.error("(IE|InstantSave) Could not turn on holding an Instant to save it: " + t.getClass().getSimpleName() + ": " + t.getMessage());
            return false;
        }
    }

    private static volatile java.lang.reflect.Field longPressField;
    private static volatile boolean longPressMissLogged;

    /**
     * The pointer lambda captures (continuation, onPress: Function0, onTap: Function1,
     * onLongPress: Function1, slop: Float, timeout: Long) — Kotlin keeps capture order in the ctor
     * (452: p3 -> A0B tap, p4 -> A0A long-press). So the long-press callback is the SECOND
     * Function1 ctor argument; the field is the Function1 field holding that same instance.
     * Resolved on the first construction and reused (field letters are fixed for one build).
     */
    private static java.lang.reflect.Field longPressField(XC_MethodHook.MethodHookParam p, Class<?> function1) {
        java.lang.reflect.Field cached = longPressField;
        if (cached != null) return cached;
        Object longPress = null;
        int seen = 0;
        for (Object a : p.args) {
            if (a != null && function1.isInstance(a) && ++seen == 2) { longPress = a; break; }
        }
        if (longPress != null) {
            for (java.lang.reflect.Field f : ps.reso.instaeclipse.utils.core.SafeReflect.fields(p.thisObject.getClass())) {
                if (f.getType() != function1) continue;
                try {
                    f.setAccessible(true);
                    if (f.get(p.thisObject) == longPress) {
                        ModuleLog.detail("(IE|InstantSave) long-press field = " + f.getName());
                        return longPressField = f;
                    }
                } catch (Throwable ignored) {}
            }
        }
        if (!longPressMissLogged) {
            longPressMissLogged = true;
            if (FeatureFlags.saveInstants)
                ModuleLog.error("(IE|InstantSave) Holding an Instant will not save it: the hold gesture was not found in this Instagram version");
            ModuleLog.detail("(IE|InstantSave) long-press field not found (Function1 args: " + seen + ")");
        }
        return null;
    }

    private static void saveCurrent() {
        final Object media = currentMedia;
        final Context app = AndroidAppHelper.currentApplication();
        if (app == null) return;
        final String id = media == null ? null : mediaId(media);
        final InstantSavePolicy.Decision decision = POLICY.hold(id, android.os.SystemClock.uptimeMillis());
        if (decision == InstantSavePolicy.Decision.IGNORE) return;
        FeedVideoDownloadHook.mainHandler.post(() -> {
            Context ui = UIHookManager.getCurrentActivity();
            if (ui == null) ui = app;
            if (media == null) { toast(ui, R.string.ig_instant_save_none); return; }
            if (decision == InstantSavePolicy.Decision.ALREADY_SAVED) { toast(ui, R.string.ig_instant_already_saved); return; }
            try {
                SelectedMediaDownloads.Captured captured = SelectedMediaDownloads.capture(ui, media, "instant");
                if (captured.children.isEmpty()) start(ui, captured);
                else for (SelectedMediaDownloads.Captured child : captured.children) start(ui, child);
                POLICY.saved(id, android.os.SystemClock.uptimeMillis());
            } catch (Throwable error) {
                SelectedMediaDownloads.unavailable(ui, error);
            }
        });
    }

    private static void start(Context ctx, SelectedMediaDownloads.Captured captured) {
        SelectedMediaDownloads.start(ctx, captured, captured.batchKind() == DownloadChoices.Choice.VIDEO);
    }

    private static String mediaId(Object media) {
        try {
            Object id = media.getClass().getMethod("getId").invoke(media);
            return id instanceof String s && !s.isEmpty() ? s : null;
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static void toast(Context ctx, int text) {
        ps.reso.instaeclipse.utils.toast.IeToast.makeText(ctx, I18n.t(ctx, text), Toast.LENGTH_SHORT).show();
    }
}
