package ps.reso.instaeclipse.mods.ghost;

public final class QuickTapSequence {
    private final long maxGap, maxTotal;
    private long firstTap, lastTap;
    private int count;

    public QuickTapSequence() { this(800L, 800L); }

    private QuickTapSequence(long maxGap, long maxTotal) {
        this.maxGap = maxGap;
        this.maxTotal = maxTotal;
    }

    public static QuickTapSequence withGap(long maxGap) {
        return new QuickTapSequence(maxGap, Long.MAX_VALUE);
    }

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

    public int count() { return count; }

    public long lastTap() { return lastTap; }

    public void reset() { count = 0; }
}
