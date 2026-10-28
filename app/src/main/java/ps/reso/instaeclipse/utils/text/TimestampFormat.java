package ps.reso.instaeclipse.utils.text;

import android.content.Context;

import java.text.DateFormat;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.Objects;
import java.util.function.BiFunction;

import ps.reso.instaeclipse.mods.misc.ExactTimestampFormatter;
import ps.reso.instaeclipse.utils.feature.FeatureFlags;

/**
 * Date/time format for "Show exact dates" (#265): the device's short date + time, one of the
 * presets, or the user's own SimpleDateFormat pattern. Framework-free so it is unit-tested.
 */
public final class TimestampFormat {

    /** Pattern choices; "" (the first) is the device's own short date + time. */
    public static final String[] PRESETS = {
            "",
            "yyyy-MM-dd HH:mm:ss",
            "yyyy-MM-dd HH:mm",
            "dd/MM/yyyy HH:mm",
            "MM/dd/yyyy h:mm a",
            "dd.MM.yyyy HH:mm",
            "d MMM yyyy, HH:mm",
            "EEE, d MMM yyyy HH:mm",
    };

    private static volatile Checked lastChecked;
    private static volatile DialogPattern lastDialogPattern;

    private TimestampFormat() {}

    private static final class Checked {
        final String pattern;
        final boolean valid;

        Checked(String pattern, boolean valid) {
            this.pattern = pattern;
            this.valid = valid;
        }
    }

    private static final class DialogPattern {
        final String custom;
        final Locale locale;
        final boolean use24HourClock;
        final String pattern;

        DialogPattern(String custom, Locale locale, boolean use24HourClock, String pattern) {
            this.custom = custom;
            this.locale = locale;
            this.use24HourClock = use24HourClock;
            this.pattern = pattern;
        }

        boolean matches(String custom, Locale locale, boolean use24HourClock) {
            return this.use24HourClock == use24HourClock && Objects.equals(this.custom, custom) && this.locale.equals(locale);
        }
    }

    public static String dialogDateTime(Context ctx, long millis) {
        return dialogDateFormat(ctx).format(new Date(millis));
    }

    public static SimpleDateFormat dialogDateFormat(Context ctx) {
        return dialogFormat(FeatureFlags.exactTimestampFormat, Locale.getDefault(),
                android.text.format.DateFormat.is24HourFormat(ctx), android.text.format.DateFormat::getBestDateTimePattern);
    }

    static SimpleDateFormat dialogFormat(String custom, Locale locale, boolean use24HourClock, BiFunction<Locale, String, String> best) {
        return new SimpleDateFormat(dialogPattern(custom, locale, use24HourClock, best), locale);
    }

    static String dialogPattern(String custom, Locale locale, boolean use24HourClock, BiFunction<Locale, String, String> best) {
        DialogPattern memo = lastDialogPattern;
        if (memo != null && memo.matches(custom, locale, use24HourClock)) return memo.pattern;
        String pattern = custom != null && !custom.isEmpty() && isValid(custom)
                ? custom : best.apply(locale, use24HourClock ? "yMMMdHm" : "yMMMdhm");
        lastDialogPattern = new DialogPattern(custom, locale, use24HourClock, pattern);
        return pattern;
    }

    /** A pattern SimpleDateFormat accepts (empty = the device default, also valid). */
    public static boolean isValid(String pattern) {
        if (pattern == null) return false;
        if (pattern.isEmpty()) return true;
        if (pattern.length() > 64) return false;
        Checked last = lastChecked;
        if (last != null && last.pattern.equals(pattern)) return last.valid;
        boolean valid;
        try {
            new SimpleDateFormat(pattern, Locale.US).format(new Date(0));
            valid = true;
        } catch (IllegalArgumentException e) {
            valid = false;
        }
        lastChecked = new Checked(pattern, valid);
        return valid;
    }

    /** {@code date} in {@code pattern}; the device's short format when it is empty or invalid. */
    public static String format(String pattern, Date date, Locale locale) {
        if (pattern != null && !pattern.isEmpty() && isValid(pattern)) {
            return new SimpleDateFormat(pattern, locale).format(date);
        }
        return DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT, locale).format(date);
    }

    public static String format(String pattern, Date date, Locale locale, String devicePattern) {
        if (pattern != null && !pattern.isEmpty() && isValid(pattern)) {
            return new SimpleDateFormat(pattern, locale).format(date);
        }
        if (devicePattern != null && !devicePattern.isEmpty() && isValid(devicePattern)) {
            return new SimpleDateFormat(devicePattern, locale).format(date);
        }
        return format(pattern, date, locale);
    }

    public static String formatAsPosts(Context ctx, String pattern, Date date) {
        Locale locale = Locale.getDefault();
        String device = android.text.format.DateFormat.getBestDateTimePattern(locale,
                ExactTimestampFormatter.skeleton(android.text.format.DateFormat.is24HourFormat(ctx)));
        return format(pattern, date, locale, device);
    }
}
