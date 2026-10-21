package ps.reso.instaeclipse.utils.media;

import org.junit.Test;

import static org.junit.Assert.*;

public class StoryCacheRetentionTest {
    private static final long HOUR = 3_600_000L;
    private static final long CAPTURED = 1_700_000_000_000L;

    @Test public void normalizesInvalidStoredValuesToTheExistingDefault() {
        for (int hours : new int[]{Integer.MIN_VALUE, -1, 0, 721, Integer.MAX_VALUE}) {
            assertEquals(24, StoryCacheRetention.normalize(hours));
            assertEquals(24 * HOUR, StoryCacheRetention.durationMillis(hours));
        }
    }

    @Test public void preservesValidHoursIncludingBothBounds() {
        for (int hours : new int[]{1, 6, 24, 72, 168, 720}) {
            assertEquals(hours, StoryCacheRetention.normalize(hours));
        }
    }

    @Test public void malformedStoredTypesUseDefaultWithoutCastingOrTruncation() {
        for (Object value : new Object[]{null, true, false, "72", "invalid", 72L, 72.0f,
                Long.MAX_VALUE, (short) 72}) {
            assertEquals(24, StoryCacheRetention.fromStored(value));
        }
    }

    @Test public void storedIntegersRetainValidChoicesAndNormalizeInvalidChoices() {
        assertEquals(1, StoryCacheRetention.fromStored(1));
        assertEquals(168, StoryCacheRetention.fromStored(168));
        assertEquals(720, StoryCacheRetention.fromStored(720));
        assertEquals(24, StoryCacheRetention.fromStored(0));
        assertEquals(24, StoryCacheRetention.fromStored(721));
        assertEquals(24, StoryCacheRetention.fromStored(Integer.MAX_VALUE));
    }

    @Test public void maximumDurationDoesNotOverflowAnInteger() {
        assertEquals(2_592_000_000L, StoryCacheRetention.durationMillis(720));
    }

    @Test public void expiresExactlyWhenSelectedDurationHasElapsed() {
        assertFalse(StoryCacheRetention.expired(CAPTURED, CAPTURED + 24 * HOUR - 1, 24));
        assertTrue(StoryCacheRetention.expired(CAPTURED, CAPTURED + 24 * HOUR, 24));
        assertTrue(StoryCacheRetention.expired(CAPTURED, CAPTURED + 24 * HOUR + 1, 24));
    }

    @Test public void shorteningDurationAppliesToAlreadyCapturedStories() {
        long now = CAPTURED + 12 * HOUR;
        assertFalse(StoryCacheRetention.expired(CAPTURED, now, 24));
        assertTrue(StoryCacheRetention.expired(CAPTURED, now, 6));
    }

    @Test public void extendingDurationKeepsOlderStoriesUntilTheNewDeadline() {
        long now = CAPTURED + 36 * HOUR;
        assertTrue(StoryCacheRetention.expired(CAPTURED, now, 24));
        assertFalse(StoryCacheRetention.expired(CAPTURED, now, 72));
    }

    @Test public void backwardClockMovementDoesNotExpireCapturedStories() {
        assertFalse(StoryCacheRetention.expired(CAPTURED, CAPTURED - HOUR, 1));
        assertFalse(StoryCacheRetention.expired(CAPTURED, Long.MIN_VALUE, 1));
    }

    @Test public void invalidCaptureTimeExpiresInsteadOfBeingKeptForever() {
        assertTrue(StoryCacheRetention.expired(0, CAPTURED, 720));
        assertTrue(StoryCacheRetention.expired(-1, CAPTURED, 720));
        assertTrue(StoryCacheRetention.expired(Long.MIN_VALUE, CAPTURED, 720));
    }

    @Test public void extremeClockValuesDoNotWrapExpiryCalculations() {
        assertFalse(StoryCacheRetention.expired(Long.MAX_VALUE - HOUR, Long.MAX_VALUE, 24));
        assertTrue(StoryCacheRetention.expired(1, Long.MAX_VALUE, 720));
    }
}
