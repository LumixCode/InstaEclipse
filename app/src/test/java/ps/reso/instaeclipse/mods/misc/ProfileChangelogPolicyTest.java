package ps.reso.instaeclipse.mods.misc;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.After;
import org.junit.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import ps.reso.instaeclipse.mods.misc.ProfileChangelogPolicy.Group;
import ps.reso.instaeclipse.mods.misc.ProfileChangelogPolicy.Kind;
import ps.reso.instaeclipse.mods.misc.ProfileChangelogPolicy.Row;
import ps.reso.instaeclipse.mods.misc.ProfileChangelogPolicy.Section;
import ps.reso.instaeclipse.utils.users.WatchlistStore;

public class ProfileChangelogPolicyTest {

    @After
    public void noCountSource() {
        WatchlistStore.setCountSource(null);
    }

    private static Map<String, String[]> users(String... ids) {
        Map<String, String[]> m = new LinkedHashMap<>();
        for (String id : ids) m.put(id, new String[]{"u" + id, "Name " + id});
        return m;
    }

    private static WatchlistStore.Event ev(long ts, long since, String type, String id) {
        return new WatchlistStore.Event(ts, since, type, id, "u" + id, null);
    }

    /** A load Instagram sent in one pass without repeats: complete, one strike confirms a loss. */
    private static WatchlistStore.ListCheck exact() {
        WatchlistStore.ListCheck q = new WatchlistStore.ListCheck();
        q.passes = 1;
        q.repeated = 0;
        return q;
    }

    /** Several passes, the last added none: complete, two strikes confirm a loss. */
    private static WatchlistStore.ListCheck passes() {
        WatchlistStore.ListCheck q = new WatchlistStore.ListCheck();
        q.passes = 4;
        q.repeated = 20;
        q.lastPassAdded = 0;
        return q;
    }

    @Test
    public void notListedAndUncertainEntriesHaveTheirOwnSectionsAndArentChanges() {
        WatchlistStore.Profile p = new WatchlistStore.Profile();
        p.id = "9";
        WatchlistStore.ListCheck first = passes();
        first.lastPassAdded = 5; // incomplete: the next gains are uncertain
        WatchlistStore.apply(p, users("1", "2"), users("5"), first, exact(), 1000);
        WatchlistStore.apply(p, users("1", "3"), users("5"), passes(), exact(), 2000);
        WatchlistStore.apply(p, users("1", "3"), users("5"), passes(), exact(), 3000);

        List<Group> g = ProfileChangelogPolicy.groups(p, WatchlistStore.maxEvents());
        assertEquals(3, g.size());
        Group last = g.get(0), mid = g.get(1);
        assertEquals(1, last.of(Section.LOST).size());     // 2, confirmed at the second miss
        assertTrue(ProfileChangelogPolicy.showsLastListed(Section.LOST, last.of(Section.LOST).get(0), last));
        assertEquals(1000, last.of(Section.LOST).get(0).since);
        assertEquals(1, mid.of(Section.GAINED_MAYBE).size());
        assertEquals(0, mid.of(Section.GAINED).size());
        assertEquals(1, mid.of(Section.NOT_LISTED).size());
        assertFalse(ProfileChangelogPolicy.showsLastListed(Section.NOT_LISTED, mid.of(Section.NOT_LISTED).get(0), mid));
        assertEquals(1, mid.kept());                        // the not-listed entry isn't a change
        assertFalse(mid.partial);
        assertTrue(ProfileChangelogPolicy.canMarkUnreliable(mid));
        // sizes are what each check listed
        assertEquals(2, last.followers);
        assertEquals(2, mid.followers);
        assertEquals(2, g.get(2).followers);

        List<Row> rows = ProfileChangelogPolicy.rows(g);
        // 3000: heading, LOST + 1; 2000: heading, GAINED_MAYBE + 1, NOT_LISTED + 1; 1000: heading
        assertEquals(9, rows.size());
        assertEquals(Section.GAINED_MAYBE, rows.get(4).section);
        assertEquals(Section.NOT_LISTED, rows.get(6).section);
    }

