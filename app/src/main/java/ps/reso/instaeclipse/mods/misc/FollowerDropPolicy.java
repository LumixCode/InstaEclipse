package ps.reso.instaeclipse.mods.misc;

import java.util.List;

import ps.reso.instaeclipse.utils.users.WatchlistStore;

final class FollowerDropPolicy {

    static final long CHECK_EVERY_MS = 5 * 60_000L;
    static final long DROP_EVERY_MS = 12 * 3_600_000L;
    static final long WEEK_MS = 7 * 24 * 3_600_000L;

    enum Kind { NONE, DROP, REMINDER }

    private FollowerDropPolicy() {}

    static int then(int countAtCheck, int listed) {
        return countAtCheck >= 0 ? countAtCheck : listed;
    }

    static Kind kind(int count, int then, long lastCheck, long now, long lastDrop, long lastReminder) {
        if (count < 0 || then < 0 || lastCheck <= 0) return Kind.NONE;
        if (count < then) return within(lastDrop, now, DROP_EVERY_MS) ? Kind.NONE : Kind.DROP;
        if (now - lastCheck < WEEK_MS) return Kind.NONE;
        return within(lastReminder, now, WEEK_MS) || within(lastDrop, now, DROP_EVERY_MS) ? Kind.NONE : Kind.REMINDER;
    }

    static int[] week(List<WatchlistStore.Event> events, long now) {
        int lost = 0, gained = 0;
        for (WatchlistStore.Event event : events) {
            if (!within(event.ts, now, WEEK_MS)) continue;
            if (WatchlistStore.LOST_FOLLOWER.equals(event.type)) lost++;
            else if (WatchlistStore.GAINED_FOLLOWER.equals(event.type)) gained++;
        }
        return new int[]{lost, gained};
    }

    private static boolean within(long at, long now, long window) {
        return at > 0 && at <= now && now - at < window;
    }
}
