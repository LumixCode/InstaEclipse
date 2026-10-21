package ps.reso.instaeclipse.mods.reels;

import java.time.LocalTime;

import ps.reso.instaeclipse.utils.feature.FeatureFlags;

/** Phone-local quiet hours. Wrapped ranges cross midnight; equal endpoints are inactive. */
public final class NightReelSchedule {
    public static final int DEFAULT_START_MINUTES = 22 * 60;
    public static final int DEFAULT_END_MINUTES = 7 * 60;

    private NightReelSchedule() {}

    public static boolean isActive() {
        return FeatureFlags.nightReelBlockEnabled && !FeatureFlags.disableReels
                && isActiveAt(currentMinutes(), FeatureFlags.nightReelBlockStartMinutes,
                FeatureFlags.nightReelBlockEndMinutes);
    }

    public static boolean isActiveAt(int nowMinutes, int startMinutes, int endMinutes) {
        if (!isValidMinutes(nowMinutes) || !isValidMinutes(startMinutes)
                || !isValidMinutes(endMinutes) || startMinutes == endMinutes) return false;
        if (startMinutes < endMinutes) {
            return nowMinutes >= startMinutes && nowMinutes < endMinutes;
        }
        return nowMinutes >= startMinutes || nowMinutes < endMinutes;
    }

    public static int validMinutes(int value, int fallback) {
        return isValidMinutes(value) ? value : fallback;
    }

    public static int currentMinutes() {
        LocalTime now = LocalTime.now();
        return now.getHour() * 60 + now.getMinute();
    }

    private static boolean isValidMinutes(int value) {
        return value >= 0 && value < 24 * 60;
    }
}
