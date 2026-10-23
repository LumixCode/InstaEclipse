package ps.reso.instaeclipse.utils.users;

/**
 * Pure decisions of the profile analyzer's watchlist (WatchlistStore.apply), kept apart so they run
 * as JVM unit tests.
 *
 * <p>Why they exist: Instagram pages someone else's Followers list through a ranking that shifts
 * between requests, so one pass lists a random ~80% of it and even several passes can miss an
 * account or two (a pass can receive all of the list's rows with about a fifth of them
 * repeats). A plain set difference would report each missed account as "stopped following" now
 * and as "new follower" at the next check. So an account a check doesn't list is first kept as
 * "not listed" and only counts as gone when complete checks keep missing it ({@link #strikesNeeded},
 * {@link #confirmed}); a load whose list size disagrees with Instagram's count change is not
 * diffed as if it were reliable ({@link #trust}); and a list Instagram limits is never diffed
 * for losses ({@link #diffMode}).
 */
public final class WatchlistPolicy {

    /** A not-listed account counts as gone after this many checks that didn't list it (complete or
     *  not), so a deactivated or hidden account doesn't stay "not listed" forever. */
    static final int EXPIRE_CHECKS = 3;
    /** ... or this long after the first check that didn't list it. */
    static final long EXPIRE_MS = 30L * 24 * 60 * 60_000L;
    /** Coverage (listed / Instagram's count, in points) a load may differ from the saved list's
     *  before one of them counts as clearly less complete (see trust). */
    static final double COVERAGE_POINTS = 2.0;

    private WatchlistPolicy() {}

    /**
     * Whether a load listed (about) every account Instagram lists, from what the loader saw (not a
     * fixed share of Instagram's count: that count lags the list by 1-3 for hours and includes
     * accounts it never lists):
     * <ul>
     * <li>paging: the accounts listed reached Instagram's count (less those it hides: the loader
     * stops its passes there, so the last pass may still have added some), one pass that repeated
     * no account ({@code repeated} 0: nothing was skipped in its place), or several passes whose
     * last one added none ({@code lastPassAdded} 0) or whose estimate of accounts no pass listed
     * ({@code estMissing}, Chao1) is below 0.5;</li>
     * <li>and the accounts listed ({@code distinct}) are within max(5, 1%) of Instagram's count
     * ({@code expected}, less the accounts it says it hides, {@code hidden}); an unknown count
     * (-1) leaves paging alone to decide. The paging test is what tells a full load; the count
     * test only catches a load far short, as a few accounts in the count are never listed
     * (a few can still be missing after a pass that adds none).</li>
     * </ul>
     * A limited list is never complete. -1: not known (a repeat count of -1 is not 0).
     */
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

    /** Complete checks that must miss an account before it counts as gone: 1 for a list Instagram
     *  sent in one pass without repeating an account (a stable listing such as Following), else 2. */
    public static int strikesNeeded(int passes, int repeated) {
        return passes <= 1 && repeated == 0 ? 1 : 2;
    }

    /**
     * A not-listed account counts as gone: {@code strikes} complete checks missed it (of
     * {@code need}), or {@code missedChecks} checks of any kind did (EXPIRE_CHECKS), or the first
     * check that missed it ({@code firstMissed}) was EXPIRE_MS before {@code now}.
     */
    public static boolean confirmed(int strikes, int need, int missedChecks, long firstMissed, long now) {
        return strikes >= need || missedChecks >= EXPIRE_CHECKS || (firstMissed > 0 && now - firstMissed >= EXPIRE_MS);
    }

    /** How a list is compared with its saved one. */
    public enum DiffMode {
        /** nothing compared */
        NONE,
        /** only new accounts (an account Instagram lists now certainly follows / is followed) */
        ADDITIONS_ONLY,
        /** gains and losses */
        FULL
    }

    /**
     * How a list is compared ({@code prevUsable}: a saved list exists and isn't a limited one;
     * {@code nowLimited}: Instagram limits this load's list, showing only part of it by design):
     * limited now and a usable saved list → additions only, and the saved list is kept (a 50-row
     * window rotating through the list would turn into changes at the next check); no usable saved
     * list → nothing (this list is saved, marked limited when it is, and never diffed while it
     * is); else full.
     */
    public static DiffMode diffMode(boolean prevUsable, boolean nowLimited) {
        if (!prevUsable) return DiffMode.NONE;
        return nowLimited ? DiffMode.ADDITIONS_ONLY : DiffMode.FULL;
    }

    /** Whether a full comparison can be trusted. */
    public enum Trust {
        /** compare as usual */
        OK,
        /** the saved list was clearly less complete than this one: what this one adds isn't new,
         *  only newly listed; it becomes the new baseline without counting those as changes */
        REBASELINE,
        /** this load is clearly less complete than the saved list: keep the saved one and count no
         *  losses */
        KEEP_SAVED
    }

    /**
     * Checks a list against Instagram's own count change: the list grew by
     * {@code newListed - oldListed} while Instagram's count changed by {@code newCount - oldCount}
     * ({@code oldListed}: the saved list with its not-listed accounts). When the two differ by more
     * than max(3, 1% of the count) (the tolerance), or one side's coverage (listed / count) is more
     * than COVERAGE_POINTS below the other's and that gap is more accounts than the tolerance (so
     * one account on a small list isn't a gap), one of the lists missed many accounts: a list that
     * grew more than the count (or covers more) means the saved one was short (REBASELINE); one
     * that shrank more than the count (or covers less) means this load was (KEEP_SAVED). Unknown
     * counts (&lt;= 0) or sizes: OK, nothing to judge by.
     */
    public static Trust trust(int oldListed, int oldCount, int newListed, int newCount) {
        if (oldListed < 0 || newListed < 0 || oldCount <= 0 || newCount <= 0) return Trust.OK;
        long excess = (long) (newListed - oldListed) - (newCount - oldCount);
        double tol = Math.max(3.0, 0.01 * newCount);
        double covGap = 100.0 * newListed / newCount - 100.0 * oldListed / oldCount; // points, + when this one covers more
        boolean covered = Math.abs(covGap) > COVERAGE_POINTS && Math.abs(covGap) / 100.0 * newCount > tol;
        if (excess > tol || (covered && covGap > 0)) return Trust.REBASELINE;
        if (-excess > tol || (covered && covGap < 0)) return Trust.KEEP_SAVED;
        return Trust.OK;
    }
}
