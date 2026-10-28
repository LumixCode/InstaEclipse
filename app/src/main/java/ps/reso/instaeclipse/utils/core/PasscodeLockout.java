package ps.reso.instaeclipse.utils.core;

import android.content.SharedPreferences;

import java.util.function.LongSupplier;

public final class PasscodeLockout {

    public static final int MAX_FAILURES = 5;
    public static final long LOCKOUT_MS = 30_000L;

    private final LongSupplier clock;
    private int failures;
    private long lockedUntil;

    public PasscodeLockout(SharedPreferences store, LongSupplier clock) {
        this.clock = clock;
    }

    public synchronized int secondsLeft() {
        long left = lockedUntil - clock.getAsLong();
        return left > 0 ? (int) ((left + 999) / 1000) : 0;
    }

    public synchronized void failed() {
        if (++failures >= MAX_FAILURES) {
            failures = 0;
            lockedUntil = clock.getAsLong() + LOCKOUT_MS;
        }
    }

    public synchronized void succeeded() {
        failures = 0;
    }

    public synchronized void reset() {
        failures = 0;
        lockedUntil = 0L;
    }

    public synchronized int failures() {
        return failures;
    }
}