    @Test
    public void recordedSizesBeforeACheckReachTheOlderUnrecordedOnes() {
        WatchlistStore.Profile p = new WatchlistStore.Profile();
        p.id = "9";
        // an older analysis saved before check records: its list had 3 then (1, 2, 4)
        p.lastCheck = 1000;
        p.followers = users("1", "2", "4");
        p.events.add(ev(1000, 500, WatchlistStore.GAINED_FOLLOWER, "4"));
        // now 4 isn't listed (pending, no change) and 3 is new
        WatchlistStore.apply(p, users("1", "2", "3"), users(), passes(), exact(), 2000);
        List<Group> g = ProfileChangelogPolicy.groups(p, WatchlistStore.maxEvents());
        assertEquals(3, g.size());
        assertEquals(3, g.get(0).followers);
        assertEquals(3, g.get(1).followers); // from the record's size before, not undoing events
        assertEquals(2, g.get(2).followers); // before 1000: 4 hadn't followed yet
    }

    @Test
    public void checksMarkedUnreliableShowAsSuchWithoutTheirChanges() {
        WatchlistStore.Profile p = new WatchlistStore.Profile();
        p.lastCheck = 3000;
        p.followers = users("1", "2", "3");
        p.events.add(ev(3000, 2000, WatchlistStore.GAINED_FOLLOWER, "3"));
        p.events.add(ev(2000, 1000, WatchlistStore.GAINED_FOLLOWER, "2"));
        WatchlistStore.dropCheckEvents(p, 3000);
        List<Group> g = ProfileChangelogPolicy.groups(p, 3000);
        Group marked = g.get(0);
        assertEquals(3000, marked.ts);
        assertTrue(marked.unreliable);
        assertFalse(marked.partial);
        assertEquals(0, marked.kept());
        assertFalse(ProfileChangelogPolicy.canMarkUnreliable(marked));
        // its changes are gone, so the sizes before it aren't known any more
        assertEquals(-1, g.get(1).followers);
    }

    @Test
    public void recordedChecksNewestFirstWithCountsAndChanges() {
        WatchlistStore.setCountSource((id, now) -> now == 2000 ? new int[]{5, 9} : null);
        WatchlistStore.Profile p = new WatchlistStore.Profile();
        p.id = "9";
        WatchlistStore.apply(p, users("1", "2"), users("3"), exact(), exact(), 1000);
        // follower 2 left, 4 and 5 arrived; B followed 6
        WatchlistStore.apply(p, users("1", "4", "5"), users("3", "6"), exact(), exact(), 2000);
        WatchlistStore.apply(p, users("1", "4", "5"), users("3", "6"), exact(), exact(), 3000);

        List<Group> g = ProfileChangelogPolicy.groups(p, WatchlistStore.maxEvents());
        assertEquals(3, g.size());
        assertEquals(3000, g.get(0).ts);
        assertEquals(2000, g.get(1).ts);
        assertEquals(1000, g.get(2).ts);

        Group newest = g.get(0);
        assertTrue(newest.recorded);
        assertFalse(newest.first);
        assertEquals(2000, newest.since);
        assertEquals(0, newest.kept());
        assertEquals(0, newest.found);
        assertFalse(newest.partial);
        assertEquals(3, newest.followers);
        assertEquals(2, newest.following);
        assertEquals(-1, newest.igFollowers); // the header showed no counts around it

        Group middle = g.get(1);
        assertEquals(1000, middle.since);
        assertEquals(5, middle.igFollowers);
        assertEquals(9, middle.igFollowing);
        assertEquals(2, middle.of(Section.GAINED).size());
        assertEquals(1, middle.of(Section.LOST).size());
        assertEquals("2", middle.of(Section.LOST).get(0).id);
        assertEquals(1, middle.of(Section.B_FOLLOWED).size());
        assertEquals(0, middle.of(Section.B_UNFOLLOWED).size());
        assertEquals(4, middle.found);

        Group oldest = g.get(2);
        assertTrue(oldest.first);
        assertEquals(0, oldest.since);
        assertEquals(2, oldest.followers);
        assertEquals(1, oldest.following);
    }

