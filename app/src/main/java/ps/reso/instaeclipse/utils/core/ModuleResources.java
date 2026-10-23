package ps.reso.instaeclipse.utils.core;

import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.res.Resources;
import android.graphics.drawable.Drawable;

import ps.reso.instaeclipse.utils.log.ModuleLog;

/** Resources belonging to the loaded module, including APKs embedded by LSPatch. */
public final class ModuleResources {
    // Written once in Application.attach, read from any thread afterwards.
    private static volatile Resources loadedResources;

    private ModuleResources() {}

    public static void init(Context host, String modulePath) {
        loadedResources = null;
        if (modulePath == null || modulePath.isEmpty()) return;
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
            loadedResources = pm.getResourcesForApplication(info);
        } catch (Exception e) {
            ModuleLog.detail("(IE|Resources) APK resources unavailable; trying installed module", e);
        }
    }

    public static Resources get(Context context) throws PackageManager.NameNotFoundException {
        Resources loaded = loadedResources;
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
}
