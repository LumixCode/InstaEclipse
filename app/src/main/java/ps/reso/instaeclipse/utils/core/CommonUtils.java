package ps.reso.instaeclipse.utils.core;

import android.content.Context;

import java.util.Arrays;
import java.util.List;

public class CommonUtils {
    public static final String IG_PACKAGE_NAME = "com.instagram.android";
    public static final String MY_PACKAGE_NAME = "ps.reso.instaeclipse";

    public static final String ACTION_REQUEST_LOGS = "ps.reso.instaeclipse.ACTION_REQUEST_LOGS";
    public static final String ACTION_LOGS_REPLY = "ps.reso.instaeclipse.ACTION_LOGS_REPLY";
    public static final String ACTION_CLEAR_LOGS = "ps.reso.instaeclipse.ACTION_CLEAR_LOGS";
    public static final String EXTRA_LOG_TEXT = "log_text";
    public static final String EXTRA_LOG_SOURCE = "source_package";
    public static final String EXTRA_LOG_ERROR = "error";

    public static final String ACTION_APPLY_SETTINGS = "ps.reso.instaeclipse.ACTION_APPLY_SETTINGS";
    public static final String ACTION_RESTART_INSTAGRAM = "ps.reso.instaeclipse.ACTION_RESTART_INSTAGRAM";
    public static final String ACTION_RESTART_ACK = "ps.reso.instaeclipse.ACTION_RESTART_ACK";
    public static final String ACTION_REQUEST_PREFS = "ps.reso.instaeclipse.ACTION_REQUEST_PREFS";
    public static final String EXTRA_REQUEST_TIME = "request_time";

    public static final List<String> SUPPORTED_PACKAGES = Arrays.asList(
            "com.instagram.android",
            "com.instagold.android",
            "com.instaflux.app",
            "com.myinsta.android",
            "cc.honista.app",
            "com.instaprime.android",
            "com.instafel.android",
            "com.instadm.android",
            "com.dfistagram.android",
            "com.Instander.android",
            "com.aero.instagram",
            "com.instapro.android",
            "com.instaflow.android",
            "com.instagram1.android",
            "com.instagram2.android",
            "com.instagramclone.android",
            "com.instaclone.android"
    );

    public static String getVariantLabel(String packageName) {
        if (IG_PACKAGE_NAME.equals(packageName)) return "Official";
        String[] parts = packageName.split("\\.");
        String best = parts.length >= 2 ? parts[1] : packageName;
        if (best.length() <= 2 && parts.length >= 3) best = parts[2];
        return Character.toUpperCase(best.charAt(0)) + best.substring(1).toLowerCase();
    }

    @SuppressWarnings("deprecation")
    public static android.content.SharedPreferences companionCache(Context context) {
        try {
            return context.getSharedPreferences("instaeclipse_cache", Context.MODE_WORLD_READABLE);
        } catch (SecurityException e) {
            return context.getSharedPreferences("instaeclipse_cache", Context.MODE_PRIVATE);
        }
    }

    public static final java.util.Set<String> STALE_CACHE_KEYS = new java.util.HashSet<>(java.util.Arrays.asList(
            "lockDirectPasscode", "lockDirectSalt", "lockedDirectThreads", "hideCreateGroupButton", "blockLocationPrompts",
            "loadedModuleVersion", "dailyReelHooksAvailable", "dailyReelTrackingAvailable",
            "dailyReelBlockingAvailable", "isGhostModeEnabled", "isMiscEnabled", "isDistractionFree",
            "homePinnedShortcuts", "backgroundAccessAsked", "pathJustSetLocally", "themeLightMigratedV1",
            "dailyReelsWatchedToday", "dailyReelUsageDate"));

    public static void dropStaleCacheKeys(Context context) {
        try {
            android.content.SharedPreferences cache = companionCache(context);
            android.content.SharedPreferences.Editor e = null;
            for (String key : STALE_CACHE_KEYS) {
                if (!cache.contains(key)) continue;
                if (e == null) e = cache.edit();
                e.remove(key);
            }
            if (e != null) e.apply();
        } catch (Throwable ignored) {}
    }
}
