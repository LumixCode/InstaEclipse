package ps.reso.instaeclipse.utils.users;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.After;
import org.junit.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Not-listed accounts, uncertain gains, the reliability guard and limited lists (WatchlistStore.apply). */
public class WatchlistStorePendingTest {

    private static final long DAY = 24 * 60 * 60_000L;

    @After
    public void noCountSource() {
        WatchlistStore.setCountSource(null);
    }

    private static Map<String, String[]> users(String... ids) {
        Map<String, String[]> m = new LinkedHashMap<>();
        for (String id : ids) m.put(id, new String[]{"u" + id, null});
        return m;
    }

    /** ids from..to (inclusive) */
    private static Map<String, String[]> range(int from, int to) {
        Map<String, String[]> m = new LinkedHashMap<>();
        for (int i = from; i <= to; i++) m.put(String.valueOf(i), new String[]{"u" + i, null});
        return m;
    }

    private static WatchlistStore.ListCheck load(int expected, int passes, int repeated, int lastPassAdded, double estMissing) {
        WatchlistStore.ListCheck q = new WatchlistStore.ListCheck();
        q.expected = expected;
        q.passes = passes;
        q.repeated = repeated;
        q.lastPassAdded = lastPassAdded;
        q.estMissing = estMissing;
        return q;
    }

    /** several passes, the last added none: complete, two strikes */
    private static WatchlistStore.ListCheck passes() {
        return load(-1, 4, 20, 0, 0.1);
    }

    /** several passes, the last still added some: incomplete */
    private static WatchlistStore.ListCheck incomplete() {
        return load(-1, 7, 20, 3, 6.0);
    }

    private static WatchlistStore.ListCheck exact() {
        return WatchlistStoreTest.exact();
    }

    private static WatchlistStore.ListCheck limited(int expected) {
        WatchlistStore.ListCheck q = exact();
        q.expected = expected;
        q.limited = true;
        return q;
    }

    private static WatchlistStore.Profile profile() {
        WatchlistStore.Profile p = new WatchlistStore.Profile();
        p.id = "9";
        return p;
    }

    @Test
    public void anAccountListedAgainMakesNoChange() {
        WatchlistStore.Profile p = profile();
        WatchlistStore.apply(p, users("1", "2", "3"), users(), passes(), exact(), 1000);
        WatchlistStore.Result r = WatchlistStore.apply(p, users("1", "2"), users(), passes(), exact(), 2000);
        assertTrue(r.added.isEmpty());
        assertEquals(1, r.notListed.size());
        WatchlistStore.Event e = r.notListed.get(0);
        assertEquals(WatchlistStore.NOT_LISTED, e.type);
        assertEquals("3", e.id);
        assertEquals(1000, e.since); // last listed
        assertEquals(2, p.followers.size()); // the saved list is what was listed
        assertTrue(p.missingFollowers.containsKey("3"));
        assertEquals(1, r.followersList.newlyMissing);

        r = WatchlistStore.apply(p, users("1", "2", "3"), users(), passes(), exact(), 3000);
        assertTrue(r.added.isEmpty());
        assertTrue(r.notListed.isEmpty());
        assertEquals(1, r.followersList.relisted);
        assertTrue(p.missingFollowers.isEmpty());
        assertEquals(0, WatchlistStore.changeCount(p.events)); // only the not-listed entry of 2000
        assertEquals(1, p.events.size());
    }

    @Test
    public void twoCompleteChecksWithoutItMakeOneLossSinceItWasLastListed() {
        WatchlistStore.Profile p = profile();
        WatchlistStore.apply(p, users("1", "2", "3"), users(), passes(), exact(), 1000);
        WatchlistStore.apply(p, users("1", "2"), users(), passes(), exact(), 2000);
        WatchlistStore.Result r = WatchlistStore.apply(p, users("1", "2"), users(), passes(), exact(), 3000);
        assertEquals(1, r.added.size());
        WatchlistStore.Event lost = r.added.get(0);
        assertEquals(WatchlistStore.LOST_FOLLOWER, lost.type);
        assertEquals("3", lost.id);
        assertEquals("u3", lost.username);
        assertEquals(3000, lost.ts);
        assertEquals(1000, lost.since);
        assertTrue(r.notListed.isEmpty());
        assertTrue(p.missingFollowers.isEmpty());
        assertEquals(1, WatchlistStore.changeCount(p.events));
        assertEquals(1, p.checks.get(0).changes);
    }

