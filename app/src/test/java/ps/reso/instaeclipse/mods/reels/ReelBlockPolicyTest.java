package ps.reso.instaeclipse.mods.reels;

import org.junit.Test;

import static org.junit.Assert.*;
import ps.reso.instaeclipse.mods.reels.ReelBlockPolicy.Reason;

public class ReelBlockPolicyTest {
    @Test public void requestReasonsUseManualNightDailyPrecedence() {
        assertEquals(Reason.NONE, ReelBlockPolicy.chooseRequestReason(false, false, false));
        assertEquals(Reason.DAILY_LIMIT, ReelBlockPolicy.chooseRequestReason(false, false, true));
        assertEquals(Reason.NIGHTTIME, ReelBlockPolicy.chooseRequestReason(false, true, true));
        assertEquals(Reason.DISABLE_REELS, ReelBlockPolicy.chooseRequestReason(true, true, true));
    }

    @Test public void nighttimeDmExceptionAppliesOnlyToTheOpenedReel() {
        assertEquals(Reason.NONE, ReelBlockPolicy.choosePlaybackReason(true,
                false, false, true, true, false, false));
        assertEquals(Reason.NIGHTTIME, ReelBlockPolicy.choosePlaybackReason(false,
                false, false, true, true, false, false));
        assertEquals(Reason.NIGHTTIME, ReelBlockPolicy.choosePlaybackReason(true,
                false, false, true, false, false, false));
    }

    @Test public void overlappingNighttimeReasonWinsUntilItsWindowEnds() {
        assertEquals(Reason.NIGHTTIME, ReelBlockPolicy.choosePlaybackReason(false,
                false, false, true, false, true, false));
        assertEquals(Reason.DAILY_LIMIT, ReelBlockPolicy.choosePlaybackReason(false,
                false, false, false, false, true, false));
    }

    @Test public void eachReasonUsesItsOwnDmPreference() {
        assertEquals(Reason.NONE, ReelBlockPolicy.choosePlaybackReason(true,
                true, true, true, false, true, false));
        assertEquals(Reason.DISABLE_REELS, ReelBlockPolicy.choosePlaybackReason(true,
                true, false, true, true, true, true));
        assertEquals(Reason.NONE, ReelBlockPolicy.choosePlaybackReason(true,
                false, false, false, false, true, true));
    }
}
