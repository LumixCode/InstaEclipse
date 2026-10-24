package ps.reso.instaeclipse.utils.core;

final class PullThrottle {

    static final long MIN_INTERVAL_MS = 1500;

    private boolean armed;
    private long last;

    boolean due(long now) {
        return !armed || now - last >= MIN_INTERVAL_MS;
    }

    void read(long now, boolean empty) {
        if (empty) return;
        armed = true;
        last = now;
    }

    void reset() {
        armed = false;
        last = 0;
    }
}
