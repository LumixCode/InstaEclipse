package ps.reso.instaeclipse.mods.reels;

import org.junit.Test;

import static org.junit.Assert.*;

public class ReelViewerSessionTest {
    @Test public void normalFeedsKeepPageBlockInsteadOfSilentScrollLock() {
        for (boolean allowDM : new boolean[]{false, true}) {
            ReelViewerSession feed = new ReelViewerSession(false);
            feed.select("last-allowed");
            assertFalse(feed.shouldLockScrolling(true, allowDM));
            DailyReelUsage usage = new DailyReelUsage(20, 100, "last-allowed");
            assertFalse(usage.blocksPlayback(feed.currentMediaId, 100));
            feed.select("next");
            assertTrue(usage.blocksPlayback(feed.currentMediaId, 100));
        }
    }

    @Test public void dmScrollLockRequiresExhaustedDailyQuotaAndException() {
        ReelViewerSession dm = new ReelViewerSession(true);
        dm.select("shared");
        assertFalse(dm.shouldLockScrolling(false, false));
        assertFalse(dm.shouldLockScrolling(false, true));
        assertFalse(dm.shouldLockScrolling(true, false));
        assertTrue(dm.shouldLockScrolling(true, true));
    }

    @Test public void dmLockCoversInitializationButNeverOnwardFeed() {
        ReelViewerSession dm = new ReelViewerSession(true);
        assertTrue(dm.shouldLockScrolling(true, true));
        dm.select("shared");
        assertTrue(dm.shouldLockScrolling(true, true));
        dm.select("recommended");
        assertFalse(dm.shouldLockScrolling(true, true));
    }

    @Test public void dmUnlocksWhenQuotaIsAvailableAgain() {
        ReelViewerSession dm = new ReelViewerSession(true);
        dm.select("shared");
        assertTrue(dm.shouldLockScrolling(true, true));
        assertFalse(dm.shouldLockScrolling(false, true));
        assertTrue(dm.isDmReel());
    }

    @Test public void onlyOpenedDmReelIsExemptIncludingItsLoops() {
        ReelViewerSession session = new ReelViewerSession(true);
        assertTrue(session.isDmReel());
        session.select("shared-reel");
        assertTrue(session.isDmReel());
        session.select("shared-reel");
        assertTrue(session.isDmReel());
        session.select("suggested-next");
        assertFalse(session.isDmReel());
        session.select("shared-reel");
        assertTrue(session.isDmReel());
    }

    @Test public void mainAndFriendsNeverGetDmExemption() {
        ReelViewerSession main = new ReelViewerSession(false);
        ReelViewerSession friends = new ReelViewerSession(false);
        main.select("shared-reel");
        friends.select("shared-reel");
        assertFalse(main.isDmReel());
        assertFalse(friends.isDmReel());
    }

    @Test public void openingAnotherMessageHasAnIndependentExemption() {
        ReelViewerSession first = new ReelViewerSession(true);
        ReelViewerSession second = new ReelViewerSession(true);
        first.select("message-one");
        first.select("message-two");
        second.select("message-two");
        assertFalse(first.isDmReel());
        assertTrue(second.isDmReel());
    }

    @Test public void missingMediaDoesNotReplaceDmIdentity() {
        ReelViewerSession session = new ReelViewerSession(true);
        session.select(null);
        session.select("");
        session.select("shared-reel");
        session.select(null);
        assertEquals("shared-reel", session.currentMediaId);
        assertTrue(session.isDmReel());
    }

    @Test public void dmAtCapDoesNotGrantAnExtraFeedView() {
        DailyReelUsage usage = new DailyReelUsage(20, 100, "last-feed-reel");
        ReelViewerSession dm = new ReelViewerSession(true);
        dm.select("shared-reel");
        assertTrue(dm.isDmReel());
        assertEquals(100, usage.count);
        dm.select("suggested-next");
        assertFalse(dm.isDmReel());
        assertTrue(usage.blocksPlayback(dm.currentMediaId, 100));
        assertFalse(usage.blocksPlayback("last-feed-reel", 100));
    }
}
