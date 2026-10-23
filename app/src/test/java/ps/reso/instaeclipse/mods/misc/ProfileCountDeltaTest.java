package ps.reso.instaeclipse.mods.misc;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import org.junit.Test;

import java.util.Locale;

public class ProfileCountDeltaTest {

    @Test
    public void upIsPlusAndDownIsMinus() {
        assertEquals("+12", ProfileCountDelta.label(612, 600, Locale.GERMANY));
        assertEquals("-25", ProfileCountDelta.label(1975, 2000, Locale.GERMANY));
        assertEquals(ProfileCountDelta.GREEN, ProfileCountDelta.color("+12"));
        assertEquals(ProfileCountDelta.RED, ProfileCountDelta.color("-25"));
    }

    @Test
    public void unchangedOrUnknownShowsNothing() {
        assertNull(ProfileCountDelta.label(600, 600, Locale.GERMANY));
        assertNull(ProfileCountDelta.label(600, -1, Locale.GERMANY)); // no baseline
        assertNull(ProfileCountDelta.label(null, 600, Locale.GERMANY)); // count not bound
    }

    @Test
    public void numbersUseTheLocalesGrouping() {
        assertEquals("+12.345", ProfileCountDelta.label(12_345, 0, Locale.GERMANY));
        assertEquals("-12,345", ProfileCountDelta.label(0, 12_345, Locale.US));
        assertEquals("+2.000.000", ProfileCountDelta.label(Integer.MAX_VALUE, Integer.MAX_VALUE - 2_000_000, Locale.GERMANY));
    }

    @Test
    public void seenCountsNeedBothFreshValues() {
        ProfileCountDelta.Seen seen = new ProfileCountDelta.Seen();
        seen.put("7", true, 600, 1000);
        assertNull(seen.counts("7", 2000)); // following not shown yet
        seen.put("7", false, 2000, 1500);
        assertArrayEquals(new int[]{600, 2000}, seen.counts("7", 2000));
        assertNull(seen.counts("8", 2000));
        assertNull(seen.counts("7", 1500 + ProfileCountDelta.MAX_AGE_MS + 1)); // too old for an analysis
        seen.put("7", true, 601, 5000); // the latest bind wins
        assertArrayEquals(new int[]{601, 2000}, seen.counts("7", 5000));
    }

    @Test
    public void seenForgetsTheLeastRecentProfiles() {
        ProfileCountDelta.Seen seen = new ProfileCountDelta.Seen();
        for (int i = 0; i <= ProfileCountDelta.MAX_PROFILES; i++) {
            seen.put(String.valueOf(i), true, i, 10);
            seen.put(String.valueOf(i), false, i, 10);
        }
        assertNull(seen.counts("0", 10));
        assertArrayEquals(new int[]{1, 1}, seen.counts("1", 10));
    }
}
