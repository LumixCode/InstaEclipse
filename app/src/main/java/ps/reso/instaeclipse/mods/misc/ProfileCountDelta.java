package ps.reso.instaeclipse.mods.misc;

import java.text.NumberFormat;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

final class ProfileCountDelta {
    static final long MAX_AGE_MS = 30 * 60_000L;
    static final int MAX_PROFILES = 200;

    private ProfileCountDelta() {}

    static String label(Integer current, int baseline, Locale locale) {
        if (current == null || current < 0 || baseline < 0) return null;
        long delta = (long) current - baseline;
        if (delta == 0) return null;
        NumberFormat format = NumberFormat.getIntegerInstance(locale != null ? locale : Locale.getDefault());
        format.setGroupingUsed(true);
        return (delta > 0 ? "+" : "-") + format.format(Math.abs(delta));
    }

    static int color(String label, int success, int destructive) {
        return label != null && label.startsWith("+") ? success : destructive;
    }

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

        synchronized int[] counts(String id, long now) {
            Counts c = id != null ? byId.get(id) : null;
            if (c == null || c.followers < 0 || c.following < 0) return null;
            if (!fresh(c.followersAt, now) || !fresh(c.followingAt, now)) return null;
            return new int[]{c.followers, c.following};
        }

        private static boolean fresh(long at, long now) {
            return at > 0 && Math.abs(now - at) <= MAX_AGE_MS;
        }
    }
}
