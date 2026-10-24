package ps.reso.instaeclipse.xposedcompat;

public final class ModuleResources {

    private static volatile String apkPath;

    private ModuleResources() {}

    /** The module APK path, set by the entry the framework loaded. */
    public static void setApkPath(String path) {
        apkPath = path;
    }

    public static String apkPath() {
        return apkPath;
    }
}
