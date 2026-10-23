package ps.reso.instaeclipse.utils.core;

public final class RestartDecision {

    public enum Outcome { NOT_INSTALLED, RELAUNCH, APP_INFO, MANUAL_TOAST }

    public static final long RELAUNCH_DELAY_MS = 400;

    private RestartDecision() {}

    public static Outcome decide(String pkg, Integer exitCode) {
        if (pkg == null) return Outcome.MANUAL_TOAST;
        return exitCode != null && exitCode == 0 ? Outcome.RELAUNCH : Outcome.APP_INFO;
    }
}
