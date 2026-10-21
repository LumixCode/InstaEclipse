package ps.reso.instaeclipse.utils.media;

/** Local retention starts when a story is captured, independently of Instagram's expiry. */
public final class StoryCacheRetention {
    public static final int DEFAULT_HOURS = 24;
    public static final int MIN_HOURS = 1;
    public static final int MAX_HOURS = 720;
    private static final long MILLIS_PER_HOUR = 60L * 60 * 1000;

    private StoryCacheRetention() {}

    public static int normalize(int hours) {
        return hours >= MIN_HOURS && hours <= MAX_HOURS ? hours : DEFAULT_HOURS;
    }

    public static int fromStored(Object value) {
        return value instanceof Integer hours ? normalize(hours) : DEFAULT_HOURS;
    }

    public static long durationMillis(int hours) {
        return normalize(hours) * MILLIS_PER_HOUR;
    }

    public static boolean expired(long capturedAt, long now, int hours) {
        if (capturedAt <= 0) return true;
        // Wall-clock corrections must not expire a newly captured story or overflow its age.
        return now >= capturedAt && now - capturedAt >= durationMillis(hours);
    }
}
