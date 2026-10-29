package ps.reso.instaeclipse.mods.media;

import android.app.Activity;
import android.app.Application;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.os.SystemClock;
import android.widget.Toast;

import java.lang.reflect.Field;
import java.util.Map;

import ps.reso.instaeclipse.xposedcompat.XC_MethodHook;
import ps.reso.instaeclipse.xposedcompat.XposedBridge;
import ps.reso.instaeclipse.xposedcompat.XposedHelpers;
import ps.reso.instaeclipse.R;
import ps.reso.instaeclipse.share.ShareLinks;
import ps.reso.instaeclipse.utils.i18n.I18n;
import ps.reso.instaeclipse.utils.log.ModuleLog;
import ps.reso.instaeclipse.utils.text.MediaIds;

public class ShareLinkDownloadHook {
    public static final String EXTRA = "ps.reso.instaeclipse.SHARE_DOWNLOAD";
    private static final String TAG = "(IE|ShareDL) ";
    private static final long TIMEOUT_MS = 25_000, STEP_MS = 500;

    private static final java.util.Set<Object> sessions =
            java.util.Collections.synchronizedSet(java.util.Collections.newSetFromMap(new java.util.WeakHashMap<>()));
    private static String pendingPk;

    public void install(ClassLoader cl) {
        try {
            XposedBridge.hookAllConstructors(XposedHelpers.findClass("com.instagram.common.session.UserSession", cl), new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    sessions.add(param.thisObject);
                }
            });
        } catch (Throwable t) {
            ModuleLog.error(TAG + "Downloading shared links will not work: the account session could not be tracked: " + t);
        }
        XC_MethodHook onIntent = new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam param) {
                Activity a = (Activity) param.thisObject;
                Intent i = param.args.length > 0 && param.args[0] instanceof Intent in ? in : a.getIntent();
                if (i == null || !i.getBooleanExtra(EXTRA, false)) return;
                i.removeExtra(EXTRA);
                start(a, i.getData());
            }
        };
        try {
            XposedHelpers.findAndHookMethod(Activity.class, "onCreate", Bundle.class, onIntent);
            XposedHelpers.findAndHookMethod(Activity.class, "onNewIntent", Intent.class, onIntent);
            ModuleLog.ok(TAG + "ready");
        } catch (Throwable t) {
            ModuleLog.error(TAG + "Downloading shared links will not work: " + t);
        }
    }

    private static void start(Activity a, Uri uri) {
        String pk = uri != null ? pkFromLink(uri.toString()) : null;
        ModuleLog.detail(TAG + "link=" + uri + " pk=" + pk);
        if (pk == null) {
            toast(a, I18n.t(a, R.string.ig_share_dl_not_found));
            return;
        }
        pendingPk = pk;
        toast(a, I18n.t(a, R.string.ig_share_dl_loading));
        final long end = System.currentTimeMillis() + TIMEOUT_MS;
        final Context app = a.getApplicationContext();
        final Application application = a.getApplication();
        Runnable poll = new Runnable() {
            @Override public void run() {
                if (!pk.equals(pendingPk)) return;
                Object media = findMedia(pk);
                if (media != null) {
                    pendingPk = null;
                    save(app, media, pk);
                    moveBack(application, new ShareMoveBackPolicy());
                } else if (System.currentTimeMillis() < end) {
                    FeedVideoDownloadHook.mainHandler.postDelayed(this, STEP_MS);
                } else {
                    pendingPk = null;
                    ModuleLog.error(TAG + "Could not download the shared link: the post did not load in time");
                    ModuleLog.detail(TAG + "timed out pk=" + pk);
                    toast(app, I18n.t(app, R.string.ig_share_dl_not_found));
                }
            }
        };
        FeedVideoDownloadHook.mainHandler.postDelayed(poll, STEP_MS);
    }

    private static void moveBack(Application application, ShareMoveBackPolicy policy) {
        Application.ActivityLifecycleCallbacks watch = new Application.ActivityLifecycleCallbacks() {
            @Override public void onActivityResumed(Activity activity) { policy.resumed(SystemClock.uptimeMillis()); }
            @Override public void onActivityCreated(Activity activity, Bundle state) { }
            @Override public void onActivityStarted(Activity activity) { }
            @Override public void onActivityPaused(Activity activity) { }
            @Override public void onActivityStopped(Activity activity) { }
            @Override public void onActivitySaveInstanceState(Activity activity, Bundle state) { }
            @Override public void onActivityDestroyed(Activity activity) { }
        };
        boolean watching = false;
        try {
            if (application != null) {
                application.registerActivityLifecycleCallbacks(watch);
                watching = true;
            }
        } catch (Throwable t) {
            ModuleLog.detail(TAG + "resume watch: " + t);
        }
        final boolean watched = watching;
        final int last = ShareMoveBackPolicy.CHECKS_MS.length - 1;
        for (int i = 0; i <= last; i++) {
            final int check = i;
            FeedVideoDownloadHook.mainHandler.postDelayed(() -> {
                try {
                    if (!policy.shouldMove(check, SystemClock.uptimeMillis())) return;
                    Activity front = resumedActivity();
                    if (front != null) front.moveTaskToBack(true);
                    policy.moved(SystemClock.uptimeMillis());
                } finally {
                    if (check == last && watched) application.unregisterActivityLifecycleCallbacks(watch);
                }
            }, ShareMoveBackPolicy.CHECKS_MS[i]);
        }
    }

    private static Activity resumedActivity() {
        try {
            Object at = XposedHelpers.callStaticMethod(Class.forName("android.app.ActivityThread"), "currentActivityThread");
            Map<?, ?> records = (Map<?, ?>) XposedHelpers.getObjectField(at, "mActivities");
            for (Object r : records.values()) {
                if (XposedHelpers.getBooleanField(r, "paused")) continue;
                Object act = XposedHelpers.getObjectField(r, "activity");
                if (act instanceof Activity a && !a.isFinishing()) return a;
            }
        } catch (Throwable t) {
            ModuleLog.detail(TAG + "resumed activity: " + t);
        }
        return null;
    }

    static String pkFromLink(String link) {
        String shortcode = ShareLinks.shortcode(link);
        return shortcode == null ? null : MediaIds.pkFromShortcode(shortcode);
    }

    private static Object findMedia(String pk) {
        Object[] all;
        synchronized (sessions) { all = sessions.toArray(); }
        for (Object s : all) {
            try {
                Class<?> cache = s.getClass().getClassLoader().loadClass("com.instagram.feed.media.MediaCache");
                Object mc = cache.getMethod("getInstance", s.getClass()).invoke(null, s);
                for (Field f : ps.reso.instaeclipse.utils.core.SafeReflect.fields(cache)) {
                    if (!Map.class.isAssignableFrom(f.getType())) continue;
                    f.setAccessible(true);
                    Map<?, ?> map = (Map<?, ?>) f.get(mc);
                    if (map == null) continue;
                    for (Map.Entry<?, ?> e : map.entrySet()) {
                        String k = String.valueOf(e.getKey());
                        if (k.equals(pk) || k.startsWith(pk + "_")) return e.getValue();
                    }
                }
            } catch (Throwable t) {
                ModuleLog.detail(TAG + "cache: " + t);
            }
        }
        return null;
    }

    private static void save(Context ctx, Object media, String pk) {
        try {
            SelectedMediaDownloads.Captured captured = SelectedMediaDownloads.capture(ctx, media, "post");
            ModuleLog.detail(TAG + "pk=" + pk + " items=" + Math.max(1, captured.children.size()));
            if (captured.children.isEmpty())
                SelectedMediaDownloads.start(ctx, captured, captured.batchKind() == DownloadChoices.Choice.VIDEO);
            else SelectedMediaDownloads.startAll(ctx, captured);
        } catch (Throwable error) {
            SelectedMediaDownloads.unavailable(ctx, error);
        }
    }

    private static void toast(Context c, String msg) {
        FeedVideoDownloadHook.mainHandler.post(() -> ps.reso.instaeclipse.utils.toast.IeToast.makeText(c, msg, Toast.LENGTH_SHORT).show());
    }
}
