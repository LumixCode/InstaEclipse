package ps.reso.instaeclipse.mods.misc;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.TreeMap;

import ps.reso.instaeclipse.utils.users.WatchlistStore;

final class ProfileChangelogPolicy {

    static final int PAGE_ROWS = 150;

    enum Section {
        GAINED, GAINED_MAYBE, LOST, NOT_LISTED, B_FOLLOWED, B_FOLLOWED_MAYBE, B_UNFOLLOWED, B_NOT_LISTED;

        boolean gain() { return this == GAINED || this == B_FOLLOWED; }

        boolean loss() { return this == LOST || this == B_UNFOLLOWED; }

        boolean gone() { return loss() || this == NOT_LISTED || this == B_NOT_LISTED; }
    }

    private ProfileChangelogPolicy() {}

    static Section section(WatchlistStore.Event e) {
        if (e.type == null) return null;
        return switch (e.type) {
            case WatchlistStore.GAINED_FOLLOWER -> e.uncertain ? Section.GAINED_MAYBE : Section.GAINED;
            case WatchlistStore.LOST_FOLLOWER -> Section.LOST;
            case WatchlistStore.NOT_LISTED -> Section.NOT_LISTED;
            case WatchlistStore.B_FOLLOWED -> e.uncertain ? Section.B_FOLLOWED_MAYBE : Section.B_FOLLOWED;
            case WatchlistStore.B_UNFOLLOWED -> Section.B_UNFOLLOWED;
            case WatchlistStore.B_NOT_LISTED -> Section.B_NOT_LISTED;
            default -> null;
        };
    }

    static final class Group {
        final long ts;
        long since;
        boolean first;
        boolean recorded;
        int followers = -1, following = -1;
        int igFollowers = -1, igFollowing = -1;
        int found = -1;
        boolean partial;
        boolean unreliable;
        WatchlistStore.ListCheck followersList, followingList;
        private final List<List<WatchlistStore.Event>> sections = new ArrayList<>();

        Group(long ts) {
            this.ts = ts;
            for (int i = 0; i < Section.values().length; i++) sections.add(new ArrayList<>());
        }

        List<WatchlistStore.Event> of(Section s) {
            return sections.get(s.ordinal());
        }

        int kept() {
            int n = 0;
            for (Section s : Section.values()) if (s != Section.NOT_LISTED && s != Section.B_NOT_LISTED) n += of(s).size();
            return n;
        }

        int gains(boolean following) {
            return following ? of(Section.B_FOLLOWED).size() + of(Section.B_FOLLOWED_MAYBE).size()
                    : of(Section.GAINED).size() + of(Section.GAINED_MAYBE).size();
        }

        int losses(boolean following) {
            return of(following ? Section.B_UNFOLLOWED : Section.LOST).size();
        }
    }

    static boolean inMenu(boolean analyzer, boolean profileKnown, long lastCheck) {
        return analyzer && profileKnown && lastCheck > 0;
    }

    static boolean canMarkUnreliable(Group g) {
        return g.kept() > 0;
    }

    static List<Group> groups(WatchlistStore.Profile p, int maxEvents) {
        TreeMap<Long, Group> byTs = new TreeMap<>(Collections.reverseOrder());
        for (WatchlistStore.Check c : p.checks) {
            if (c.ts <= 0) continue;
            Group g = group(byTs, c.ts);
            g.recorded = true;
            g.first = c.first;
            g.since = c.since;
            g.followers = c.followers;
            g.following = c.following;
            g.followersList = c.followersList;
            g.followingList = c.followingList;
            g.igFollowers = c.followersList != null && c.followersList.expected >= 0 ? c.followersList.expected : c.igFollowers;
            g.igFollowing = c.followingList != null && c.followingList.expected >= 0 ? c.followingList.expected : c.igFollowing;
            g.found = c.changes;
        }
        long oldestEvent = Long.MAX_VALUE;
        for (WatchlistStore.Event e : p.events) {
            if (e.ts <= 0) continue;
            Group g = group(byTs, e.ts);
            Section s = section(e);
            if (s != null) g.of(s).add(e);
            if (e.since > 0 && e.since < e.ts) {
                if (g.since == 0) g.since = e.since;
                group(byTs, e.since);
            }
            oldestEvent = Math.min(oldestEvent, e.ts);
        }
        for (long ts : p.unreliable) if (ts > 0) group(byTs, ts).unreliable = true;
        if (p.lastCheck > 0) group(byTs, p.lastCheck);

        boolean truncated = maxEvents <= 0 || p.events.size() >= maxEvents;
        int fol = -1, fing = -1;
        for (Group g : byTs.values()) {
            if (g.ts == p.lastCheck) {
                fol = p.followers.size();
                fing = p.followingState.ts >= 0 ? p.following.size() : -1;
            }
            if (g.recorded) {
                fol = g.followers;
                fing = g.following;
            } else {
                g.followers = fol;
                g.following = fing;
                if (g.ts == p.lastCheck && p.baseAt == p.lastCheck) {
                    g.igFollowers = p.baseFollowers;
                    g.igFollowing = p.baseFollowing;
                }
            }
            g.partial = !g.unreliable && (g.recorded && g.found >= 0 ? g.kept() < g.found : truncated && g.ts <= oldestEvent);
            fol = before(fol, g.followersList, g, false);
            fing = before(fing, g.followingList, g, true);
        }
        return new ArrayList<>(byTs.values());
    }

    private static int before(int after, WatchlistStore.ListCheck rec, Group g, boolean following) {
        if (rec != null) return rec.before;
        if (g.partial || g.unreliable || after < 0) return -1;
        return after - g.gains(following) + g.losses(following);
    }

    private static Group group(TreeMap<Long, Group> byTs, long ts) {
        Group g = byTs.get(ts);
        if (g == null) byTs.put(ts, g = new Group(ts));
        return g;
    }

    enum Kind { CHECK, SECTION, ACCOUNT }

    static final class Row {
        final Kind kind;
        final Group group;
        final Section section;
        final WatchlistStore.Event event;

        Row(Kind kind, Group group, Section section, WatchlistStore.Event event) {
            this.kind = kind;
            this.group = group;
            this.section = section;
            this.event = event;
        }
    }

    static List<Row> rows(List<Group> groups) {
        List<Row> out = new ArrayList<>();
        for (Group g : groups) {
            out.add(new Row(Kind.CHECK, g, null, null));
            for (Section s : Section.values()) {
                List<WatchlistStore.Event> list = g.of(s);
                if (list.isEmpty()) continue;
                out.add(new Row(Kind.SECTION, g, s, null));
                for (WatchlistStore.Event e : list) out.add(new Row(Kind.ACCOUNT, g, s, e));
            }
        }
        return out;
    }

    static boolean showsLastListed(Section s, WatchlistStore.Event e, Group g) {
        return s != null && s.gone() && e.since > 0 && e.since != g.since;
    }

    static int pageEnd(List<Row> rows, int from, int pageRows) {
        int end = Math.min(rows.size(), from + Math.max(1, pageRows));
        while (end > from && end < rows.size()) {
            Kind last = rows.get(end - 1).kind, next = rows.get(end).kind;
            if (last == Kind.SECTION || (last == Kind.CHECK && next == Kind.SECTION)) end++;
            else break;
        }
        return end;
    }
}
