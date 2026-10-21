package ps.reso.instaeclipse.mods.ghost;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

public class UnsentRestorePolicyTest {
    @Test public void insertsBetweenNeighboursNewestFirst() {
        assertEquals(1, UnsentRestorePolicy.restoreIndex(new long[]{50, 40, 30, 20}, 45));
        assertEquals(3, UnsentRestorePolicy.restoreIndex(new long[]{50, 40, 30, 20}, 25));
    }

    @Test public void insertsBetweenNeighboursOldestFirst() {
        assertEquals(1, UnsentRestorePolicy.restoreIndex(new long[]{20, 30, 40, 50}, 25));
        assertEquals(3, UnsentRestorePolicy.restoreIndex(new long[]{20, 30, 40, 50}, 45));
    }

    @Test public void keepsNewerThanEverythingAtTheNewestEnd() {
        assertEquals(0, UnsentRestorePolicy.restoreIndex(new long[]{50, 40}, 60));
        assertEquals(2, UnsentRestorePolicy.restoreIndex(new long[]{40, 50}, 60));
    }

    @Test public void waitsUntilOlderHistoryHasLoaded() {
        assertEquals(-1, UnsentRestorePolicy.restoreIndex(new long[]{50, 40, 30}, 29));
        assertEquals(-1, UnsentRestorePolicy.restoreIndex(new long[]{30, 40, 50}, 29));
    }

    @Test public void oldestLoadedTimestampIsInRange() {
        assertEquals(3, UnsentRestorePolicy.restoreIndex(new long[]{50, 40, 30}, 30));
        assertEquals(1, UnsentRestorePolicy.restoreIndex(new long[]{30, 40, 50}, 30));
    }

    @Test public void needsAKnownDirection() {
        assertEquals(-1, UnsentRestorePolicy.restoreIndex(new long[]{}, 30));
        assertEquals(-1, UnsentRestorePolicy.restoreIndex(new long[]{30}, 30));
        assertEquals(-1, UnsentRestorePolicy.restoreIndex(new long[]{30, 30}, 30));
    }
}
