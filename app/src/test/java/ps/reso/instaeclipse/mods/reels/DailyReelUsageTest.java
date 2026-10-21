package ps.reso.instaeclipse.mods.reels;

import org.junit.Test;

import static org.junit.Assert.*;

public class DailyReelUsageTest {
    @Test public void warnsOnlyAtTenAndFiveRemainingAcrossBothFeeds() {
        DailyReelUsage usage = new DailyReelUsage(20, 0, null);
        for (int i = 1; i <= 100; i++) {
            String id = (i % 2 == 0 ? "main-" : "friends-") + i;
            assertTrue(usage.record(id, 20));
            assertEquals(i == 90 ? 10 : i == 95 ? 5 : -1, usage.warningRemaining(100));
            // recordView only requests a warning when record returns true.
            assertFalse(usage.record(id, 20));
        }
    }

    @Test public void changedAllowanceCanWarnAgainTheSameDay() {
        DailyReelUsage usage = new DailyReelUsage(20, 89, "old");
        usage.record("90", 20);
        assertEquals(10, usage.warningRemaining(100));
        for (int i = 91; i <= 95; i++) usage.record(String.valueOf(i), 20);
        assertEquals(5, usage.warningRemaining(100));
        usage.record("96", 20);
        assertEquals(10, usage.warningRemaining(106));
        for (int i = 97; i <= 101; i++) usage.record(String.valueOf(i), 20);
        assertEquals(5, usage.warningRemaining(106));
    }

    @Test public void restartKeepsDedupButDoesNotSuppressLaterWarnings() {
        DailyReelUsage restored = new DailyReelUsage(20, 90, "90");
        assertFalse(restored.record("90", 20));
        restored.record("91", 20);
        assertEquals(10, restored.warningRemaining(101));
        restored.record("92", 20);
        assertEquals(5, restored.warningRemaining(97));
    }

    @Test public void smallAllowanceAndMidnightUseCurrentCount() {
        DailyReelUsage usage = new DailyReelUsage(20, 100, "last");
        usage.record("first", 21);
        assertEquals(-1, usage.warningRemaining(1));
        assertEquals(5, usage.warningRemaining(6));
        assertEquals(10, usage.warningRemaining(11));
        assertFalse(usage.record("first", 20));
    }

    @Test public void mainAndFriendsConsumeOneAllowance() {
        DailyReelUsage usage = new DailyReelUsage(20, 0, null);
        for (int i = 0; i < 60; i++) usage.record("main-" + i, 20);
        for (int i = 0; i < 40; i++) usage.record("friends-" + i, 20);
        assertEquals(100, usage.count);
        assertTrue(usage.reached(100));
        assertFalse(usage.blocksPlayback("friends-39", 100));
        assertTrue(usage.blocksPlayback("main-60", 100));
    }

    @Test public void loopsAndRepeatedImpressionsDoNotConsumeMoreSlots() {
        DailyReelUsage usage = new DailyReelUsage(20, 0, null);
        assertTrue(usage.record("reel-a", 20));
        for (int i = 0; i < 50; i++) assertFalse(usage.record("reel-a", 20));
        assertEquals(1, usage.count);
    }

    @Test public void returningAfterAnotherReelCountsAsAnotherView() {
        DailyReelUsage usage = new DailyReelUsage(20, 0, null);
        usage.record("reel-a", 20);
        usage.record("reel-b", 20);
        usage.record("reel-a", 20);
        assertEquals(3, usage.count);
    }

    @Test public void finalReelCanFinishButNextCachedReelIsBlocked() {
        DailyReelUsage usage = new DailyReelUsage(20, 98, "98");
        usage.record("99", 20);
        assertFalse(usage.reached(100));
        usage.record("100", 20);
        assertTrue(usage.reached(100));
        assertFalse(usage.blocksPlayback("100", 100));
        for (int i = 101; i <= 110; i++) assertTrue(usage.blocksPlayback(String.valueOf(i), 100));
        assertTrue(usage.blocksPlayback("99", 100));
        assertTrue(usage.blocksPlayback(null, 100));
        assertEquals(100, usage.count);
    }

    @Test public void restoringSavedStateDoesNotResetOrRecountCurrentReel() {
        DailyReelUsage previous = new DailyReelUsage(20, 100, "last-reel");
        DailyReelUsage restored = new DailyReelUsage(previous.day, previous.count, previous.lastMediaId);
        assertFalse(restored.record("last-reel", 20));
        assertTrue(restored.reached(100));
        assertFalse(restored.blocksPlayback("last-reel", 100));
        assertTrue(restored.blocksPlayback("another-reel", 100));
    }

    @Test public void nextDayResetsEvenWhenCurrentReelHasNotChanged() {
        DailyReelUsage usage = new DailyReelUsage(20, 110, "reel-a");
        assertTrue(usage.rollover(21));
        assertFalse(usage.reached(100));
        usage.record("reel-a", 21);
        assertEquals(1, usage.count);
    }

    @Test public void movingClockBackDoesNotGrantAnotherAllowance() {
        DailyReelUsage usage = new DailyReelUsage(20, 110, "reel-a");
        assertFalse(usage.rollover(19));
        assertTrue(usage.blocksPlayback("reel-a", 100));
    }

    @Test public void changingLimitKeepsUsage() {
        DailyReelUsage usage = new DailyReelUsage(20, 110, "reel-a");
        assertTrue(usage.blocksPlayback("reel-a", 100));
        assertFalse(usage.reached(200));
        assertTrue(usage.reached(50));
        assertEquals(110, usage.count);
    }

    @Test public void unknownMediaCannotConsumeAllowance() {
        DailyReelUsage usage = new DailyReelUsage(20, 0, null);
        assertFalse(usage.record(null, 20));
        assertFalse(usage.record("", 20));
        assertEquals(0, usage.count);
    }

    @Test public void invalidLimitsFallBackAndUsageCannotOverflow() {
        assertEquals(100, DailyReelUsage.validLimit(0));
        assertEquals(100, DailyReelUsage.validLimit(-1));
        assertEquals(100, DailyReelUsage.validLimit(Integer.MAX_VALUE));
        assertEquals(1, DailyReelUsage.validLimit(1));
        assertEquals(10_000, DailyReelUsage.validLimit(10_000));
        DailyReelUsage usage = new DailyReelUsage(20, Integer.MAX_VALUE, "old");
        usage.record("new", 20);
        assertEquals(Integer.MAX_VALUE, usage.count);
    }

    @Test public void allowanceOfOneStillLetsFirstReelFinish() {
        DailyReelUsage usage = new DailyReelUsage(20, 0, null);
        assertFalse(usage.blocksPlayback("first", 1));
        usage.record("first", 20);
        assertFalse(usage.blocksPlayback("first", 1));
        assertFalse(usage.record("first", 20));
        assertTrue(usage.blocksPlayback("second", 1));
        assertEquals(1, usage.count);
    }

    @Test public void nextDayUnlocksPreviouslyBlockedMedia() {
        DailyReelUsage usage = new DailyReelUsage(20, 100, "final");
        assertTrue(usage.blocksPlayback("next", 100));
        usage.rollover(21);
        assertFalse(usage.blocksPlayback("next", 100));
    }
}
