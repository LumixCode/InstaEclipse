package ps.reso.instaeclipse.mods.misc;

import org.junit.Test;

import java.util.Locale;
import java.util.TimeZone;

import static org.junit.Assert.*;

public class ExactTimestampFormatterTest {
    @Test public void convertsSecondsExactlyOnceIncludingFractions() {
        assertEquals(1704067200000L, ExactTimestampFormatter.epochSecondsToMillis(1704067200));
        assertEquals(1704067200125L, ExactTimestampFormatter.epochSecondsToMillis(1704067200.125));
    }

    @Test public void rejectsMissingInvalidAndMillisecondInputs() {
        for (double invalid : new double[]{0, -1, Double.NaN, Double.POSITIVE_INFINITY,
                Double.NEGATIVE_INFINITY, Double.MAX_VALUE, 1704067200000D}) {
            assertEquals(-1, ExactTimestampFormatter.epochSecondsToMillis(invalid));
            assertNull(ExactTimestampFormatter.formatSeconds(invalid, Locale.US,
                    TimeZone.getTimeZone("UTC"), "yyyy-MM-dd HH:mm:ss"));
        }
    }

    @Test public void displaysExactDateTimeInSelectedZone() {
        assertEquals("2024-01-01 00:00:00", format(1704067200, "UTC", "yyyy-MM-dd HH:mm:ss"));
        assertEquals("2023-12-31 16:00:00", format(1704067200, "America/Los_Angeles", "yyyy-MM-dd HH:mm:ss"));
    }

    @Test public void usesDaylightSavingOffsetAtTheTimestamp() {
        assertEquals("2024-07-01 02:00:00", format(1719792000, "Europe/Berlin", "yyyy-MM-dd HH:mm:ss"));
        assertEquals("2024-01-01 01:00:00", format(1704067200, "Europe/Berlin", "yyyy-MM-dd HH:mm:ss"));
    }

    @Test public void formatsLocalizedDateAndTwelveHourTime() {
        assertEquals("1 janvier 2024 00:00:00", ExactTimestampFormatter.formatSeconds(
                1704067200, Locale.FRANCE, TimeZone.getTimeZone("UTC"), "d MMMM yyyy HH:mm:ss"));
        assertEquals("2024-01-01 12:00:00 AM", format(1704067200, "UTC", "yyyy-MM-dd hh:mm:ss a"));
        assertEquals("yMMMdHms", ExactTimestampFormatter.skeleton(true));
        assertEquals("yMMMdhms", ExactTimestampFormatter.skeleton(false));
    }

    @Test public void directMessageMicrosBecomeTimeOnlyWithSeconds() {
        long micros = 1704112496123456L;
        assertEquals("12:34:56", ExactTimestampFormatter.formatMicros(micros, Locale.US,
                TimeZone.getTimeZone("UTC"), "HH:mm:ss"));
        assertEquals("12:34:56 PM", ExactTimestampFormatter.formatMicros(micros, Locale.US,
                TimeZone.getTimeZone("UTC"), "h:mm:ss a"));
        assertEquals("13:34:56", ExactTimestampFormatter.formatMicros(micros, Locale.US,
                TimeZone.getTimeZone("Europe/Berlin"), "HH:mm:ss"));
        assertEquals("Hms", ExactTimestampFormatter.timeSkeleton(true));
        assertEquals("hms", ExactTimestampFormatter.timeSkeleton(false));
    }

    @Test public void customDateFormatReplacesTheDevicePattern() {
        assertEquals("dd.MM.yyyy HH:mm", ExactTimestampFormatter.pattern("dd.MM.yyyy HH:mm", "MMM d, y, HH:mm:ss"));
        assertEquals("01.01.2024 00:00", format(1704067200, "UTC",
                ExactTimestampFormatter.pattern("dd.MM.yyyy HH:mm", "MMM d, y, HH:mm:ss")));
    }

    @Test public void emptyOrMissingDateFormatKeepsTheDevicePatternWithSeconds() {
        assertEquals("MMM d, y, HH:mm:ss", ExactTimestampFormatter.pattern("", "MMM d, y, HH:mm:ss"));
        assertEquals("MMM d, y, HH:mm:ss", ExactTimestampFormatter.pattern(null, "MMM d, y, HH:mm:ss"));
    }

    @Test public void invalidDateFormatKeepsTheDevicePattern() {
        // An unknown pattern letter, an unterminated quote and an over-long pattern are refused.
        for (String invalid : new String[]{"yyyy-MM-dd bb", "'unterminated", "y".repeat(65)}) {
            assertEquals("MMM d, y, HH:mm:ss", ExactTimestampFormatter.pattern(invalid, "MMM d, y, HH:mm:ss"));
        }
    }

    @Test public void invalidDirectMessageMicrosKeepNativeResult() {
        for (long invalid : new long[]{0, -1, Long.MAX_VALUE}) {
            assertNull(ExactTimestampFormatter.formatMicros(invalid, Locale.US,
                    TimeZone.getTimeZone("UTC"), "HH:mm:ss"));
        }
    }

    private static String format(double seconds, String zone, String pattern) {
        return ExactTimestampFormatter.formatSeconds(seconds, Locale.US, TimeZone.getTimeZone(zone), pattern);
    }
}
