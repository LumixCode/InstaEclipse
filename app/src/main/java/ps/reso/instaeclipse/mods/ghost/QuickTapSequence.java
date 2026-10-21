package ps.reso.instaeclipse.mods.ghost;

/**
 * Counts completed taps; callers reject drags and taps outside the target before recording.
 * The default rule wants all three taps within 800 ms of the first (ThreadHeaderMenu's replay
 * delay is tuned to it). {@link #withGap} limits only the pause between two taps, so a steady
 * triple tap that is not especially fast still counts.
 */
public final class QuickTapSequence {
    private final long maxGap, maxTotal;
    private long firstTap, lastTap;
    private int count;

    public QuickTapSequence() { this(800L, 800L); }

    private QuickTapSequence(long maxGap, long maxTotal) {
        this.maxGap = maxGap;
        this.maxTotal = maxTotal;
    }

    /** Three taps, each at most {@code maxGap} ms after the one before, however long in all. */
    public static QuickTapSequence withGap(long maxGap) {
        return new QuickTapSequence(maxGap, Long.MAX_VALUE);
    }

    /** Records a tap at {@code time}; true for the third one. A late tap starts a new sequence. */
    public boolean tap(long time) {
        if (count == 0 || time < lastTap || time - lastTap > maxGap || time - firstTap > maxTotal) {
            firstTap = time;
            lastTap = time;
            count = 1;
            return false;
        }
        lastTap = time;
        if (++count < 3) return false;
        reset();
        return true;
    }

    /** Taps counted so far (0 after a reset or a completed sequence). */
    public int count() { return count; }

    /** When the last counted tap ended. */
    public long lastTap() { return lastTap; }

    public void reset() { count = 0; }
}