    @Test
    public void olderAnalysesWithoutRecordsComeFromEventsAndTheSnapshot() {
        WatchlistStore.Profile p = new WatchlistStore.Profile();
        p.id = "9";
        p.lastCheck = 3000;
        p.followers = users("1", "4", "5");
        p.following = users("3", "6");
        p.baseFollowers = 7;
        p.baseFollowing = 8;
        p.baseAt = 3000;
        // newest first, as the log keeps them: at 3000 (vs 2000) 5 followed, B followed 6;
        // at 2000 (vs 1000) follower 2 left
        p.events.add(ev(3000, 2000, WatchlistStore.GAINED_FOLLOWER, "5"));
        p.events.add(ev(3000, 2000, WatchlistStore.B_FOLLOWED, "6"));
        p.events.add(ev(2000, 1000, WatchlistStore.LOST_FOLLOWER, "2"));

        List<Group> g = ProfileChangelogPolicy.groups(p, 3000);
        assertEquals(3, g.size());
        Group last = g.get(0), mid = g.get(1), first = g.get(2);
        assertFalse(last.recorded);
        assertEquals(2000, last.since);
        assertEquals(3, last.followers);
        assertEquals(2, last.following);
        assertEquals(7, last.igFollowers);
        assertEquals(8, last.igFollowing);
        // before 3000: 5 hadn't followed yet, B didn't follow 6
        assertEquals(2, mid.followers);
        assertEquals(1, mid.following);
        assertEquals(-1, mid.igFollowers);
        assertEquals(1000, mid.since);
        // before 2000: follower 2 was still there
        assertEquals(1000, first.ts);
        assertEquals(3, first.followers);
        assertEquals(1, first.following);
        assertEquals(0, first.kept());
        assertFalse(first.first); // not known without a record
        assertFalse(first.partial);
    }

    @Test
    public void baselineOfAnOlderAnalysisIsNotShownOnTheLastOne() {
        WatchlistStore.Profile p = new WatchlistStore.Profile();
        p.lastCheck = 3000;
        p.baseFollowers = 7;
        p.baseFollowing = 8;
        p.baseAt = 2500;
        List<Group> g = ProfileChangelogPolicy.groups(p, 3000);
        assertEquals(1, g.size());
        assertEquals(-1, g.get(0).igFollowers);
        assertEquals(0, g.get(0).followers); // an empty snapshot is still a known size
    }

    @Test
    public void fullLogMarksTheOldestAnalysesPartialAndStopsCounting() {
        WatchlistStore.Profile p = new WatchlistStore.Profile();
        p.lastCheck = 3000;
        p.followers = users("1", "2", "3");
        p.events.add(ev(3000, 2000, WatchlistStore.GAINED_FOLLOWER, "3"));
        p.events.add(ev(2000, 1000, WatchlistStore.GAINED_FOLLOWER, "2"));
        p.events.add(ev(2000, 1000, WatchlistStore.GAINED_FOLLOWER, "9")); // the log is full at 3
        List<Group> g = ProfileChangelogPolicy.groups(p, 3);
        assertEquals(3, g.size());
        assertFalse(g.get(0).partial);
        assertTrue(g.get(1).partial);  // the oldest kept analysis may have lost some
        assertTrue(g.get(2).partial);
        assertEquals(3, g.get(0).followers);
        assertEquals(2, g.get(1).followers);
        assertEquals(-1, g.get(2).followers); // its newer neighbour's changes may be incomplete
    }

    @Test
    public void recordedAnalysisIsPartialWhenSomeOfItsChangesWereDropped() {
        WatchlistStore.Profile p = new WatchlistStore.Profile();
        p.lastCheck = 2000;
        p.checks.add(new WatchlistStore.Check(2000, 1000, false, 10, 4, -1, -1, 3));
        p.events.add(ev(2000, 1000, WatchlistStore.LOST_FOLLOWER, "1"));
        Group g = ProfileChangelogPolicy.groups(p, 3000).get(0);
        assertTrue(g.recorded);
        assertTrue(g.partial);
        assertEquals(10, g.followers); // the record wins over the snapshot
        assertEquals(1, g.kept());
    }

    @Test
    public void eventsOfUnknownTypeAreSkipped() {
        WatchlistStore.Profile p = new WatchlistStore.Profile();
        p.lastCheck = 2000;
        p.events.add(ev(2000, 1000, "something_else", "1"));
        List<Group> g = ProfileChangelogPolicy.groups(p, 3000);
        assertEquals(2, g.size());
        assertEquals(0, g.get(0).kept());
        assertTrue(ProfileChangelogPolicy.groups(new WatchlistStore.Profile(), 3000).isEmpty());
    }

