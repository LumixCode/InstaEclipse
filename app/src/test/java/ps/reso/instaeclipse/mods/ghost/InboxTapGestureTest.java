package ps.reso.instaeclipse.mods.ghost;

import org.junit.Test;
import static org.junit.Assert.*;
import static ps.reso.instaeclipse.mods.ghost.InboxTapGesture.Verdict.*;

public class InboxTapGestureTest {
    private static final float SLOP = 21f;      // 8 dp at density 2.625
    private static final long LONG_PRESS = 400L;

    /** One still tap of 60 ms ending at {@code upTime}. */
    private static InboxTapGesture.Verdict tap(InboxTapGesture g, long upTime, boolean inCorner) {
        assertEquals(inCorner && g.guarding(upTime - 60) ? SWALLOW : PASS,
                g.down(100, 150, upTime - 60, inCorner));
        g.during();
        return g.up(102, 151, upTime, upTime - 60, SLOP, LONG_PRESS);
    }

    @Test public void thirdQuickTapOpensThePrompt() {
        InboxTapGesture g = new InboxTapGesture();
        assertEquals(PASS, tap(g, 1000, true));
        assertEquals(PASS, tap(g, 1200, true));
        assertEquals(OPEN, tap(g, 1400, true));
        assertNull(g.takeBreak());
    }

    @Test public void steadyTapsUpTo500MsApartStillCount() {
        // The old rule wanted all three within 800 ms of the first: this sequence opened nothing.
        InboxTapGesture g = new InboxTapGesture();
        tap(g, 1000, true);
        tap(g, 1450, true);
        assertEquals(OPEN, tap(g, 1900, true));
    }

    @Test public void fourthTapRightAfterIsSwallowedWholeAndDoesNotCount() {
        InboxTapGesture g = new InboxTapGesture();
        tap(g, 1000, true);
        tap(g, 1200, true);
        assertEquals(OPEN, tap(g, 1400, true));
        // Before the prompt's window is up, the extra tap would reach Instagram (or the hidden
        // inbox's back control after a quick unlock): every event of it is consumed.
        assertTrue(g.guarding(1600));
        assertEquals(SWALLOW, g.down(100, 150, 1600, true));
        assertEquals(SWALLOW, g.during());
        assertEquals(SWALLOW, g.up(100, 150, 1660, 1600, SLOP, LONG_PRESS));
        // Even a whole fast triple tap inside the guard never opens a second prompt.
        assertEquals(SWALLOW, tap(g, 1900, true));
        assertEquals(SWALLOW, tap(g, 2100, true));
        assertEquals(SWALLOW, tap(g, 2300, true));
    }

    @Test public void guardEndsAfterOneSecondAndCountingStartsFresh() {
        InboxTapGesture g = new InboxTapGesture();
        tap(g, 1000, true);
        tap(g, 1200, true);
        tap(g, 1400, true);
        assertFalse(g.guarding(2400));
        assertEquals(PASS, tap(g, 2500, true));
        assertEquals(PASS, tap(g, 2700, true));
        assertEquals(OPEN, tap(g, 2900, true));
    }

    @Test public void guardOnlyCoversTheCorner() {
        InboxTapGesture g = new InboxTapGesture();
        tap(g, 1000, true);
        tap(g, 1200, true);
        tap(g, 1400, true);
        assertEquals(PASS, g.down(600, 900, 1500, false)); // a chat row: Instagram gets it
        assertEquals(PASS, g.during());
        assertEquals(PASS, g.up(600, 900, 1560, 1500, SLOP, LONG_PRESS));
    }

    @Test public void endGuardLetsCornerTapsThroughAgain() {
        InboxTapGesture g = new InboxTapGesture();
        tap(g, 1000, true);
        tap(g, 1200, true);
        tap(g, 1400, true);
        g.endGuard(); // the prompt could not open (no passcode set)
        assertEquals(PASS, tap(g, 1600, true));
    }

