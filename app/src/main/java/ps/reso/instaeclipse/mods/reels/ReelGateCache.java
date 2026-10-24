package ps.reso.instaeclipse.mods.reels;

import java.util.Objects;
import java.util.function.LongSupplier;
import java.util.function.Supplier;

final class ReelGateCache {

    private static final long MINUTE = 60_000L;

    private final LongSupplier clock;
    private long minute = Long.MIN_VALUE;
    private Object inputs;
    private ReelBlockPolicy.Reason reason;

    ReelGateCache(LongSupplier clock) {
        this.clock = clock;
    }

    boolean expired(Object inputs) {
        return reason == null || minuteNow() != minute || !Objects.equals(inputs, this.inputs);
    }

    ReelBlockPolicy.Reason reason(Object inputs, Supplier<ReelBlockPolicy.Reason> compute) {
        long now = minuteNow();
        if (reason == null || now != minute || !Objects.equals(inputs, this.inputs)) {
            ReelBlockPolicy.Reason computed = compute.get();
            minute = now;
            this.inputs = inputs;
            reason = computed;
        }
        return reason;
    }

    void clear() {
        reason = null;
        inputs = null;
        minute = Long.MIN_VALUE;
    }

    private long minuteNow() {
        return Math.floorDiv(clock.getAsLong(), MINUTE);
    }
}
