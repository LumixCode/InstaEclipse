package ps.reso.instaeclipse.mods.ghost;

import org.junit.Test;
import static org.junit.Assert.*;

public class QuickTapSequenceTest {
    @Test public void thirdQuickTapTriggersOnce() {
        QuickTapSequence taps = new QuickTapSequence();
        assertFalse(taps.tap(1000));
        assertFalse(taps.tap(1250));
        assertTrue(taps.tap(1500));
        assertFalse(taps.tap(1600));
    }

    @Test public void slowSequenceStartsAgain() {
        QuickTapSequence taps = new QuickTapSequence();
        assertFalse(taps.tap(1000));
        assertFalse(taps.tap(1300));
        assertFalse(taps.tap(1900));
        assertFalse(taps.tap(2150));
        assertTrue(taps.tap(2300));
    }

    @Test public void cancellationOrOutsideTapBreaksSequence() {
        QuickTapSequence taps = new QuickTapSequence();
        taps.tap(1000);
        taps.tap(1200);
        taps.reset();
        assertFalse(taps.tap(1300));
        assertFalse(taps.tap(1500));
        assertTrue(taps.tap(1600));
    }

    @Test public void defaultRuleStillWantsAllThreeWithin800Ms() {
        // ThreadHeaderMenu's 400 ms replay delay is tuned to this; withGap is the inbox's rule.
        QuickTapSequence taps = new QuickTapSequence();
        assertFalse(taps.tap(0));
        assertFalse(taps.tap(450));
        assertFalse(taps.tap(900));
        assertEquals(1, taps.count());
    }

    @Test public void gapRuleAcceptsASteadyTripleTap() {
        QuickTapSequence taps = QuickTapSequence.withGap(500);
        assertFalse(taps.tap(0));
        assertFalse(taps.tap(450));
        assertTrue(taps.tap(900));
        assertEquals(0, taps.count());
    }

    @Test public void gapRuleRestartsAtALatePauseAndKeepsCounting() {
        QuickTapSequence taps = QuickTapSequence.withGap(500);
        assertFalse(taps.tap(0));
        assertFalse(taps.tap(300));
        assertFalse(taps.tap(900)); // 600 ms pause: this tap is the new first
        assertEquals(1, taps.count());
        assertEquals(900, taps.lastTap());
        assertFalse(taps.tap(1200));
        assertTrue(taps.tap(1500));
    }

    @Test public void gapRuleBoundaryIsInclusive() {
        QuickTapSequence taps = QuickTapSequence.withGap(500);
        taps.tap(0);
        taps.tap(500);
        assertTrue(taps.tap(1000));
        taps.tap(2000);
        assertFalse(taps.tap(2501));
        assertEquals(1, taps.count());
    }

    @Test public void repeatedUnlocksCanTriggerNewSequences() {
        QuickTapSequence taps = new QuickTapSequence();
        for (int start : new int[]{0, 5000, 10000}) {
            assertFalse(taps.tap(start));
            assertFalse(taps.tap(start + 100));
            assertTrue(taps.tap(start + 200));
        }
    }
}
