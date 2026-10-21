package ps.reso.instaeclipse.mods.reels;

/** Daily viewing state shared by every reel surface, independent of Android and network prefetch. */
final class DailyReelUsage {
    static final int DEFAULT_LIMIT = 100;
    static final int MAX_LIMIT = 10_000;

    long day;
    int count;
    String lastMediaId;

    DailyReelUsage(long day, int count, String lastMediaId) {
        this.day = day;
        this.count = Math.max(0, count);
        this.lastMediaId = lastMediaId;
    }

    boolean rollover(long today) {
        // Moving the clock backwards must not grant a second allowance for an earlier day.
        if (today <= day) return false;
        day = today;
        count = 0;
        lastMediaId = null;
        return true;
    }

    boolean record(String mediaId, long today) {
        boolean changed = rollover(today);
        if (mediaId == null || mediaId.isEmpty() || mediaId.equals(lastMediaId)) return changed;
        lastMediaId = mediaId;
        if (count < Integer.MAX_VALUE) count++;
        return true;
    }

    static int validLimit(int value) {
        return value >= 1 && value <= MAX_LIMIT ? value : DEFAULT_LIMIT;
    }

    int warningRemaining(int limit) {
        int remaining = validLimit(limit) - count;
        return remaining == 10 || remaining == 5 ? remaining : -1;
    }

    boolean reached(int limit) {
        return count >= validLimit(limit);
    }

    boolean blocksPlayback(String mediaId, int limit) {
        int allowance = validLimit(limit);
        if (count < allowance) return false;
        // Let the final counted reel finish and loop; selecting another reel closes the viewer.
        return count > allowance || mediaId == null || mediaId.isEmpty() || !mediaId.equals(lastMediaId);
    }
}
