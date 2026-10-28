package ps.reso.instaeclipse.utils.core;

import android.content.Context;

import java.util.Arrays;
import java.util.List;

public class CommonUtils {
    public static final String IG_PACKAGE_NAME = "com.instagram.android";
    public static final String MY_PACKAGE_NAME = "ps.reso.instaeclipse";

    // In-app log viewer IPC
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

    /** All Instagram packages this module hooks into. */
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

    /**
     * Returns a human-readable variant label for a package name.
     * "com.instagram.android" → "Official"
     * "com.instaflow.android" → "Instaflow"
     * "cc.honista.app"        → "Honista"
     */
    public static String getVariantLabel(String packageName) {
        if (IG_PACKAGE_NAME.equals(packageName)) return "Official";
        String[] parts = packageName.split("\\.");
        // Use the most descriptive segment: skip generic TLDs and short segments
        String best = parts.length >= 2 ? parts[1] : packageName;
        // If second segment is very short or generic, try third
        if (best.length() <= 2 && parts.length >= 3) best = parts[2];
        return Character.toUpperCase(best.charAt(0)) + best.substring(1).toLowerCase();
    }

    /**
     * The companion's instaeclipse_cache prefs, opened MODE_WORLD_READABLE so Instagram can read
     * it through XSharedPreferences. Vector (xposedsharedprefs) allows that mode and keeps the
     * file under /data/misc/<id>/prefs; MODE_PRIVATE made it 0660 there, unreadable from
     * Instagram. Without Vector the platform rejects the mode, so fall back to MODE_PRIVATE.
     * Every companion-side open must go through here: a process keeps the first mode it used.
     */
    @SuppressWarnings("deprecation")
    public static android.content.SharedPreferences companionCache(Context context) {
        try {
            return context.getSharedPreferences("instaeclipse_cache", Context.MODE_WORLD_READABLE);
        } catch (SecurityException e) {
            return context.getSharedPreferences("instaeclipse_cache", Context.MODE_PRIVATE);
        }
    }

    /**
     * Keys that builds before 0.8 left in instaeclipse_cache: Instagram's lock passcode hash,
     * salt and locked-chat list (PrefsMerge keeps them out now), the old names of the share-sheet
     * switch and of the location switch folded into "hideOnboardingPrompts", and extras that were
     * never settings. Kept, they ride along in backups.
     */
    public static final java.util.Set<String> STALE_CACHE_KEYS = new java.util.HashSet<>(java.util.Arrays.asList(
            "lockDirectPasscode", "lockDirectSalt", "lockedDirectThreads", "hideCreateGroupButton", "blockLocationPrompts",
            "loadedModuleVersion", "dailyReelHooksAvailable", "dailyReelTrackingAvailable",
            "dailyReelBlockingAvailable", "isGhostModeEnabled", "isMiscEnabled", "isDistractionFree",
            "homePinnedShortcuts", "backgroundAccessAsked", "pathJustSetLocally", "themeLightMigratedV1",
            "dailyReelsWatchedToday", "dailyReelUsageDate"));

    /** Drops {@link #STALE_CACHE_KEYS} from the companion cache; writes nothing when none is there. */
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

    /*
    Dev Purposes
    public static final String USER_SESSION_CLASS = "com.instagram.common.session.UserSession";
    */
}
