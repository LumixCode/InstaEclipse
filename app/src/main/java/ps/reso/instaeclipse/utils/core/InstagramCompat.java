package ps.reso.instaeclipse.utils.core;

public final class InstagramCompat {

    private InstagramCompat() {}

    public static boolean isTested(String versionName) {
        return ExportBrand.TESTED_INSTAGRAM.equals(versionName);
    }
}
