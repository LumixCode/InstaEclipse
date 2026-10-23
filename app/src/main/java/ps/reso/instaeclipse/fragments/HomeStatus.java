package ps.reso.instaeclipse.fragments;

import ps.reso.instaeclipse.utils.core.CommonUtils;
import ps.reso.instaeclipse.utils.core.InstagramCompat;

public final class HomeStatus {

    public enum Pill { NOT_INSTALLED, MODULE_ACTIVE, WORKED, NOT_CONFIRMED, CLONE }

    public final Pill pill;
    public final long workedAt;
    public final boolean tested;
    public final boolean proof;

    private HomeStatus(Pill pill, long workedAt, boolean tested, boolean proof) {
        this.pill = pill;
        this.workedAt = workedAt;
        this.tested = tested;
        this.proof = proof;
    }

    public static HomeStatus of(boolean installed, String versionName, boolean pingReply,
                                long mirrorLogMtime, String activePackage) {
        if (!installed || activePackage == null) return new HomeStatus(Pill.NOT_INSTALLED, 0, false, false);
        boolean tested = InstagramCompat.isTested(versionName);
        long worked = Math.max(mirrorLogMtime, 0);
        boolean proof = pingReply || worked > 0;
        if (!CommonUtils.IG_PACKAGE_NAME.equals(activePackage)) return new HomeStatus(Pill.CLONE, worked, tested, proof);
        if (pingReply) return new HomeStatus(Pill.MODULE_ACTIVE, worked, tested, true);
        if (worked > 0) return new HomeStatus(Pill.WORKED, worked, tested, true);
        return new HomeStatus(Pill.NOT_CONFIRMED, 0, tested, false);
    }
}
