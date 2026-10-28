package ps.reso.instaeclipse.utils.core;

import android.content.SharedPreferences;

import java.util.function.LongSupplier;

public final class PasscodeLockout {

    public static final int MAX_FAILURES = 5;
    public static final long LOCKOUT_MS = 30_000L;
    static final String FAILURES = "failures";
    static final String LOCKED_AT = "lockedAt";
    private static final long NONE = Long.MIN_VALUE;

    private final SharedPreferences store;
    private final LongSupplier clock;
    private int failures;
    private long lockedAt = NONE;

    public PasscodeLockout(SharedPreferences store, LongSupplier clock) {
        this.store = store;
        this.clock = clock;
        if (store != null) {
            failures = Math.max(0, store.getInt(FAILURES, 0));
            lockedAt = store.getLong(LOCKED_AT, NONE);
        }
    }

    public synchronized int secondsLeft() {
        if (lockedAt == NONE) return 0;
        long now = clock.getAsLong();
        if (now < lockedAt) {
            lockedAt = now;
            save();
        }
        long left = lockedAt + LOCKOUT_MS - now;
        if (left > 0) return (int) ((left + 999) / 1000);
        lockedAt = NONE;
        save();
        return 0;
    }

    public synchronized void failed() {
        if (++failures >= MAX_FAILURES) {
            failures = 0;
            lockedAt = clock.getAsLong();
        }
        save();
    }

    public synchronized void succeeded() {
        if (failures == 0) return;
        failures = 0;
        save();
    }

    public synchronized void reset() {
        failures = 0;
        lockedAt = NONE;
        save();
    }

    public synchronized int failures() {
        return failures;
    }

    private void save() {
        if (store != null) store.edit().putInt(FAILURES, failures).putLong(LOCKED_AT, lockedAt).commit();
    }
}
