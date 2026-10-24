package ps.reso.instaeclipse.utils.core;

import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.res.Resources;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffColorFilter;
import android.graphics.drawable.Drawable;

import java.util.concurrent.ExecutionException;
import java.util.concurrent.FutureTask;
import java.util.function.Consumer;

import ps.reso.instaeclipse.utils.log.ModuleLog;

/** Resources belonging to the loaded module, including APKs embedded by LSPatch. */
public final class ModuleResources {
    private static final Consumer<Runnable> BACKGROUND = task -> new Thread(task, "IE-Res").start();

    private static volatile FutureTask<Resources> build;
    private static volatile Consumer<Runnable> starter = BACKGROUND;

    private ModuleResources() {}

    public static void init(Context host, String modulePath) {
        if (modulePath == null || modulePath.isEmpty()) {
            build = null;
            return;
        }
        Context app = host.getApplicationContext() != null ? host.getApplicationContext() : host;
        FutureTask<Resources> task = new FutureTask<>(() -> load(app, modulePath));
        build = task;
        starter.accept(task);
    }

    public static void startBuildsForTest(Consumer<Runnable> testStarter) {
        starter = testStarter != null ? testStarter : BACKGROUND;
    }

    private static Resources load(Context host, String modulePath) {
        try {
            PackageManager pm = host.getPackageManager();
            PackageInfo archive = pm.getPackageArchiveInfo(modulePath, 0);
            if (archive == null || archive.applicationInfo == null) {
                throw new IllegalArgumentException("Cannot read module APK");
            }
            ApplicationInfo info = archive.applicationInfo;
            info.sourceDir = modulePath;
            info.publicSourceDir = modulePath;
            // Numeric resource IDs must match the loaded code, not a different companion version.
            return pm.getResourcesForApplication(info);
        } catch (Exception e) {
            ModuleLog.detail("(IE|Resources) APK resources unavailable; trying installed module", e);
            return null;
        }
    }

    private static Resources loaded() {
        FutureTask<Resources> task = build;
        if (task == null) return null;
        task.run();
        boolean interrupted = false;
        try {
            while (true) {
                try {
                    return task.get();
                } catch (InterruptedException e) {
                    interrupted = true;
                } catch (ExecutionException e) {
                    return null;
                }
            }
        } finally {
            if (interrupted) Thread.currentThread().interrupt();
        }
    }

    public static Resources get(Context context) throws PackageManager.NameNotFoundException {
        Resources loaded = loaded();
        if (loaded != null) return loaded;
        if (CommonUtils.MY_PACKAGE_NAME.equals(context.getPackageName())) return context.getResources();
        return context.createPackageContext(CommonUtils.MY_PACKAGE_NAME,
                Context.CONTEXT_IGNORE_SECURITY).getResources();
    }

    /**
     * A drawable from the loaded module APK, or null (logged). Replaces the XModuleResources
     * loaders, which produced blank icons under embedded LSPatch.
     */
    public static Drawable drawable(Context context, int resId) {
        try {
            return get(context).getDrawable(resId, null);
        } catch (Throwable t) {
            ModuleLog.warn("(IE|Resources) An icon could not be loaded (0x" + Integer.toHexString(resId) + ")", t);
            return null;
        }
    }

    public static Drawable icon(Context context, int resId) {
        Drawable icon = drawable(context, resId);
        return icon != null ? icon.mutate() : null;
    }

    public static Drawable icon(Context context, int resId, int tint) {
        Drawable icon = icon(context, resId);
        if (icon != null) icon.setColorFilter(new PorterDuffColorFilter(tint, PorterDuff.Mode.SRC_IN));
        return icon;
    }
}
