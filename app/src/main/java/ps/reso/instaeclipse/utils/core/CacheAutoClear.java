package ps.reso.instaeclipse.utils.core;

import android.app.Activity;
import android.app.Application;
import android.content.Context;
import android.os.Bundle;

import java.io.File;
import java.util.List;

import ps.reso.instaeclipse.utils.feature.FeatureFlags;
import ps.reso.instaeclipse.utils.log.ModuleLog;

public class CacheAutoClear {

    private static final long MB = 1024 * 1024;

    private static volatile boolean installed = false;
    private static final CacheSweep.ForegroundCount foreground = new CacheSweep.ForegroundCount();
    private static long lastClear = 0;

    public static void register(Application app) {
        if (installed || app == null) return;
        installed = true;
        ps.reso.instaeclipse.utils.feature.FeatureStatusTracker.setHooked("AutoClearCache");
        app.registerActivityLifecycleCallbacks(new Application.ActivityLifecycleCallbacks() {
            @Override public void onActivityStarted(Activity a) { foreground.started(); }
            @Override public void onActivityStopped(Activity a) {
                if (foreground.stopped(a.isChangingConfigurations())) onAppBackgrounded(app);
            }
            @Override public void onActivityCreated(Activity a, Bundle b) {}
            @Override public void onActivityResumed(Activity a) {}
            @Override public void onActivityPaused(Activity a) {}
            @Override public void onActivitySaveInstanceState(Activity a, Bundle b) {}
            @Override public void onActivityDestroyed(Activity a) {}
        });
        ModuleLog.detail("(IE|CacheAutoClear) background listener installed");
    }

    private static void onAppBackgrounded(Context app) {
        if (!FeatureFlags.autoClearCache) return;
        long now = System.currentTimeMillis();
        if (now - lastClear < 30_000) return;
        lastClear = now;
        int limitMb = FeatureFlags.autoClearCacheSizeMb > 0 ? FeatureFlags.autoClearCacheSizeMb : 100;
        sweep(app, limitMb * MB);
    }

    public static void clearNow(Context app, Runnable onDone) {
        new Thread(() -> {
            try {
                List<File> entries = CacheSweep.select(app.getCacheDir(), app.getExternalCacheDir(), app.getFilesDir());
                long measured = CacheSweep.size(entries);
                CacheSweep.delete(entries);
                ModuleLog.line("(IE|CacheAutoClear) Cleared about " + (measured / MB) + " MB of cache");
            } catch (Throwable t) {
                ModuleLog.warn("(IE|CacheAutoClear) Could not clear the cache: " + t.getClass().getSimpleName() + ": " + t.getMessage());
            } finally {
                if (onDone != null) onDone.run();
            }
        }, "ie-cacheclear").start();
    }

    static Thread sweep(Context app, long limit) {
        Thread worker = new Thread(() -> {
            try {
                List<File> entries = CacheSweep.select(app.getCacheDir(), app.getExternalCacheDir(), app.getFilesDir());
                long measured = CacheSweep.size(entries);

                ModuleLog.detail("(IE|CacheAutoClear) backgrounded — cache=" + (measured / MB)
                        + "MB (limit " + (limit / MB) + "MB)");

                if (measured >= limit) {
                    CacheSweep.delete(entries);
                    ModuleLog.line("(IE|CacheAutoClear) Cleared about " + (measured / MB) + " MB of cache");
                }
            } catch (Throwable t) {
                ModuleLog.warn("(IE|CacheAutoClear) Could not clear the cache this time: " + t.getClass().getSimpleName() + ": " + t.getMessage());
            }
        }, "ie-cacheclear");
        worker.start();
        return worker;
    }
}
