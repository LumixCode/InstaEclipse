package ps.reso.instaeclipse.mods.reels;

import org.junit.Test;

import ps.reso.instaeclipse.utils.feature.FeatureFlags;

import static org.junit.Assert.*;

public class NightReelScheduleTest {
    @Test public void wrappedScheduleCrossesMidnightWithExactBoundaries() {
        int start = 22 * 60;
        int end = 7 * 60;
        assertFalse(NightReelSchedule.isActiveAt(start - 1, start, end));
        assertTrue(NightReelSchedule.isActiveAt(start, start, end));
        assertTrue(NightReelSchedule.isActiveAt(23 * 60 + 59, start, end));
        assertTrue(NightReelSchedule.isActiveAt(0, start, end));
        assertTrue(NightReelSchedule.isActiveAt(end - 1, start, end));
        assertFalse(NightReelSchedule.isActiveAt(end, start, end));
    }

    @Test public void sameDayScheduleUsesTheSameBoundaryContract() {
        int start = 1 * 60;
        int end = 6 * 60;
        assertFalse(NightReelSchedule.isActiveAt(start - 1, start, end));
        assertTrue(NightReelSchedule.isActiveAt(start, start, end));
        assertTrue(NightReelSchedule.isActiveAt(end - 1, start, end));
        assertFalse(NightReelSchedule.isActiveAt(end, start, end));
    }

    @Test public void equalOrInvalidTimesAreInactive() {
        assertFalse(NightReelSchedule.isActiveAt(0, 0, 0));
        assertFalse(NightReelSchedule.isActiveAt(-1, 1320, 420));
        assertFalse(NightReelSchedule.isActiveAt(0, -1, 420));
        assertFalse(NightReelSchedule.isActiveAt(0, 1320, 1440));
        assertEquals(1320, NightReelSchedule.validMinutes(-1, 1320));
        assertEquals(420, NightReelSchedule.validMinutes(420, 1320));
    }

    @Test public void enabledScheduleIsIgnoredByTheFullDisablePrecedence() {
        boolean originalEnabled = FeatureFlags.nightReelBlockEnabled;
        boolean originalDisabled = FeatureFlags.disableReels;
        int originalStart = FeatureFlags.nightReelBlockStartMinutes;
        int originalEnd = FeatureFlags.nightReelBlockEndMinutes;
        try {
            int now = NightReelSchedule.currentMinutes();
            FeatureFlags.nightReelBlockStartMinutes = (now + 23 * 60) % (24 * 60);
            FeatureFlags.nightReelBlockEndMinutes = (now + 60) % (24 * 60);
            FeatureFlags.nightReelBlockEnabled = true;
            FeatureFlags.disableReels = false;
            assertTrue(NightReelSchedule.isActive());
            FeatureFlags.disableReels = true;
            assertFalse(NightReelSchedule.isActive());
            FeatureFlags.disableReels = false;
            FeatureFlags.nightReelBlockEnabled = false;
            assertFalse(NightReelSchedule.isActive());
        } finally {
            FeatureFlags.nightReelBlockEnabled = originalEnabled;
            FeatureFlags.disableReels = originalDisabled;
            FeatureFlags.nightReelBlockStartMinutes = originalStart;
            FeatureFlags.nightReelBlockEndMinutes = originalEnd;
        }
    }
}
