package ps.reso.instaeclipse.utils.core;

import java.util.concurrent.ThreadLocalRandom;

public final class Pace {

    private Pace() {}

    public static long between(long minMs, long maxMs) {
        if (maxMs <= minMs) return minMs;
        return ThreadLocalRandom.current().nextLong(minMs, maxMs + 1);
    }

    public static long next(int step, long minMs, long maxMs, int every, long restMinMs, long restMaxMs) {
        if (every > 0 && step > 0 && step % every == 0) return between(restMinMs, restMaxMs);
        return between(minMs, maxMs);
    }
}