    @Test
    public void rowsPerCheckInSectionOrder() {
        WatchlistStore.Profile p = new WatchlistStore.Profile();
        p.lastCheck = 2000;
        p.events.add(ev(2000, 1000, WatchlistStore.B_UNFOLLOWED, "1"));
        p.events.add(ev(2000, 1000, WatchlistStore.GAINED_FOLLOWER, "2"));
        p.events.add(ev(2000, 1000, WatchlistStore.GAINED_FOLLOWER, "3"));
        List<Row> rows = ProfileChangelogPolicy.rows(ProfileChangelogPolicy.groups(p, 3000));
        // 2000: heading, gained (2 accounts), B unfollowed (1); 1000: heading only
        assertEquals(7, rows.size());
        assertEquals(Kind.CHECK, rows.get(0).kind);
        assertEquals(Kind.SECTION, rows.get(1).kind);
        assertEquals(Section.GAINED, rows.get(1).section);
        assertEquals("2", rows.get(2).event.id);
        assertEquals("3", rows.get(3).event.id);
        assertEquals(Section.B_UNFOLLOWED, rows.get(4).section);
        assertEquals(Kind.ACCOUNT, rows.get(5).kind);
        assertEquals(Kind.CHECK, rows.get(6).kind);
        assertEquals(1000, rows.get(6).group.ts);
    }

    @Test
    public void pagesNeverEndOnAHeadingOfTheirNextRow() {
        WatchlistStore.Profile p = new WatchlistStore.Profile();
        p.lastCheck = 2000;
        p.events.add(ev(2000, 1000, WatchlistStore.GAINED_FOLLOWER, "2"));
        p.events.add(ev(2000, 1000, WatchlistStore.GAINED_FOLLOWER, "3"));
        List<Row> rows = ProfileChangelogPolicy.rows(ProfileChangelogPolicy.groups(p, 3000));
        // CHECK, SECTION, ACCOUNT, ACCOUNT, CHECK
        assertEquals(3, ProfileChangelogPolicy.pageEnd(rows, 0, 1)); // check + section + first account
        assertEquals(3, ProfileChangelogPolicy.pageEnd(rows, 0, 2));
        assertEquals(3, ProfileChangelogPolicy.pageEnd(rows, 0, 3));
        assertEquals(4, ProfileChangelogPolicy.pageEnd(rows, 0, 4));
        assertEquals(5, ProfileChangelogPolicy.pageEnd(rows, 3, 150));
        assertEquals(5, ProfileChangelogPolicy.pageEnd(rows, 0, 150));
        assertEquals(3, ProfileChangelogPolicy.pageEnd(rows, 0, 0)); // at least one row, then as above
    }

    @Test
    public void analysesWithoutChangesFillAPageOneByOne() {
        WatchlistStore.Profile p = new WatchlistStore.Profile();
        for (long ts = 5000; ts >= 1000; ts -= 1000)
            p.checks.add(new WatchlistStore.Check(ts, ts - 1000, ts == 1000, 1, 1, -1, -1, 0));
        p.lastCheck = 5000;
        List<Row> rows = ProfileChangelogPolicy.rows(ProfileChangelogPolicy.groups(p, 3000));
        assertEquals(5, rows.size());
        assertEquals(2, ProfileChangelogPolicy.pageEnd(rows, 0, 2));
        assertEquals(4, ProfileChangelogPolicy.pageEnd(rows, 2, 2));
        assertEquals(5, ProfileChangelogPolicy.pageEnd(rows, 4, 2));
    }

    @Test
    public void menuOffersChangelogOnlyWithASavedAnalysis() {
        assertTrue(ProfileChangelogPolicy.inMenu(true, true, 1000));
        assertFalse(ProfileChangelogPolicy.inMenu(true, true, 0));
        assertFalse(ProfileChangelogPolicy.inMenu(false, true, 1000));
        assertFalse(ProfileChangelogPolicy.inMenu(true, false, 1000));
    }
}
