package ps.reso.instaeclipse.utils.users;

public final class WatchlistPolicy {

    static final int EXPIRE_CHECKS = 3;
    static final long EXPIRE_MS = 30L * 24 * 60 * 60_000L;
    static final double COVERAGE_POINTS = 2.0;

    private WatchlistPolicy() {}

    public static boolean complete(int passes, int repeated, int lastPassAdded, double estMissing,
                                   int expected, int hidden, int distinct, boolean limited) {
        if (limited || distinct < 0) return false;
        long gap = expected < 0 ? Long.MAX_VALUE : (long) expected - Math.max(0, hidden) - distinct;
        boolean paging = gap <= 0
                || (passes <= 1 ? repeated == 0 : lastPassAdded == 0 || (estMissing >= 0 && estMissing < 0.5));
        if (!paging) return false;
        if (expected < 0) return true;
        return gap <= Math.max(5.0, 0.01 * expected);
    }

    public static int strikesNeeded(int passes, int repeated) {
        return passes <= 1 && repeated == 0 ? 1 : 2;
    }

    public static boolean confirmed(int strikes, int need, int missedChecks, long firstMissed, long now) {
        return strikes >= need || missedChecks >= EXPIRE_CHECKS || (firstMissed > 0 && now - firstMissed >= EXPIRE_MS);
    }

    public enum DiffMode {
        NONE,
        ADDITIONS_ONLY,
        FULL
    }

    public static DiffMode diffMode(boolean prevUsable, boolean nowLimited) {
        if (!prevUsable) return DiffMode.NONE;
        return nowLimited ? DiffMode.ADDITIONS_ONLY : DiffMode.FULL;
    }

    public enum Trust {
        OK,
        REBASELINE,
        KEEP_SAVED
    }

    public static Trust trust(int oldListed, int oldCount, int newListed, int newCount) {
        if (oldListed < 0 || newListed < 0 || oldCount <= 0 || newCount <= 0) return Trust.OK;
        long excess = (long) (newListed - oldListed) - (newCount - oldCount);
        double tol = Math.max(3.0, 0.01 * newCount);
        double covGap = 100.0 * newListed / newCount - 100.0 * oldListed / oldCount;
        boolean covered = Math.abs(covGap) > COVERAGE_POINTS && Math.abs(covGap) / 100.0 * newCount > tol;
        if (excess > tol || (covered && covGap > 0)) return Trust.REBASELINE;
        if (-excess > tol || (covered && covGap < 0)) return Trust.KEEP_SAVED;
        return Trust.OK;
    }
}
