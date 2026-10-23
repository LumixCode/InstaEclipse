package ps.reso.instaeclipse.mods.misc;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.TreeMap;

import ps.reso.instaeclipse.utils.users.WatchlistStore;

/**
 * Pure logic of a watched profile's changelog (ProfileChangelog), kept apart so it runs as JVM unit
 * tests: the profile's saved analyses grouped as checks, newest first, and the flat rows the dialog
 * adds a page at a time.
 *
 * <p>Checks come from the profile's check records ({@link WatchlistStore.Check}) and, for analyses
 * saved before those existed, from its events (each names its analysis and the one it was compared
 * with) and its last analysis. How many accounts each list had at an older check without a record
 * follows from the last snapshot: a record since the not-listed accounts came keeps the saved
 * list's size before its check; before that, each check's changes are exactly what its lists
 * gained and lost, so undoing them, newest first, gives the sizes before it, as long as none of
 * its changes were dropped from the log or marked unreliable. Not-listed entries are no changes and
 * never take part in that.
 *
 * <p>A check's sections: new followers, new followers that may only have been missed last time
 * (uncertain), confirmed losses, followers this check didn't list (not confirmed), and the same
 * for the accounts the profile follows.
 */
final class ProfileChangelogPolicy {

    /** Rows per page of the dialog; "Show more" adds the next page. */
    static final int PAGE_ROWS = 150;

    /** The sections of a check, in the order shown. */
    enum Section {
        GAINED, GAINED_MAYBE, LOST, NOT_LISTED, B_FOLLOWED, B_FOLLOWED_MAYBE, B_UNFOLLOWED, B_NOT_LISTED;

        /** Confirmed gains (green) / losses (red); the rest aren't confirmed (grey). */
        boolean gain() { return this == GAINED || this == B_FOLLOWED; }

        boolean loss() { return this == LOST || this == B_UNFOLLOWED; }

        /** Losses and not-listed entries: their accounts were listed before ({@code since}). */
        boolean gone() { return loss() || this == NOT_LISTED || this == B_NOT_LISTED; }
    }

    private ProfileChangelogPolicy() {}

    /** The section an event belongs to, null for an unknown type. */
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

    /** One saved check of the profile, as the changelog shows it. */
    static final class Group {
        final long ts;
        /** the check it was compared with, 0 when not known (or none: the first check) */
        long since;
        /** the profile's first check, nothing compared (known from its record only) */
        boolean first;
        /** saved with a check record; older checks show only through their changes */
        boolean recorded;
        /** accounts each list had at this check, -1 when not known */
        int followers = -1, following = -1;
        /** Instagram's counts around this check (the list's own, else its header's), -1 when not recorded */
        int igFollowers = -1, igFollowing = -1;
        /** changes this check found, -1 when not recorded */
        int found = -1;
        /** some of its changes are no longer in the log (it keeps the newest ones only) */
        boolean partial;
        /** the user marked its changes unreliable: they were removed, the lists kept */
        boolean unreliable;
        /** how each list loaded and was compared (records since the not-listed accounts came), or null */
        WatchlistStore.ListCheck followersList, followingList;
        private final List<List<WatchlistStore.Event>> sections = new ArrayList<>();

        Group(long ts) {
            this.ts = ts;
            for (int i = 0; i < Section.values().length; i++) sections.add(new ArrayList<>());
        }

        /** Its entries of one section, in the log's order. */
        List<WatchlistStore.Event> of(Section s) {
            return sections.get(s.ordinal());
        }

        /** Its changes still in the log (not-listed entries aren't changes). */
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

    /** Whether the tools menu offers Changelog: the analyzer is on, the list's profile is known and
     *  the watchlist has a saved check of it ({@code lastCheck}, 0 when none). */
    static boolean inMenu(boolean analyzer, boolean profileKnown, long lastCheck) {
        return analyzer && profileKnown && lastCheck > 0;
    }

    /** Whether a check offers "Mark these changes as unreliable": it still has changes in the log. */
    static boolean canMarkUnreliable(Group g) {
        return g.kept() > 0;
    }

    /** The profile's saved checks, newest first. {@code maxEvents}: how many events its log keeps. */
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
                // the check it was compared with (a gain), or that last listed the account (a loss,
                // a not-listed entry: the same check unless it was missing for a while; checks with
                // those have records, which name the check they were compared with)
                if (g.since == 0) g.since = e.since;
                group(byTs, e.since); // that check was saved too
            }
            oldestEvent = Math.min(oldestEvent, e.ts);
        }
        for (long ts : p.unreliable) if (ts > 0) group(byTs, ts).unreliable = true;
        if (p.lastCheck > 0) group(byTs, p.lastCheck);

        boolean truncated = maxEvents <= 0 || p.events.size() >= maxEvents;
        // list sizes after the check being looked at, from the newest snapshot backwards
        int fol = -1, fing = -1;
        for (Group g : byTs.values()) {
            if (g.ts == p.lastCheck) {
                fol = p.followers.size();
                fing = p.following.size();
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
            // the sizes before this check
            fol = before(fol, g.followersList, g, false);
            fing = before(fing, g.followingList, g, true);
        }
        return new ArrayList<>(byTs.values());
    }

    /** A list's size before check {@code g}, from its size after it ({@code after}): the record's
     *  saved size when it has one, else its changes undone (unknown once some are missing). */
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

    /** What a row of the dialog shows. */
    enum Kind { CHECK, SECTION, ACCOUNT }

    static final class Row {
        final Kind kind;
        final Group group;
        /** SECTION / ACCOUNT: its section */
        final Section section;
        /** ACCOUNT: the entry */
        final WatchlistStore.Event event;

        Row(Kind kind, Group group, Section section, WatchlistStore.Event event) {
            this.kind = kind;
            this.group = group;
            this.section = section;
            this.event = event;
        }
    }

    /** Every row: per check its heading, then per section with entries a heading and its accounts. */
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

    /** Whether an account row shows when it was last listed: a loss or not-listed entry whose
     *  account wasn't listed by the check right before (it was not listed for a while). */
    static boolean showsLastListed(Section s, WatchlistStore.Event e, Group g) {
        return s != null && s.gone() && e.since > 0 && e.since != g.since;
    }

    /**
     * End (exclusive) of the page that starts at {@code from}: {@code pageRows} rows, but never
     * ending on a heading whose first row would only come on the next page.
     */
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
