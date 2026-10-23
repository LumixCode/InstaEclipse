package ps.reso.instaeclipse.utils.users;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class WatchlistPolicyTest {

    private static final long DAY = 24 * 60 * 60_000L;

    @Test
    public void onePassWithoutRepeatsIsCompleteWithinTheSlack() {
        // one pass without repeats, 3 short of the count
        assertTrue(WatchlistPolicy.complete(1, 0, -1, -1, 2000, 0, 1997, false));
        // a pass that repeated accounts skipped as many
        assertFalse(WatchlistPolicy.complete(1, 120, -1, -1, 600, 0, 480, false));
        // repeats not counted (no page hook): not known to be complete while short of the count
        assertFalse(WatchlistPolicy.complete(1, -1, -1, -1, 100, 0, 99, false));
        // ... but listing all Instagram counts settles it
        assertTrue(WatchlistPolicy.complete(1, -1, -1, -1, 100, 0, 100, false));
        // far short of Instagram's count: 30 short, max(5, 1%) of 2000 is 20
        assertFalse(WatchlistPolicy.complete(1, 0, -1, -1, 2000, 0, 1970, false));
        // ... unless Instagram says it hides enough of them (19 short)
        assertTrue(WatchlistPolicy.complete(1, 0, -1, -1, 2000, 11, 1970, false));
        // count unknown: the paging alone
        assertTrue(WatchlistPolicy.complete(1, 0, -1, -1, -1, 0, 50, false));
    }

    @Test
    public void severalPassesAreCompleteWhenTheLastAddedNoneOrFewAreEstimatedMissing() {
        // 599 of 600, last pass +0
        assertTrue(WatchlistPolicy.complete(5, 100, 0, 0.3, 600, 0, 599, false));
        // the cap hit while the last pass still added 3
        assertFalse(WatchlistPolicy.complete(5, 100, 3, 8.0, 600, 0, 596, false));
        assertTrue(WatchlistPolicy.complete(5, 100, 3, 0.4, 600, 0, 598, false));
        // 596 of 600 after a pass with +0: 4 short, within max(5, 6) (accounts never listed)
        assertTrue(WatchlistPolicy.complete(5, 100, 0, 0.0, 600, 0, 596, false));
        // ... but 587 of 600 is too far short to call complete
        assertFalse(WatchlistPolicy.complete(5, 100, 0, 0.0, 600, 0, 587, false));
        // the passes stopped at Instagram's count, 600 of 600, while the last added 2
        assertTrue(WatchlistPolicy.complete(5, 100, 2, 1.2, 600, 0, 600, false));
        // ... but a count that isn't known can't be reached
        assertFalse(WatchlistPolicy.complete(5, 100, 2, 1.2, -1, 0, 600, false));
        // a 60-follower profile with one account Instagram never lists stays complete
        assertTrue(WatchlistPolicy.complete(2, 3, 0, 0.0, 60, 0, 59, false));
    }

    @Test
    public void aLimitedListIsNeverComplete() {
        assertFalse(WatchlistPolicy.complete(1, 0, -1, -1, 50, 0, 50, true));
    }

    @Test
    public void oneStrikeOnlyForAStableOnePassListing() {
        assertEquals(1, WatchlistPolicy.strikesNeeded(1, 0));
        assertEquals(2, WatchlistPolicy.strikesNeeded(1, 3));
        assertEquals(2, WatchlistPolicy.strikesNeeded(1, -1));
        assertEquals(2, WatchlistPolicy.strikesNeeded(5, 0));
    }

    @Test
    public void notListedCountsAsGoneAfterStrikesChecksOrDays() {
        long t0 = 100 * DAY;
        assertFalse(WatchlistPolicy.confirmed(1, 2, 1, t0, t0));
        assertTrue(WatchlistPolicy.confirmed(2, 2, 2, t0, t0 + DAY));
        assertTrue(WatchlistPolicy.confirmed(1, 1, 1, t0, t0));
        // incomplete checks add no strikes, but the third check that misses it confirms it
        assertFalse(WatchlistPolicy.confirmed(0, 2, 2, t0, t0 + DAY));
        assertTrue(WatchlistPolicy.confirmed(0, 2, 3, t0, t0 + 2 * DAY));
        // ... as does a month
        assertFalse(WatchlistPolicy.confirmed(0, 2, 2, t0, t0 + 29 * DAY));
        assertTrue(WatchlistPolicy.confirmed(0, 2, 2, t0, t0 + 30 * DAY));
    }

    @Test
    public void limitedListsAreComparedForAdditionsOnlyOrNotAtAll() {
        assertEquals(WatchlistPolicy.DiffMode.FULL, WatchlistPolicy.diffMode(true, false));
        assertEquals(WatchlistPolicy.DiffMode.ADDITIONS_ONLY, WatchlistPolicy.diffMode(true, true));
        assertEquals(WatchlistPolicy.DiffMode.NONE, WatchlistPolicy.diffMode(false, true));
        // the saved list was limited (or there is none): this one is the baseline
        assertEquals(WatchlistPolicy.DiffMode.NONE, WatchlistPolicy.diffMode(false, false));
    }

    @Test
    public void aShortSavedListIsRebaselined() {
        // a one-pass baseline that listed 75%, then a near-complete list: +146 listed vs +3 counted
        assertEquals(WatchlistPolicy.Trust.REBASELINE, WatchlistPolicy.trust(450, 597, 596, 600));
        // a large list 1980 of 2002 → 1988 of 2000: +8 vs -2 is within max(3, 20)
        assertEquals(WatchlistPolicy.Trust.OK, WatchlistPolicy.trust(1980, 2002, 1988, 2000));
    }

    @Test
    public void nearCompleteListsWithARealChangeAreTrusted() {
        assertEquals(WatchlistPolicy.Trust.OK, WatchlistPolicy.trust(596, 600, 598, 602));
        assertEquals(WatchlistPolicy.Trust.OK, WatchlistPolicy.trust(596, 600, 589, 593));
        // a real mass follow the count confirms
        assertEquals(WatchlistPolicy.Trust.OK, WatchlistPolicy.trust(596, 600, 1196, 1200));
    }

    @Test
    public void aShortNewListKeepsTheSavedOne() {
        // a stopped load: 480 of 600 against a saved 596 of 600
        assertEquals(WatchlistPolicy.Trust.KEEP_SAVED, WatchlistPolicy.trust(596, 600, 480, 600));
        // a real loss the count confirms is no reason
        assertEquals(WatchlistPolicy.Trust.OK, WatchlistPolicy.trust(596, 600, 396, 400));
    }

    @Test
    public void coverageGapsOfAFewAccountsOnSmallListsAreNoReason() {
        // 47 of 50 → 54 of 55: 4 points, but 2 accounts, within the tolerance of 3
        assertEquals(WatchlistPolicy.Trust.OK, WatchlistPolicy.trust(47, 50, 54, 55));
        // a coverage gap with the count change hiding it: 800 of 1000 → 1000 of 1200
        assertEquals(WatchlistPolicy.Trust.REBASELINE, WatchlistPolicy.trust(800, 1000, 1000, 1200));
    }

    @Test
    public void unknownCountsAreNotJudged() {
        assertEquals(WatchlistPolicy.Trust.OK, WatchlistPolicy.trust(450, -1, 596, 600));
        assertEquals(WatchlistPolicy.Trust.OK, WatchlistPolicy.trust(450, 597, 596, -1));
        assertEquals(WatchlistPolicy.Trust.OK, WatchlistPolicy.trust(-1, 597, 596, 600));
    }
}
