package ps.reso.instaeclipse.utils.ghost;

public final class SeenPause {
    private static volatile long until = Long.MIN_VALUE;

    private SeenPause() {}

    public static long now() {
        return System.nanoTime() / 1_000_000L;
    }

    public static void pause(long ms) {
        pause(now(), ms);
    }

    public static void pause(long now, long ms) {
        until = Math.max(until, now + ms);
    }

    public static boolean isPaused(long now) {
        return now < until;
    }

    public static boolean blocks(boolean ghostSeen) {
        return blocks(ghostSeen, now());
    }

    public static boolean blocks(boolean ghostSeen, long now) {
        return ghostSeen && !isPaused(now);
    }

    public static void resetForTest() {
        until = Long.MIN_VALUE;
    }
}
