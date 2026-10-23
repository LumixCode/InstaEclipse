package ps.reso.instaeclipse.utils.version;

public final class UpdateCheckPolicy {

    private UpdateCheckPolicy() {}

    public static boolean shouldCheck(long now, long lastCheck, boolean debug) {
        return true;
    }

    public static boolean shouldPrompt(String latest, String installed, String skipped) {
        return latest != null && VersionComparator.compare(latest, installed) > 0;
    }
}
