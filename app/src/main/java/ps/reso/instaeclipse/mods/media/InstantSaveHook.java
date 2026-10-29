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

public class InstantSaveHook {

    private static final String VIEWER_PREFIX =
            "com.instagram.quicksnap.viewer.compose.QuickSnapMediaViewerScreenKt$QuickSnapMediaViewerScreen$";
    private static final String[] VIEWER_LAMBDAS = {
            VIEWER_PREFIX + "16$1$11$1",
    };
    private static final String LONGPRESS_LAMBDA =
            "com.instagram.quicksnap.viewer.compose.PointerTouchKt$handleTapAndLongPressWithRelease$2$1$1$1";

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
                    @Override protected void afterHookedMethod(MethodHookParam p) {
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

    private boolean wrapLongPress(ClassLoader cl) {
        try {
            final Class<?> function1 = XposedHelpers.findClass("kotlin.jvm.functions.Function1", cl);
            Class<?> handler = XposedHelpers.findClass(LONGPRESS_LAMBDA, cl);
            int hooked = 0;
            for (java.lang.reflect.Constructor<?> c : ps.reso.instaeclipse.utils.core.SafeReflect.constructors(handler)) {
                XposedBridge.hookMethod(c, new XC_MethodHook() {
                    @Override protected void afterHookedMethod(MethodHookParam p) {
                        try {
                            java.lang.reflect.Field field = longPressField(p, function1);
                            if (field == null) return;
                            final Object orig = field.get(p.thisObject);
                            if (orig == null || !function1.isInstance(orig)) return;
                            if (orig.getClass().getName().startsWith("$Proxy")) return;
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
