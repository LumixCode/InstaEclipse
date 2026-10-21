package ps.reso.instaeclipse.mods.reels;

import android.content.Context;
import android.content.SharedPreferences;

import java.time.LocalDate;

import ps.reso.instaeclipse.utils.feature.FeatureFlags;

public final class DailyReelLimit {
    private static SharedPreferences prefs;
    private static DailyReelUsage usage;

    private DailyReelLimit() {}

    public static synchronized void init(Context context) {
        if (prefs != null) return;
        // Usage is separate from exportable settings so restoring settings cannot reset the day.
        prefs = context.getSharedPreferences("instaeclipse_reel_usage", Context.MODE_PRIVATE);
        usage = new DailyReelUsage(prefs.getLong("day", today()), prefs.getInt("count", 0),
                prefs.getString("lastMediaId", null));
        refreshDay();
    }

    public static synchronized int recordView(String mediaId) {
        if (usage == null) return -1;
        refreshDay();
        if (isActive() && usage.blocksPlayback(mediaId, FeatureFlags.dailyReelLimit)) return -1;
        if (!usage.record(mediaId, today())) return -1;
        // Impression deduplication prevents loop spam without suppressing a later allowance's warnings.
        int warning = isActive() ? usage.warningRemaining(FeatureFlags.dailyReelLimit) : -1;
        save();
        return warning;
    }

    public static boolean isActive() {
        return FeatureFlags.dailyReelLimitEnabled && !FeatureFlags.disableReels;
    }

    public static synchronized int watchedToday() {
        refreshDay();
        return usage == null ? 0 : usage.count;
    }

    public static synchronized boolean isInitialized() {
        return usage != null;
    }

    public static synchronized boolean shouldBlockRequests() {
        if (!isActive()) return false;
        refreshDay();
        return usage != null
                && usage.reached(FeatureFlags.dailyReelLimit);
    }

    public static synchronized boolean shouldBlockPlayback(String mediaId) {
        if (!isActive()) return false;
        refreshDay();
        return usage != null
                && usage.blocksPlayback(mediaId, FeatureFlags.dailyReelLimit);
    }

    public static int validLimit(int value) {
        return DailyReelUsage.validLimit(value);
    }

    private static long today() {
        return LocalDate.now().toEpochDay();
    }

    private static void refreshDay() {
        if (usage != null && usage.rollover(today())) save();
    }

    private static void save() {
        prefs.edit().putLong("day", usage.day).putInt("count", usage.count)
                .putString("lastMediaId", usage.lastMediaId).apply();
    }
}
