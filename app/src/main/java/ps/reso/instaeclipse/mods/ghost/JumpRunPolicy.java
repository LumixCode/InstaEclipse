package ps.reso.instaeclipse.mods.ghost;

final class JumpRunPolicy {

    static final long CAP_MS = 5 * 60_000L;
    static final long QUIET_MS = 3000L;

    enum Request { START, STOP, REPLACE }

    enum Step { SCROLL, CANCELLED, REACHED, CAPPED }

    private JumpRunPolicy() {}

    static Request request(boolean running, boolean export) {
        if (!running) return Request.START;
        return export ? Request.REPLACE : Request.STOP;
    }

    static Step step(boolean listShown, String startThread, String openThread, long runningMs, long quietMs) {
        if (!listShown || changed(startThread, openThread)) return Step.CANCELLED;
        if (runningMs > CAP_MS) return Step.CAPPED;
        if (quietMs > QUIET_MS) return Step.REACHED;
        return Step.SCROLL;
    }

    static boolean changed(String startThread, String openThread) {
        return startThread != null && openThread != null && !startThread.equals(openThread);
    }

    static String exportThread(Step end, String startThread) {
        return end == Step.REACHED || end == Step.CAPPED ? startThread : null;
    }
}
