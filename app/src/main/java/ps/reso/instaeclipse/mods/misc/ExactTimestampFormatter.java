package ps.reso.instaeclipse.mods.misc;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.TimeZone;
import java.util.function.BiFunction;

import ps.reso.instaeclipse.utils.text.TimestampFormat;

/** The host's absolute timestamp formatters accept epoch seconds, including their long overloads. */
final class ExactTimestampFormatter {
    private static final int FORMATS_PER_THREAD = 4;
    private static volatile PatternMemo lastPattern, lastTimePattern;
    private static final ThreadLocal<Map<String, SimpleDateFormat>> FORMATS = ThreadLocal.withInitial(() ->
            new LinkedHashMap<String, SimpleDateFormat>(8, 0.75f, true) {
                @Override protected boolean removeEldestEntry(Map.Entry<String, SimpleDateFormat> eldest) {
                    return size() > FORMATS_PER_THREAD;
                }
            });

    private ExactTimestampFormatter() {}

    private static final class PatternMemo {
        final String custom;
        final Locale locale;
        final boolean use24HourClock;
        final String pattern;

        PatternMemo(String custom, Locale locale, boolean use24HourClock, String pattern) {
            this.custom = custom;
            this.locale = locale;
            this.use24HourClock = use24HourClock;
            this.pattern = pattern;
        }

        boolean matches(String custom, Locale locale, boolean use24HourClock) {
            return this.use24HourClock == use24HourClock && Objects.equals(this.custom, custom) && this.locale.equals(locale);
        }
    }

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
    static String pattern(String custom, Locale locale, boolean use24HourClock, BiFunction<Locale, String, String> best) {
        PatternMemo memo = lastPattern;
        if (memo != null && memo.matches(custom, locale, use24HourClock)) return memo.pattern;
        String pattern = custom != null && !custom.isEmpty() && TimestampFormat.isValid(custom)
                ? custom : best.apply(locale, skeleton(use24HourClock));
        lastPattern = new PatternMemo(custom, locale, use24HourClock, pattern);
        return pattern;
    }

    static String timePattern(Locale locale, boolean use24HourClock, BiFunction<Locale, String, String> best) {
        PatternMemo memo = lastTimePattern;
        if (memo != null && memo.matches(null, locale, use24HourClock)) return memo.pattern;
        String pattern = best.apply(locale, timeSkeleton(use24HourClock));
        lastTimePattern = new PatternMemo(null, locale, use24HourClock, pattern);
        return pattern;
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
        return format(pattern, locale, zone).format(new Date(millis));
    }

    static SimpleDateFormat format(String pattern, Locale locale, TimeZone zone) {
        String key = pattern + '\u0000' + locale + '\u0000' + zone.getID();
        Map<String, SimpleDateFormat> formats = FORMATS.get();
        SimpleDateFormat format = formats.get(key);
        if (format == null) {
            format = new SimpleDateFormat(pattern, locale);
            format.setTimeZone(zone);
            formats.put(key, format);
        }
        return format;
    }
}
