package ps.reso.instaeclipse.mods.misc;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.TimeZone;

import ps.reso.instaeclipse.utils.text.TimestampFormat;

/** The host's absolute timestamp formatters accept epoch seconds, including their long overloads. */
final class ExactTimestampFormatter {
    private ExactTimestampFormatter() {}

    static String skeleton(boolean use24HourClock) {
        return use24HourClock ? "yMMMdHms" : "yMMMdhms";
    }

    static String timeSkeleton(boolean use24HourClock) {
        return use24HourClock ? "Hms" : "hms";
    }

    /**
     * The pattern for post, comment and story dates: the user's "Date format" setting when it is
     * set and valid, otherwise {@code best}, the device's pattern for {@link #skeleton}. The DM
     * swipe time keeps its time-only skeleton and never uses this.
     */
    static String pattern(String custom, String best) {
        return custom != null && !custom.isEmpty() && TimestampFormat.isValid(custom) ? custom : best;
    }

    static long epochSecondsToMillis(double seconds) {
        // Reject missing timestamps and contemporary millisecond inputs rather than
        // displaying a misleading date. Instagram cannot contain dates beyond year 9999.
        if (!Double.isFinite(seconds) || seconds <= 0 || seconds > 253402300799D) return -1;
        return (long) (seconds * 1000D);
    }

    static String formatSeconds(double seconds, Locale locale, TimeZone zone, String pattern) {
        long millis = epochSecondsToMillis(seconds);
        if (millis < 0) return null;
        return formatMillis(millis, locale, zone, pattern);
    }

    static String formatMicros(long micros, Locale locale, TimeZone zone, String pattern) {
        if (micros <= 0 || micros > 253402300799999999L) return null;
        return formatMillis(micros / 1000L, locale, zone, pattern);
    }

    private static String formatMillis(long millis, Locale locale, TimeZone zone, String pattern) {
        SimpleDateFormat format = new SimpleDateFormat(pattern, locale);
        format.setTimeZone(zone);
        return format.format(new Date(millis));
    }
}