    @Test
    public void incompleteChecksConfirmOnlyByExpiry() {
        WatchlistStore.Profile p = profile();
        WatchlistStore.apply(p, users("1", "2"), users(), passes(), exact(), 1000);
        assertTrue(WatchlistStore.apply(p, users("1"), users(), incomplete(), exact(), 2000).added.isEmpty());
        assertTrue(WatchlistStore.apply(p, users("1"), users(), incomplete(), exact(), 3000).added.isEmpty());
        // the third check that doesn't list it
        WatchlistStore.Result r = WatchlistStore.apply(p, users("1"), users(), incomplete(), exact(), 4000);
        assertEquals(1, r.added.size());
        assertEquals(WatchlistStore.LOST_FOLLOWER, r.added.get(0).type);
        assertEquals(1000, r.added.get(0).since);
    }

    @Test
    public void aMonthNotListedConfirmsIt() {
        long t0 = 100 * DAY;
        WatchlistStore.Profile p = profile();
        WatchlistStore.apply(p, users("1", "2"), users(), passes(), exact(), t0);
        WatchlistStore.apply(p, users("1"), users(), incomplete(), exact(), t0 + DAY);
        WatchlistStore.Result r = WatchlistStore.apply(p, users("1"), users(), incomplete(), exact(), t0 + 31 * DAY);
        assertEquals(1, r.added.size());
        assertEquals(WatchlistStore.LOST_FOLLOWER, r.added.get(0).type);
    }

    @Test
    public void aStableFollowingListConfirmsAtOnce() {
        WatchlistStore.Profile p = profile();
        WatchlistStore.apply(p, users("1"), users("5", "6"), passes(), exact(), 1000);
        WatchlistStore.Result r = WatchlistStore.apply(p, users("1"), users("5"), passes(), exact(), 2000);
        assertEquals(1, r.added.size());
        assertEquals(WatchlistStore.B_UNFOLLOWED, r.added.get(0).type);
        assertEquals("6", r.added.get(0).id);
        assertTrue(r.notListed.isEmpty());
    }

    @Test
    public void gainsAreUncertainAfterAnIncompleteOrUnknownSavedList() {
        WatchlistStore.Profile p = profile();
        WatchlistStore.apply(p, users("1"), users(), incomplete(), exact(), 1000);
        WatchlistStore.Result r = WatchlistStore.apply(p, users("1", "2"), users(), passes(), exact(), 2000);
        assertTrue(r.added.get(0).uncertain); // the saved list's load wasn't complete
        assertEquals(1, r.followersList.uncertain);
        r = WatchlistStore.apply(p, users("1", "2", "3"), users(), passes(), exact(), 3000);
        assertFalse(r.added.get(0).uncertain); // now it was

        // a profile saved before load records existed: nothing known about its lists
        WatchlistStore.Profile old = profile();
        old.lastCheck = 1000;
        old.followers = users("1");
        r = WatchlistStore.apply(old, users("1", "2"), users(), passes(), exact(), 2000);
        assertTrue(r.added.get(0).uncertain);
        assertFalse(r.first);
    }

    @Test
    public void aShortSavedListIsReplacedWithoutCountingWhatItMissed() {
        WatchlistStore.Profile p = profile();
        // a one-pass baseline that listed 450 of 597
        WatchlistStore.apply(p, range(1, 450), users(), load(597, 1, 100, -1, -1), exact(), 1000);
        // 596 of 600 now, one of the old ones really gone
        Map<String, String[]> now = range(2, 597);
        WatchlistStore.Result r = WatchlistStore.apply(p, now, users(), load(600, 5, 100, 0, 0.2), exact(), 2000);
        WatchlistStore.ListCheck q = r.followersList;
        assertEquals(WatchlistStore.Outcome.REBASELINED, q.outcome);
        assertEquals(450, q.prevListed);
        assertEquals(597, q.prevCount);
        assertEquals(147, q.filled);
        assertEquals(0, r.of(WatchlistStore.GAINED_FOLLOWER).size());
        // losses are still compared (not listed now, two strikes needed)
        assertEquals(1, r.of(WatchlistStore.NOT_LISTED).size());
        assertEquals(596, p.followers.size());
        assertEquals(600, p.followersState.expected);
    }

    @Test
    public void theHeaderCountsJudgeASavedListWithoutItsOwnCount() {
        WatchlistStore.Profile p = profile();
        p.lastCheck = 1000;
        p.followers = range(1, 450);
        p.baseFollowers = 597;
        p.baseFollowing = 0;
        p.baseAt = 1000;
        WatchlistStore.Result r = WatchlistStore.apply(p, range(1, 596), users(), load(600, 5, 100, 0, 0.2), exact(), 2000);
        assertEquals(WatchlistStore.Outcome.REBASELINED, r.followersList.outcome);
        assertTrue(r.added.isEmpty());
    }

