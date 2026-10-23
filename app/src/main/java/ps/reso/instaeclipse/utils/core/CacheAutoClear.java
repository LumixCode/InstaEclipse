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

    private static volatile boolean installed = false;
    private static final CacheSweep.ForegroundCount foreground = new CacheSweep.ForegroundCount();
    private static long lastClear = 0;

    public static void maybeClear(Context ctx) {
        if (installed || ctx == null) return;
        Context appCtx = ctx.getApplicationContext();
        if (!(appCtx instanceof Application)) return;
        installed = true;
        ps.reso.instaeclipse.utils.feature.FeatureStatusTracker.setHooked("AutoClearCache");
        final Application app = (Application) appCtx;
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
        new Thread(() -> {
            try {
                int limitMb = FeatureFlags.autoClearCacheSizeMb > 0 ? FeatureFlags.autoClearCacheSizeMb : 100;
                long limit = (long) limitMb * 1024 * 1024;

                List<File> entries = CacheSweep.select(app.getCacheDir(), app.getExternalCacheDir(), app.getFilesDir());
                try { entries.addAll(CacheSweep.children(app.getCodeCacheDir())); } catch (Throwable ignored) {}
                long osCache = osCacheBytes(app);
                long measured = osCache > 0 ? osCache : CacheSweep.size(entries);

                ModuleLog.detail("(IE|CacheAutoClear) backgrounded — cache=" + (measured / (1024 * 1024))
                        + "MB (limit " + limitMb + "MB)");

                if (measured >= limit) {
                    long freed = CacheSweep.size(entries);
                    CacheSweep.delete(entries);
                    ModuleLog.line("(IE|CacheAutoClear) Cleared about " + (freed / (1024 * 1024)) + " MB of cache");
                }
            } catch (Throwable t) {
                ModuleLog.warn("(IE|CacheAutoClear) Could not clear the cache this time: " + t.getClass().getSimpleName() + ": " + t.getMessage());
            }
        }, "ie-cacheclear").start();
    }

    private static long osCacheBytes(Context app) {
        try {
            if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.O) return 0;
            android.app.usage.StorageStatsManager ssm =
                    (android.app.usage.StorageStatsManager) app.getSystemService(Context.STORAGE_STATS_SERVICE);
            if (ssm == null) return 0;
            android.app.usage.StorageStats st = ssm.queryStatsForUid(
                    android.os.storage.StorageManager.UUID_DEFAULT, android.os.Process.myUid());
            return st.getCacheBytes();
        } catch (Throwable ignored) {
            return 0;
        }
    }
}
