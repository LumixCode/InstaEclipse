package ps.reso.instaeclipse.utils.core;

public final class RestartDecision {

    public enum Outcome { NOT_INSTALLED, RELAUNCH, APP_INFO, NOT_RUNNING }

    public static final long RELAUNCH_DELAY_MS = 400;

    private RestartDecision() {}

    public static Outcome decide(String pkg, boolean ack, boolean stillRunning, boolean suAvailable, Integer suExit) {
        return unreachable(pkg, suAvailable, suExit);
    }

    public static Outcome unreachable(String pkg, boolean suAvailable, Integer suExit) {
        if (pkg == null) return Outcome.NOT_INSTALLED;
        return suAvailable && suExit != null && suExit == 0 ? Outcome.RELAUNCH : Outcome.APP_INFO;
    }
}
