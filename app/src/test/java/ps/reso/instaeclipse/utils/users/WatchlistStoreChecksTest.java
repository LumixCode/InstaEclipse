package ps.reso.instaeclipse.utils.users;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.After;
import org.junit.Test;

import java.util.LinkedHashMap;
import java.util.Map;

/** The check records each analysis adds (the profile changelog's source). */
public class WatchlistStoreChecksTest {

    @After
    public void noCountSource() {
        WatchlistStore.setCountSource(null);
    }

    private static Map<String, String[]> users(String... ids) {
        Map<String, String[]> m = new LinkedHashMap<>();
        for (String id : ids) m.put(id, new String[]{"u" + id, null});
        return m;
    }

    private static WatchlistStore.ListCheck exact() {
        return WatchlistStoreTest.exact();
    }

    @Test
    public void eachAnalysisAddsACheckNewestFirst() {
        WatchlistStore.setCountSource((id, now) -> now == 2000 ? new int[]{600, 2000} : null);
        WatchlistStore.Profile p = new WatchlistStore.Profile();
        p.id = "9";
        WatchlistStore.apply(p, users("1", "2"), users("3"), exact(), exact(), 1000);
        WatchlistStore.apply(p, users("1"), users("3", "4", "5"), exact(), exact(), 2000);
        assertEquals(2, p.checks.size());

        WatchlistStore.Check last = p.checks.get(0);
        assertEquals(2000, last.ts);
        assertEquals(1000, last.since);
        assertFalse(last.first);
        assertEquals(1, last.followers);
        assertEquals(3, last.following);
        assertEquals(600, last.igFollowers);
        assertEquals(2000, last.igFollowing);
        assertEquals(3, last.changes); // lost 2, B followed 4 and 5

        WatchlistStore.Check first = p.checks.get(1);
        assertTrue(first.first);
        assertEquals(0, first.since);
        assertEquals(2, first.followers);
        assertEquals(-1, first.igFollowers);
        assertEquals(0, first.changes);
    }

    @Test
    public void checksKeepHowEachListLoadedAndWasCompared() {
        WatchlistStore.Profile p = new WatchlistStore.Profile();
        p.id = "9";
        WatchlistStore.apply(p, users("1", "2"), users("3"), exact(), exact(), 1000);
        WatchlistStore.ListCheck fol = new WatchlistStore.ListCheck();
        fol.received = 3;
        fol.repeated = 1;
        fol.passes = 3;
        fol.lastPassAdded = 0;
        fol.expected = 3;
        WatchlistStore.apply(p, users("1", "2", "4"), users("3"), fol, exact(), 2000);

        WatchlistStore.ListCheck last = p.checks.get(0).followersList;
        assertEquals(WatchlistStore.Outcome.FULL, last.outcome);
        assertTrue(last.complete);       // the last pass added none, 3 of 3 listed
        assertEquals(2, last.before);    // the saved list's size before it
        assertEquals(3, last.expected);  // Instagram's count from the list
        assertEquals(3, last.distinct);
        assertEquals(1, last.gained);
        assertEquals(0, last.uncertain); // the first check's one-pass load was complete
        assertEquals(2000, p.followersState.ts); // the saved list is this check's now
        assertEquals(3, p.followersState.expected);
        assertEquals(1, p.followersState.complete);

        WatchlistStore.ListCheck first = p.checks.get(1).followersList;
        assertEquals(WatchlistStore.Outcome.FIRST, first.outcome);
        assertEquals(-1, first.before);
    }

    @Test
    public void checksAreCapped() {
        WatchlistStore.Profile p = new WatchlistStore.Profile();
        p.id = "9";
        for (int i = 1; i <= WatchlistStore.MAX_CHECKS + 3; i++) WatchlistStore.apply(p, users("1"), users("2"), exact(), exact(), i);
        assertEquals(WatchlistStore.MAX_CHECKS, p.checks.size());
        assertEquals(WatchlistStore.MAX_CHECKS + 3, p.checks.get(0).ts);
    }
}
