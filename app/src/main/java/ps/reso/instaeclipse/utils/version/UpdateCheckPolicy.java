package ps.reso.instaeclipse.utils.version;

import java.util.concurrent.TimeUnit;

public final class UpdateCheckPolicy {

    public static final long INTERVAL_MS = TimeUnit.HOURS.toMillis(24);

    private UpdateCheckPolicy() {}

    public static boolean shouldCheck(long now, long lastCheck, boolean debug) {
        if (debug) return false;
        return lastCheck <= 0 || now < lastCheck || now - lastCheck >= INTERVAL_MS;
    }

    public static boolean shouldPrompt(String latest, String installed, String skipped) {
        if (latest == null || VersionComparator.compare(latest, installed) <= 0) return false;
        return skipped == null || VersionComparator.compare(latest, skipped) > 0;
    }
}
