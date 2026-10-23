package ps.reso.instaeclipse.mods.misc;

import java.text.NumberFormat;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * What ProfileCountDeltaHook draws next to a profile header's follower / following count, and the
 * counts headers showed per profile, which become a profile's baseline when its analysis completes
 * (WatchlistStore.apply asks {@link Seen#counts}). Exact counts only: Instagram's own Integers,
 * never the header text, which is abbreviated ("12,3K").
 */
final class ProfileCountDelta {
    static final int GREEN = 0xFF34C759, RED = 0xFFFF453A; // ProfileAnalyzer's colours
    /** Header counts older than this aren't "at the analysis": an analysis starts from the profile
     *  and gives up after 15 minutes. */
    static final long MAX_AGE_MS = 30 * 60_000L;
    static final int MAX_PROFILES = 200;

    private ProfileCountDelta() {}

    /**
     * "+N" when {@code current} is above {@code baseline}, "-N" when below, N grouped as
     * {@code locale} groups numbers; null when they are equal or either is unknown (negative), so
     * an unchanged count, or one without an analysis to compare with, shows nothing.
     */
    static String label(Integer current, int baseline, Locale locale) {
        if (current == null || current < 0 || baseline < 0) return null;
        long delta = (long) current - baseline;
        if (delta == 0) return null;
        NumberFormat format = NumberFormat.getIntegerInstance(locale != null ? locale : Locale.getDefault());
        format.setGroupingUsed(true);
        return (delta > 0 ? "+" : "-") + format.format(Math.abs(delta));
    }

    static int color(String label) {
        return label != null && label.startsWith("+") ? GREEN : RED;
    }

    /** Per profile id, the latest follower and following counts a header showed, and when. */
    static final class Seen {
        private static final class Counts {
            int followers = -1, following = -1;
            long followersAt, followingAt;
        }

        private final Map<String, Counts> byId = new LinkedHashMap<>(16, 0.75f, true) {
            @Override protected boolean removeEldestEntry(Map.Entry<String, Counts> eldest) {
                return size() > MAX_PROFILES;
            }
        };

        synchronized void put(String id, boolean followers, int count, long now) {
            if (id == null || count < 0) return;
            Counts c = byId.get(id);
            if (c == null) byId.put(id, c = new Counts());
            if (followers) { c.followers = count; c.followersAt = now; }
            else { c.following = count; c.followingAt = now; }
        }

        /** {followers, following}, or null unless both were shown within {@link #MAX_AGE_MS} of {@code now}. */
        synchronized int[] counts(String id, long now) {
            Counts c = id != null ? byId.get(id) : null;
            if (c == null || c.followers < 0 || c.following < 0) return null;
            if (!fresh(c.followersAt, now) || !fresh(c.followingAt, now)) return null;
            return new int[]{c.followers, c.following};
        }

        private static boolean fresh(long at, long now) {
            return at > 0 && Math.abs(now - at) <= MAX_AGE_MS; // wall clock: it may step back a little
        }
    }
}
