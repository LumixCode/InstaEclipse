package ps.reso.instaeclipse.mods.media;

import org.junit.Test;

import static org.junit.Assert.*;

public class PlaybackSpeedHoldGateTest {
    private static final float SLOW = PlaybackSpeedHook.SLOW;

    @Test public void leftHoldOnReelSlowsTwoXUntilRelease() {
        PlaybackSpeedHook.HoldGate gate = new PlaybackSpeedHook.HoldGate();
        gate.down(true);
        assertEquals(SLOW, gate.speedFor(2f, false, 0), 0f);
        assertTrue(gate.slowActive());
        assertEquals(SLOW, gate.speedFor(2f, true, 10), 0f); // the same hold reaching the Hero setter
        gate.up();
        assertFalse(gate.slowActive());
        assertEquals(2f, gate.speedFor(2f, false, 20), 0f);
    }

    @Test public void tapOnTwoXControlAfterLeftTouchKeepsTwoX() {
        // a voice message pill or IG's speed sheet: the click runs after ACTION_UP
        PlaybackSpeedHook.HoldGate gate = new PlaybackSpeedHook.HoldGate();
        gate.down(true);
        gate.up();
        assertEquals(2f, gate.speedFor(2f, false, 0), 0f);
        assertEquals(2f, gate.speedFor(2f, true, 0), 0f);
        assertFalse(gate.slowActive());
    }

    @Test public void touchOffTheLeftHalfOfAReelNeverSlows() {
        PlaybackSpeedHook.HoldGate gate = new PlaybackSpeedHook.HoldGate();
        gate.down(false);
        assertEquals(2f, gate.speedFor(2f, false, 0), 0f);
        assertEquals(2f, gate.speedFor(2f, true, 0), 0f);
        assertFalse(gate.slowActive());
    }

    @Test public void ownTwoXReachingHeroAfterHopIsNotRewrittenDuringLeftTouch() {
        PlaybackSpeedHook.HoldGate gate = new PlaybackSpeedHook.HoldGate();
        gate.down(true); // finger still on the left half while a video starts at the chosen 2x
        gate.own(2f, 1000);
        assertEquals(2f, gate.speedFor(2f, true, 1050), 0f);
        assertFalse(gate.slowActive());
        // IG's own hold through a player wrapper is still slowed (own calls there run under APPLYING)
        assertEquals(SLOW, gate.speedFor(2f, false, 1100), 0f);
    }

    @Test public void ownRequestWindowExpires() {
        PlaybackSpeedHook.HoldGate gate = new PlaybackSpeedHook.HoldGate();
        gate.own(2f, 1000);
        gate.down(true);
        assertEquals(SLOW, gate.speedFor(2f, true, 1000 + PlaybackSpeedHook.HoldGate.OWN_MS + 1), 0f);
    }

    @Test public void ownRequestOfAnotherSpeedDoesNotShieldTwoX() {
        PlaybackSpeedHook.HoldGate gate = new PlaybackSpeedHook.HoldGate();
        gate.own(1.5f, 1000);
        gate.down(true);
        assertEquals(SLOW, gate.speedFor(2f, true, 1010), 0f);
    }

    @Test public void oneXResetEndsSlowMotionWhileStillHeld() {
        PlaybackSpeedHook.HoldGate gate = new PlaybackSpeedHook.HoldGate();
        gate.down(true);
        gate.speedFor(2f, false, 0);
        assertEquals(1f, gate.speedFor(1f, false, 10), 0f);
        assertFalse(gate.slowActive());
    }

    @Test public void nextTouchDownEndsAnUnreleasedHold() {
        PlaybackSpeedHook.HoldGate gate = new PlaybackSpeedHook.HoldGate();
        gate.down(true);
        gate.speedFor(2f, false, 0);
        gate.down(false); // the UP never arrived
        assertFalse(gate.slowActive());
        assertEquals(2f, gate.speedFor(2f, false, 10), 0f);
    }

    @Test public void otherSpeedsPassThroughDuringHold() {
        PlaybackSpeedHook.HoldGate gate = new PlaybackSpeedHook.HoldGate();
        gate.down(true);
        assertEquals(1.5f, gate.speedFor(1.5f, false, 0), 0f);
        assertEquals(3f, gate.speedFor(3f, true, 0), 0f);
        assertEquals(SLOW, gate.speedFor(SLOW, true, 0), 0f);
        assertFalse(gate.slowActive());
    }
}