    @Test
    public void aShortLoadKeepsTheSavedListAndCountsNoLosses() {
        WatchlistStore.Profile p = profile();
        WatchlistStore.apply(p, range(1, 596), users(), load(600, 5, 100, 0, 0.2), exact(), 1000);
        // a load that listed 480 of 600, one of them new
        Map<String, String[]> now = range(1, 479);
        now.put("5000", new String[]{"u5000", null});
        WatchlistStore.Result r = WatchlistStore.apply(p, now, users(), load(600, 2, 100, 10, 30), exact(), 2000);
        assertEquals(WatchlistStore.Outcome.KEPT_SAVED, r.followersList.outcome);
        assertTrue(r.notListed.isEmpty());
        assertEquals(0, r.of(WatchlistStore.LOST_FOLLOWER).size());
        assertEquals(1, r.of(WatchlistStore.GAINED_FOLLOWER).size()); // certainly a follower now
        assertEquals(597, p.followers.size()); // the saved list, plus the new one
        assertEquals(1000, p.followersState.ts); // still the saved list's check
    }

    @Test
    public void aLimitedListIsComparedForAdditionsOnlyAgainstAFullOne() {
        WatchlistStore.Profile p = profile();
        WatchlistStore.apply(p, range(1, 100), users(), exact(), exact(), 1000);
        Map<String, String[]> sample = range(51, 99);
        sample.put("500", new String[]{"u500", null});
        WatchlistStore.Result r = WatchlistStore.apply(p, sample, users(), limited(120000), exact(), 2000);
        assertEquals(WatchlistStore.Outcome.ADDITIONS, r.followersList.outcome);
        assertEquals(1, r.added.size());
        assertEquals("500", r.added.get(0).id);
        assertTrue(r.notListed.isEmpty());
        assertEquals(101, p.followers.size());
        assertFalse(p.followersState.limited);
    }

    @Test
    public void aLimitedListWithoutAUsableSavedOneIsNotCompared() {
        WatchlistStore.Profile p = profile();
        WatchlistStore.Result r = WatchlistStore.apply(p, range(1, 50), users(), limited(120000), exact(), 1000);
        assertEquals(WatchlistStore.Outcome.NOT_COMPARED, r.followersList.outcome);
        assertTrue(p.followersState.limited);
        // limited again: still nothing compared
        r = WatchlistStore.apply(p, range(20, 69), users(), limited(120000), exact(), 2000);
        assertEquals(WatchlistStore.Outcome.NOT_COMPARED, r.followersList.outcome);
        assertTrue(r.added.isEmpty());
        assertTrue(r.notListed.isEmpty());
        // a full list after a limited one is the new baseline
        r = WatchlistStore.apply(p, range(1, 300), users(), exact(), exact(), 3000);
        assertEquals(WatchlistStore.Outcome.BASELINE, r.followersList.outcome);
        assertTrue(r.added.isEmpty());
        assertFalse(p.followersState.limited);
        r = WatchlistStore.apply(p, range(1, 301), users(), exact(), exact(), 4000);
        assertEquals(WatchlistStore.Outcome.FULL, r.followersList.outcome);
        assertEquals(1, r.added.size());
    }

    @Test
    public void markingAChecksChangesUnreliableKeepsTheLists() {
        WatchlistStore.Profile p = profile();
        WatchlistStore.apply(p, users("1"), users("5"), exact(), exact(), 1000);
        WatchlistStore.apply(p, users("1", "2", "3"), users("5"), exact(), exact(), 2000);
        WatchlistStore.apply(p, users("1", "2", "3", "4"), users("5"), exact(), exact(), 3000);
        assertEquals(3, p.events.size());
        assertEquals(2, WatchlistStore.dropCheckEvents(p, 2000));
        assertEquals(1, p.events.size());
        assertEquals(3000, p.events.get(0).ts);
        assertEquals(4, p.followers.size());
        assertEquals(List.of(2000L), p.unreliable);
        // marking it again changes nothing
        assertEquals(0, WatchlistStore.dropCheckEvents(p, 2000));
        assertEquals(1, p.unreliable.size());

        List<WatchlistStore.Entry> index = new ArrayList<>();
        WatchlistStore.touch(index, p, 3000);
        WatchlistStore.dropCheckEvents(p, 3000);
        WatchlistStore.recount(index, p);
        assertEquals(0, index.get(0).events);
    }

    @Test
    public void notListedEntriesArentCountedAsChanges() {
        WatchlistStore.Profile p = profile();
        WatchlistStore.apply(p, users("1", "2"), users("5", "6"), passes(), passes(), 1000);
        WatchlistStore.Result r = WatchlistStore.apply(p, users("1", "3"), users("5"), passes(), passes(), 2000);
        assertEquals(1, r.added.size()); // gained 3
        assertEquals(2, r.notListed.size()); // 2 and B's 6
        assertEquals(1, r.of(WatchlistStore.B_NOT_LISTED).size());
        assertEquals(1, p.checks.get(0).changes);
        List<WatchlistStore.Entry> index = new ArrayList<>();
        WatchlistStore.touch(index, p, 2000);
        assertEquals(1, index.get(0).events);
        assertEquals(2, index.get(0).followers); // what was listed
    }
}