    @Test public void swallowedTouchThatIsCancelledStaysSwallowed() {
        InboxTapGesture g = new InboxTapGesture();
        tap(g, 1000, true);
        tap(g, 1200, true);
        tap(g, 1400, true);
        assertEquals(SWALLOW, g.down(100, 150, 1500, true));
        assertEquals(SWALLOW, g.pointerDown());
        assertEquals(SWALLOW, g.cancel());
        assertEquals(PASS, g.down(600, 900, 1600, false));
    }

    @Test public void touchOutsideTheCornerBreaksTheSequence() {
        InboxTapGesture g = new InboxTapGesture();
        tap(g, 1000, true);
        tap(g, 1200, true);
        assertEquals(PASS, tap(g, 1400, false));
        assertEquals("touch outside the corner after 2 tap(s)", g.takeBreak());
        assertNull(g.takeBreak());
        assertEquals(PASS, tap(g, 1600, true));
        assertEquals(PASS, tap(g, 1800, true));
        assertEquals(OPEN, tap(g, 2000, true));
    }

    @Test public void dragLongPressSecondFingerAndCancelBreakIt() {
        InboxTapGesture g = new InboxTapGesture();
        tap(g, 1000, true);
        g.down(100, 150, 1100, true);
        assertEquals(PASS, g.up(100, 190, 1160, 1100, SLOP, LONG_PRESS));
        assertEquals("drag after 1 tap(s)", g.takeBreak());

        tap(g, 2000, true);
        g.down(100, 150, 2100, true);
        assertEquals(PASS, g.up(100, 150, 2600, 2100, SLOP, LONG_PRESS));
        assertEquals("long press after 1 tap(s)", g.takeBreak());

        tap(g, 3000, true);
        g.down(100, 150, 3100, true);
        assertEquals(PASS, g.pointerDown());
        assertEquals(PASS, g.up(100, 150, 3160, 3100, SLOP, LONG_PRESS));
        assertEquals("second finger after 1 tap(s)", g.takeBreak());

        tap(g, 4000, true);
        g.down(100, 150, 4100, true);
        assertEquals(PASS, g.cancel()); // e.g. the system took it as a back swipe
        assertEquals("touch cancelled after 1 tap(s)", g.takeBreak());
        assertEquals(PASS, tap(g, 4300, true));
        assertEquals(PASS, tap(g, 4500, true));
        assertEquals(OPEN, tap(g, 4700, true));
    }

    @Test public void latePauseIsReportedAndTheLateTapCountsAsTheFirst() {
        InboxTapGesture g = new InboxTapGesture();
        tap(g, 1000, true);
        tap(g, 1200, true);
        assertEquals(PASS, tap(g, 1800, true));
        assertEquals("pause of 600 ms after 2 tap(s)", g.takeBreak());
        tap(g, 2000, true);
        assertEquals(OPEN, tap(g, 2200, true));
    }

    @Test public void nothingIsReportedWithoutAStartedSequence() {
        InboxTapGesture g = new InboxTapGesture();
        assertEquals(PASS, tap(g, 1000, false));
        assertEquals(PASS, g.cancel());
        assertNull(g.takeBreak());
    }

    @Test public void inTouchOnlyForFollowedOrSwallowedTouches() {
        InboxTapGesture g = new InboxTapGesture();
        g.down(600, 900, 1000, false);
        assertFalse(g.inTouch()); // the hook passes this UP without reading ViewConfiguration
        g.down(100, 150, 1100, true);
        assertTrue(g.inTouch());
        g.up(100, 150, 1160, 1100, SLOP, LONG_PRESS);
        assertFalse(g.inTouch());
        tap(g, 1300, true);
        tap(g, 1500, true);
        g.down(100, 150, 1600, true);
        assertTrue(g.inTouch()); // swallowed
        g.up(100, 150, 1660, 1600, SLOP, LONG_PRESS);
        assertFalse(g.inTouch());
    }

    @Test public void cornerTapsOffTheCountOnlyPassThrough() {
        // The hidden inbox (or the inbox off screen) reports every touch as outside the corner.
        InboxTapGesture g = new InboxTapGesture();
        for (long t = 1000; t < 2000; t += 150) assertEquals(PASS, tap(g, t, false));
        assertNull(g.takeBreak());
    }
}
