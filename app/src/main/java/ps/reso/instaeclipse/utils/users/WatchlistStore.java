package ps.reso.instaeclipse.utils.users;

import android.content.Context;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Profile analyzer watchlist: other profiles whose Followers and Following lists you check.
 *
 * Like FollowersHistory, but for a watched profile B and both of its lists. Each analysis is a full
 * load of B's Followers and Following (FollowListLoader drives IG's pager); both lists are kept as
 * B's last snapshot (id → {username, full name}, the accounts the latest check listed) and each
 * analysis is compared with it: who B newly followed / unfollowed, who newly followed B / stopped
 * following B. Everything lives in Instagram's private files, per logged-in account: an index
 * (watched profiles and their counts) and one file per watched profile (snapshot, not-listed
 * accounts, event log newest first, capped, and checks).
 *
 * <p>A load can miss accounts: Instagram pages someone else's Followers through a ranking that
 * shifts between requests, so even several passes may not list an account or two. So an account
 * the saved list has that a check doesn't list isn't "stopped following" at once: it is kept per
 * list as not listed ({@link Missing}, shown as such, outside the snapshot) until complete checks
 * keep missing it or it expires (WatchlistPolicy.confirmed), and an account listed again leaves it
 * without any change. A new account is marked uncertain ("new, or not listed last time") when the
 * saved list's load wasn't complete or isn't known (profiles saved before this). A list whose size
 * change disagrees with Instagram's count change is re-baselined or not trusted
 * (WatchlistPolicy.trust), and a list Instagram limits is compared for additions only or not at
 * all (WatchlistPolicy.diffMode). How each list's load went is saved with its check
 * ({@link ListCheck}).
 *
 * <p>Each analysis also keeps the follower / following counts Instagram itself showed for B around
 * that analysis (its profile header, via {@link #setCountSource}), as the baseline of the header's
 * count changes (ProfileCountDeltaHook). These are B's real counts, not the loaded list sizes, and
 * are kept only when the header showed them shortly before; otherwise that analysis has none.
 *
 * <p>Each analysis is also kept as a {@link Check} (when, list sizes, those counts, how many
 * changes, how each list loaded), the profile's changelog (ProfileChangelog). Profiles saved
 * before have none. A check's changes can be marked unreliable there ({@link #dropCheckEvents}).
 */
public final class WatchlistStore {

    public static final String B_FOLLOWED = "b_followed";       // B started following someone
    public static final String B_UNFOLLOWED = "b_unfollowed";   // B stopped following someone
    public static final String GAINED_FOLLOWER = "gained";      // someone started following B
    public static final String LOST_FOLLOWER = "lost";          // someone stopped following B
    /** Not changes: a check didn't list an account of the saved Followers / Following list, which
     *  doesn't count as gone yet (see {@link Missing}); kept in the log so the changelog shows it. */
    public static final String NOT_LISTED = "not_listed";
    public static final String B_NOT_LISTED = "b_not_listed";
    static final int MAX_EVENTS = 3000;
    static final int MAX_PROFILES = 100;
    static final int MAX_CHECKS = 500;

    /** How many events a profile's log keeps (the newest); older ones are dropped. */
    public static int maxEvents() {
        return MAX_EVENTS;
    }

    /** Whether an event of {@code type} is a change (not a not-listed entry). */
    public static boolean isChange(String type) {
        return B_FOLLOWED.equals(type) || B_UNFOLLOWED.equals(type) || GAINED_FOLLOWER.equals(type) || LOST_FOLLOWER.equals(type);
    }

    /** The changes among {@code events} (not-listed entries left out). */
    public static int changeCount(List<Event> events) {
        int n = 0;
        for (Event e : events) if (isChange(e.type)) n++;
        return n;
    }

    /** One noticed change of a watched profile, or an account a check didn't list (NOT_LISTED,
     *  B_NOT_LISTED). {@code since}: the check before (a gain), the last check that listed the
     *  account (a loss, a not-listed entry). */
    public static final class Event {
        public final long ts, since;
        public final String type, id, username, fullName;
        /** a gain while the saved list's load wasn't complete or isn't known: new, or not listed last time */
        public final boolean uncertain;

        public Event(long ts, long since, String type, String id, String username, String fullName) {
            this(ts, since, type, id, username, fullName, false);
        }

        public Event(long ts, long since, String type, String id, String username, String fullName, boolean uncertain) {
            this.ts = ts;
            this.since = since;
            this.type = type;
            this.id = id;
            this.username = username;
            this.fullName = fullName;
            this.uncertain = uncertain;
        }
    }

    /** An account of a saved list that a check didn't list and that doesn't count as gone yet. */
    public static final class Missing {
        public final String username, fullName;
        /** the last check that listed it, and the first check that didn't */
        public final long lastSeen, firstMissed;
        /** complete checks that didn't list it, and checks of any kind that didn't */
        public int strikes, checks;

        public Missing(String username, String fullName, long lastSeen, long firstMissed) {
            this.username = username;
            this.fullName = fullName;
            this.lastSeen = lastSeen;
            this.firstMissed = firstMissed;
        }
    }

    /** What one saved list is: the check that listed it ({@code ts}, 0: not known, i.e. the last
     *  analysis), Instagram's count then (-1: not known), whether that load was complete (1, 0, -1:
     *  not known) and whether it is a list Instagram limited (never compared then). */
    public static final class ListState {
        public long ts;
        public int expected = -1, complete = -1;
        public boolean limited;
    }

    /** What a check did with one list (see WatchlistPolicy). */
    public enum Outcome {
        /** the profile's first check: saved, nothing compared */
        FIRST,
        /** no usable saved list (Instagram limited it): saved as the baseline, nothing compared */
        BASELINE,
        /** compared: gains and losses */
        FULL,
        /** the saved list was clearly less complete: this one is the new baseline, its newly listed
         *  accounts aren't changes; losses compared */
        REBASELINED,
        /** this load was clearly less complete: the saved list kept (new accounts added), no losses */
        KEPT_SAVED,
        /** Instagram limits this list now: the saved list kept (new accounts added), no losses */
        ADDITIONS,
        /** Instagram limits this list and no usable one was saved: saved (marked limited), nothing compared */
        NOT_COMPARED
    }

    /**
     * One list of one check: how its load went (the analyzer copies FollowListLoader's Result; -1:
     * not known) and what the comparison did with it (filled in by {@link #apply}).
     */
    public static final class ListCheck {
        /** first pass: accounts Instagram sent (repeats included) and repeats in it */
        public int received = -1, repeated = -1;
        /** accounts listed by all passes; passes run; accounts the last extra pass added (-1: none
         *  ran); accounts listed by exactly one / two passes */
        public int distinct = -1, passes = -1, lastPassAdded = -1, f1 = -1, f2 = -1;
        /** Chao1's estimate of accounts no pass listed, -1 with one pass */
        public double estMissing = -1;
        /** Instagram's count for this list (its tab), -1 when unknown */
        public int expected = -1;
        /** accounts Instagram says it hides from this list (hidden_following_account_count), 0 when none */
        public int hidden;
        /** Instagram limits this list (shows only part of it) */
        public boolean limited;

        /** this load listed (about) every account (WatchlistPolicy.complete) */
        public boolean complete;
        public Outcome outcome;
        /** the saved list's size before this check, -1 for none: the changelog's sizes before it */
        public int before = -1;
        /** the saved list with its not-listed accounts, and Instagram's count then, as compared
         *  (-1: not compared or not known) */
        public int prevListed = -1, prevCount = -1;
        /** new accounts (of them uncertain), accounts confirmed gone, accounts not listed (of them
         *  for the first time), not-listed accounts listed again, newly listed accounts a new
         *  baseline didn't count as changes */
        public int gained, uncertain, gone, notListed, newlyMissing, relisted, filled;
    }

    /**
     * One saved analysis: when it ran ({@code ts}), the analysis it was compared with ({@code since},
     * 0 for the first), how many accounts each list had (what this check listed), Instagram's
     * header counts around it (-1: not known) and how many changes it found. Profiles keep these
     * since the changelog came; older analyses only show through their events. Checks since the
     * not-listed accounts came also keep how each list loaded and was compared ({@code
     * followersList} / {@code followingList}, null before).
     */
    public static final class Check {
        public final long ts, since;
        public final boolean first;
        public final int followers, following, igFollowers, igFollowing, changes;
        public ListCheck followersList, followingList;

        public Check(long ts, long since, boolean first, int followers, int following,
                     int igFollowers, int igFollowing, int changes) {
            this.ts = ts;
            this.since = since;
            this.first = first;
            this.followers = followers;
            this.following = following;
            this.igFollowers = igFollowers;
            this.igFollowing = igFollowing;
            this.changes = changes;
        }
    }

    /** A watched profile as listed in the index. */
    public static final class Entry {
        public String id, username;
        public long added, lastCheck;
        public int followers = -1, following = -1, events;
        /** Instagram's counts at the last analysis (-1: not known), and when its header showed them. */
        public int baseFollowers = -1, baseFollowing = -1;
        public long baseAt;
    }

    /** A watched profile's saved state: the last snapshot of both lists and its event log. */
    public static final class Profile {
        public String id, username;
        public long lastCheck;
        /** The saved lists: the accounts the latest check listed (or the kept list, see Outcome),
         *  never the not-listed ones, so their sizes are what was listed. */
        public Map<String, String[]> followers = new LinkedHashMap<>();
        public Map<String, String[]> following = new LinkedHashMap<>();
        /** Accounts of the saved lists a check didn't list that don't count as gone yet, by id. */
        public Map<String, Missing> missingFollowers = new LinkedHashMap<>();
        public Map<String, Missing> missingFollowing = new LinkedHashMap<>();
        /** What the saved lists are (default for profiles saved before: nothing known). */
        public ListState followersState = new ListState(), followingState = new ListState();
        public List<Event> events = new ArrayList<>();
        /** As in {@link Entry}: Instagram's counts at the last analysis, -1 when not known. */
        public int baseFollowers = -1, baseFollowing = -1;
        public long baseAt;
        /** Its saved analyses, newest first (capped); empty for analyses saved before these existed. */
        public List<Check> checks = new ArrayList<>();
        /** Analyses whose changes the user marked unreliable (removed from the log), by time. */
        public List<Long> unreliable = new ArrayList<>();
    }

    /** Instagram's {followers, following} counts for a profile id as its header showed them
     *  recently (before {@code now}), or null when they aren't known. */
    public interface CountSource {
        int[] counts(String id, long now);
    }

    private static volatile CountSource countSource;

    /** Set once by ProfileCountDeltaHook; without one, analyses keep no baseline. */
    public static void setCountSource(CountSource source) {
        countSource = source;
    }

    /** What one analysis found. {@code first}: no earlier snapshot, nothing compared.
     *  {@code followers} / {@code following}: what it listed. */
    public static final class Result {
        public final boolean first;
        public final int followers, following;
        public final long previousCheck;
        /** the changes */
        public final List<Event> added;
        /** accounts of the saved lists this analysis didn't list that don't count as gone yet */
        public final List<Event> notListed;
        public final ListCheck followersList, followingList;

        Result(boolean first, int followers, int following, long previousCheck, List<Event> added,
               List<Event> notListed, ListCheck followersList, ListCheck followingList) {
            this.first = first;
            this.followers = followers;
            this.following = following;
            this.previousCheck = previousCheck;
            this.added = added;
            this.notListed = notListed;
            this.followersList = followersList;
            this.followingList = followingList;
        }

        /** Its changes and not-listed entries of {@code type}. */
        public List<Event> of(String type) {
            List<Event> out = new ArrayList<>();
            for (Event e : added) if (type.equals(e.type)) out.add(e);
            for (Event e : notListed) if (type.equals(e.type)) out.add(e);
            return out;
        }
    }

    private WatchlistStore() {}

    // ---- pure logic ----

    private static Event event(long now, long since, String type, String id, String[] u, boolean uncertain) {
        return new Event(now, since, type, id, u != null && u.length > 0 ? u[0] : null,
                u != null && u.length > 1 ? u[1] : null, uncertain);
    }

    /**
     * Applies the Followers and Following lists one analysis loaded completely ({@code qFollowers}
     * / {@code qFollowing}: how each load went; null: nothing known) to {@code p}: each list is
     * compared with its saved one (see the class comment and {@link Outcome}), the changes and
     * not-listed entries are prepended to its log (capped), the saved lists are updated and the
     * analysis is added as a check.
     */
    public static Result apply(Profile p, Map<String, String[]> followers, Map<String, String[]> following,
                               ListCheck qFollowers, ListCheck qFollowing, long now) {
        boolean first = p.lastCheck == 0;
        long previous = p.lastCheck;
        ListCheck qFol = qFollowers != null ? qFollowers : new ListCheck();
        ListCheck qFing = qFollowing != null ? qFollowing : new ListCheck();
        // Instagram's header counts at the last analysis: the saved lists' counts when they have none
        boolean header = p.baseAt > 0 && p.baseAt == p.lastCheck;
        List<Event> added = new ArrayList<>(), notListed = new ArrayList<>();
        p.following = applyList(p.following, p.missingFollowing, p.followingState, following, qFing, first, previous,
                header ? p.baseFollowing : -1, now, B_UNFOLLOWED, B_FOLLOWED, B_NOT_LISTED, added, notListed);
        p.followers = applyList(p.followers, p.missingFollowers, p.followersState, followers, qFol, first, previous,
                header ? p.baseFollowers : -1, now, LOST_FOLLOWER, GAINED_FOLLOWER, NOT_LISTED, added, notListed);
        List<Event> all = new ArrayList<>(added);
        all.addAll(notListed);
        all.addAll(p.events);
        while (all.size() > MAX_EVENTS) all.remove(all.size() - 1);
        p.events = all;
        p.lastCheck = now;
        applyBaseline(p, now);
        Check c = new Check(now, previous, first, followers.size(), following.size(),
                p.baseFollowers, p.baseFollowing, added.size());
        c.followersList = qFol;
        c.followingList = qFing;
        List<Check> checks = new ArrayList<>();
        checks.add(c);
        checks.addAll(p.checks);
        while (checks.size() > MAX_CHECKS) checks.remove(checks.size() - 1);
        p.checks = checks;
        return new Result(first, followers.size(), following.size(), previous, added, notListed, qFol, qFing);
    }

    /**
     * Compares one loaded list ({@code now}, its load {@code q}) with its saved one ({@code saved},
     * {@code missing}, {@code st}, updated in place); adds its changes and not-listed entries and
     * returns the list to save. {@code headerCount}: Instagram's header count at the last analysis
     * (-1), for a saved list without its own.
     */
    private static Map<String, String[]> applyList(Map<String, String[]> saved, Map<String, Missing> missing, ListState st,
                                                   Map<String, String[]> now, ListCheck q, boolean first, long previous,
                                                   int headerCount, long ts, String goneType, String newType, String missType,
                                                   List<Event> changes, List<Event> notListed) {
        q.distinct = now.size();
        q.complete = WatchlistPolicy.complete(q.passes, q.repeated, q.lastPassAdded, q.estMissing, q.expected, q.hidden,
                now.size(), q.limited);
        q.before = first ? -1 : saved.size();
        WatchlistPolicy.DiffMode mode = WatchlistPolicy.diffMode(!first && !st.limited, q.limited);
        if (mode == WatchlistPolicy.DiffMode.FULL) {
            q.prevListed = saved.size() + missing.size();
            q.prevCount = st.expected >= 0 ? st.expected : headerCount;
            q.outcome = switch (WatchlistPolicy.trust(q.prevListed, q.prevCount, now.size(), q.expected)) {
                case REBASELINE -> Outcome.REBASELINED;
                case KEEP_SAVED -> Outcome.KEPT_SAVED;
                default -> Outcome.FULL;
            };
        } else if (mode == WatchlistPolicy.DiffMode.ADDITIONS_ONLY) q.outcome = Outcome.ADDITIONS;
        else q.outcome = q.limited ? Outcome.NOT_COMPARED : first ? Outcome.FIRST : Outcome.BASELINE;

        boolean uncertain = st.complete != 1;
        switch (q.outcome) {
            case KEPT_SAVED, ADDITIONS -> {
                // the saved list stays; what this load newly lists is certainly there
                Map<String, String[]> out = new LinkedHashMap<>(saved);
                for (Map.Entry<String, String[]> e : now.entrySet()) {
                    if (saved.containsKey(e.getKey())) continue;
                    if (missing.remove(e.getKey()) != null) q.relisted++;
                    else gained(changes, q, ts, previous, newType, e, uncertain);
                    out.put(e.getKey(), e.getValue());
                }
                return out;
            }
            case FULL, REBASELINED -> {
                for (Map.Entry<String, String[]> e : now.entrySet()) {
                    if (saved.containsKey(e.getKey())) continue;
                    if (missing.remove(e.getKey()) != null) q.relisted++; // not listed last time: no change
                    else if (q.outcome == Outcome.REBASELINED) q.filled++;
                    else gained(changes, q, ts, previous, newType, e, uncertain);
                }
                // the saved list's accounts this load didn't list, then the earlier not-listed ones
                long savedTs = st.ts > 0 ? st.ts : previous;
                List<String> goneIds = new ArrayList<>();
                List<Missing> goneMissing = new ArrayList<>();
                for (Map.Entry<String, String[]> e : saved.entrySet()) {
                    if (now.containsKey(e.getKey())) continue;
                    String[] u = e.getValue();
                    goneIds.add(e.getKey());
                    goneMissing.add(new Missing(u != null && u.length > 0 ? u[0] : null, u != null && u.length > 1 ? u[1] : null,
                            savedTs, ts));
                }
                for (Map.Entry<String, Missing> e : missing.entrySet()) {
                    if (now.containsKey(e.getKey()) || saved.containsKey(e.getKey())) continue;
                    goneIds.add(e.getKey());
                    goneMissing.add(e.getValue());
                }
                int need = WatchlistPolicy.strikesNeeded(q.passes, q.repeated);
                for (int i = 0; i < goneIds.size(); i++) {
                    String id = goneIds.get(i);
                    Missing m = goneMissing.get(i);
                    boolean fresh = m.checks == 0;
                    m.checks++;
                    if (q.complete) m.strikes++;
                    if (WatchlistPolicy.confirmed(m.strikes, need, m.checks, m.firstMissed, ts)) {
                        missing.remove(id);
                        changes.add(new Event(ts, m.lastSeen, goneType, id, m.username, m.fullName));
                        q.gone++;
                    } else {
                        missing.put(id, m);
                        notListed.add(new Event(ts, m.lastSeen, missType, id, m.username, m.fullName));
                        q.notListed++;
                        if (fresh) q.newlyMissing++;
                    }
                }
                saved(st, q, ts);
                return new LinkedHashMap<>(now);
            }
            default -> {
                // FIRST, BASELINE, NOT_COMPARED: this list is the baseline (marked when limited)
                missing.clear();
                saved(st, q, ts);
                return new LinkedHashMap<>(now);
            }
        }
    }

    private static void gained(List<Event> changes, ListCheck q, long ts, long previous, String type,
                               Map.Entry<String, String[]> e, boolean uncertain) {
        changes.add(event(ts, previous, type, e.getKey(), e.getValue(), uncertain));
        q.gained++;
        if (uncertain) q.uncertain++;
    }

    /** The loaded list becomes the saved one. */
    private static void saved(ListState st, ListCheck q, long ts) {
        st.ts = ts;
        st.expected = q.expected;
        st.complete = q.complete ? 1 : 0;
        st.limited = q.limited;
    }

    /**
     * Marks the changes of the check at {@code ts} as unreliable: its events (changes and
     * not-listed entries) leave the log and the check is remembered as marked; the saved lists stay
     * as they are (an older list is never restored). Returns how many changes were removed.
     */
    public static int dropCheckEvents(Profile p, long ts) {
        int changes = 0;
        List<Event> keep = new ArrayList<>();
        for (Event e : p.events) {
            if (e.ts != ts) keep.add(e);
            else if (isChange(e.type)) changes++;
        }
        p.events = keep;
        if (!p.unreliable.contains(ts)) p.unreliable.add(0, ts);
        while (p.unreliable.size() > MAX_CHECKS) p.unreliable.remove(p.unreliable.size() - 1);
        return changes;
    }

    /**
     * The counts Instagram showed for {@code p} around this analysis become its baseline. When
     * they aren't known the baseline is cleared, so a header is never compared with an older
     * analysis's counts as if they were the last one's.
     */
    static void applyBaseline(Profile p, long now) {
        int[] c = null;
        CountSource source = countSource;
        try {
            if (source != null && p.id != null) c = source.counts(p.id, now);
        } catch (Throwable ignored) {
        }
        boolean known = c != null && c.length == 2 && c[0] >= 0 && c[1] >= 0;
        p.baseFollowers = known ? c[0] : -1;
        p.baseFollowing = known ? c[1] : -1;
        p.baseAt = known ? now : 0;
    }

    /** Index row for {@code p} (keeps the date it was added). */
    public static void touch(List<Entry> index, Profile p, long now) {
        Entry e = find(index, p.id);
        if (e == null) {
            e = new Entry();
            e.id = p.id;
            e.added = now;
        } else index.remove(e);
        if (p.username != null) e.username = p.username;
        e.lastCheck = p.lastCheck;
        e.followers = p.lastCheck > 0 ? p.followers.size() : -1;
        e.following = p.lastCheck > 0 ? p.following.size() : -1;
        e.baseFollowers = p.baseFollowers;
        e.baseFollowing = p.baseFollowing;
        e.baseAt = p.baseAt;
        e.events = changeCount(p.events);
        index.add(0, e); // most recently analyzed first
        while (index.size() > MAX_PROFILES) index.remove(index.size() - 1);
    }

    /** Updates {@code p}'s index row after its log changed without an analysis (keeps its place). */
    public static void recount(List<Entry> index, Profile p) {
        Entry e = find(index, p.id);
        if (e != null) e.events = changeCount(p.events);
    }

    public static Entry find(List<Entry> index, String id) {
        for (Entry e : index) if (e.id.equals(id)) return e;
        return null;
    }

    // ---- storage (Instagram's private files, per logged-in account) ----

    private static File indexFile(Context ctx, String self) {
        return new File(ctx.getFilesDir(), "ie_watchlist_" + self + ".json");
    }

    private static File profileFile(Context ctx, String self, String id) {
        return new File(ctx.getFilesDir(), "ie_watch_" + self + "_" + id + ".json");
    }

    private static File selfFile(Context ctx) {
        return new File(ctx.getFilesDir(), "ie_watchlist_self");
    }

    private static volatile String lastSelf;
    private static volatile long indexVersion;

    /** Bumped whenever an index file is written or a profile removed, so in-memory copies of
     *  the index (ProfileCountDeltaHook's baselines) know to reload it. */
    public static long indexVersion() {
        return indexVersion;
    }

    /** The account last seen logged in (from a list's session argument), so the menu can find its
     *  watchlist; kept on disk across restarts. */
    public static void setSelf(Context ctx, String self) {
        if (self == null || self.equals(lastSelf)) return;
        lastSelf = self;
        new Thread(() -> {
            try { write(selfFile(ctx), self); } catch (Throwable ignored) {}
        }).start();
    }

    public static String self(Context ctx) {
        if (lastSelf != null) return lastSelf;
        try {
            File f = selfFile(ctx);
            if (f.exists()) lastSelf = new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8).trim();
        } catch (Throwable ignored) {}
        return lastSelf != null && !lastSelf.isEmpty() ? lastSelf : null;
    }

    public static synchronized List<Entry> loadIndex(Context ctx, String self) {
        List<Entry> out = new ArrayList<>();
        try {
            File f = indexFile(ctx, self);
            if (!f.exists()) return out;
            JSONArray a = new JSONObject(read(f)).optJSONArray("profiles");
            if (a == null) return out;
            for (int i = 0; i < a.length(); i++) {
                JSONObject o = a.getJSONObject(i);
                Entry e = new Entry();
                e.id = o.optString("id");
                e.username = o.optString("u", null);
                e.added = o.optLong("added");
                e.lastCheck = o.optLong("last");
                e.followers = o.optInt("fc", -1);
                e.following = o.optInt("gc", -1);
                // absent in indexes written before count baselines existed
                e.baseFollowers = o.optInt("bf", -1);
                e.baseFollowing = o.optInt("bg", -1);
                e.baseAt = o.optLong("bt");
                e.events = o.optInt("ev");
                if (!e.id.isEmpty()) out.add(e);
            }
        } catch (Throwable ignored) {
        }
        return out;
    }

    public static synchronized void saveIndex(Context ctx, String self, List<Entry> index) throws Exception {
        JSONArray a = new JSONArray();
        for (Entry e : index) {
            JSONObject o = new JSONObject();
            o.put("id", e.id);
            if (e.username != null) o.put("u", e.username);
            o.put("added", e.added);
            o.put("last", e.lastCheck);
            o.put("fc", e.followers);
            o.put("gc", e.following);
            if (e.baseAt > 0) {
                o.put("bf", e.baseFollowers);
                o.put("bg", e.baseFollowing);
                o.put("bt", e.baseAt);
            }
            o.put("ev", e.events);
            a.put(o);
        }
        JSONObject root = new JSONObject();
        root.put("profiles", a);
        write(indexFile(ctx, self), root.toString());
        indexVersion++;
    }

    /** Adds {@code id} to the watchlist (no snapshot yet) if it isn't there. */
    public static synchronized void add(Context ctx, String self, String id, String username) {
        try {
            List<Entry> index = loadIndex(ctx, self);
            Entry e = find(index, id);
            if (e != null) {
                if (username != null && !username.equals(e.username)) { e.username = username; saveIndex(ctx, self, index); }
                return;
            }
            e = new Entry();
            e.id = id;
            e.username = username;
            e.added = System.currentTimeMillis();
            index.add(0, e);
            while (index.size() > MAX_PROFILES) index.remove(index.size() - 1);
            saveIndex(ctx, self, index);
        } catch (Throwable ignored) {
        }
    }

    public static synchronized void remove(Context ctx, String self, String id) {
        try {
            List<Entry> index = loadIndex(ctx, self);
            Entry e = find(index, id);
            if (e != null) { index.remove(e); saveIndex(ctx, self, index); }
        } catch (Throwable ignored) {
        }
        //noinspection ResultOfMethodCallIgnored
        profileFile(ctx, self, id).delete();
        indexVersion++;
    }

    public static synchronized Profile loadProfile(Context ctx, String self, String id) {
        Profile p = new Profile();
        p.id = id;
        try {
            File f = profileFile(ctx, self, id);
            if (!f.exists()) return p;
            JSONObject o = new JSONObject(read(f));
            p.username = o.optString("u", null);
            p.lastCheck = o.optLong("last");
            p.baseFollowers = o.optInt("bf", -1);
            p.baseFollowing = o.optInt("bg", -1);
            p.baseAt = o.optLong("bt");
            readUsers(o.optJSONObject("followers"), p.followers);
            readUsers(o.optJSONObject("following"), p.following);
            // the not-listed accounts and what the saved lists are: absent in profiles saved before
            readMissing(o.optJSONObject("mf"), p.missingFollowers);
            readMissing(o.optJSONObject("mg"), p.missingFollowing);
            readState(o.optJSONObject("sf"), p.followersState);
            readState(o.optJSONObject("sg"), p.followingState);
            JSONArray ev = o.optJSONArray("events");
            if (ev != null) {
                for (int i = 0; i < ev.length(); i++) {
                    JSONObject e = ev.getJSONObject(i);
                    p.events.add(new Event(e.optLong("ts"), e.optLong("since"), e.optString("type"),
                            e.optString("id"), e.optString("u", null), e.optString("n", null), e.optInt("q") == 1));
                }
            }
            // absent in profiles saved before the changelog
            JSONArray cs = o.optJSONArray("checks");
            if (cs != null) {
                for (int i = 0; i < cs.length(); i++) {
                    JSONObject c = cs.getJSONObject(i);
                    Check check = new Check(c.optLong("ts"), c.optLong("since"), c.optInt("first") == 1,
                            c.optInt("fc", -1), c.optInt("gc", -1), c.optInt("bf", -1), c.optInt("bg", -1),
                            c.optInt("n", -1));
                    check.followersList = readListCheck(c.optJSONObject("f"));
                    check.followingList = readListCheck(c.optJSONObject("g"));
                    p.checks.add(check);
                }
            }
            JSONArray ur = o.optJSONArray("ur");
            if (ur != null) for (int i = 0; i < ur.length(); i++) if (ur.optLong(i) > 0) p.unreliable.add(ur.optLong(i));
        } catch (Throwable ignored) {
        }
        return p;
    }

    public static synchronized void saveProfile(Context ctx, String self, Profile p) throws Exception {
        JSONArray ev = new JSONArray();
        for (Event e : p.events) {
            JSONObject o = new JSONObject();
            o.put("ts", e.ts);
            o.put("since", e.since);
            o.put("type", e.type);
            o.put("id", e.id);
            if (e.username != null) o.put("u", e.username);
            if (e.fullName != null) o.put("n", e.fullName);
            if (e.uncertain) o.put("q", 1);
            ev.put(o);
        }
        JSONObject root = new JSONObject();
        root.put("id", p.id);
        if (p.username != null) root.put("u", p.username);
        root.put("last", p.lastCheck);
        if (p.baseAt > 0) {
            root.put("bf", p.baseFollowers);
            root.put("bg", p.baseFollowing);
            root.put("bt", p.baseAt);
        }
        root.put("followers", writeUsers(p.followers));
        root.put("following", writeUsers(p.following));
        root.put("mf", writeMissing(p.missingFollowers));
        root.put("mg", writeMissing(p.missingFollowing));
        root.put("sf", writeState(p.followersState));
        root.put("sg", writeState(p.followingState));
        root.put("events", ev);
        JSONArray cs = new JSONArray();
        for (Check c : p.checks) {
            JSONObject o = new JSONObject();
            o.put("ts", c.ts);
            if (c.since > 0) o.put("since", c.since);
            if (c.first) o.put("first", 1);
            o.put("fc", c.followers);
            o.put("gc", c.following);
            if (c.igFollowers >= 0) o.put("bf", c.igFollowers);
            if (c.igFollowing >= 0) o.put("bg", c.igFollowing);
            o.put("n", c.changes);
            if (c.followersList != null) o.put("f", writeListCheck(c.followersList));
            if (c.followingList != null) o.put("g", writeListCheck(c.followingList));
            cs.put(o);
        }
        root.put("checks", cs);
        if (!p.unreliable.isEmpty()) {
            JSONArray ur = new JSONArray();
            for (long ts : p.unreliable) ur.put(ts);
            root.put("ur", ur);
        }
        write(profileFile(ctx, self, p.id), root.toString());
    }

    /**
     * Marks the changes of {@code id}'s check at {@code ts} unreliable ({@link #dropCheckEvents})
     * and saves the profile and its index row. Returns how many changes were removed, -1 when
     * saving failed.
     */
    public static synchronized int markUnreliable(Context ctx, String self, String id, long ts) {
        try {
            Profile p = loadProfile(ctx, self, id);
            int removed = dropCheckEvents(p, ts);
            saveProfile(ctx, self, p);
            List<Entry> index = loadIndex(ctx, self);
            recount(index, p);
            saveIndex(ctx, self, index);
            return removed;
        } catch (Throwable t) {
            return -1;
        }
    }

    private static void readMissing(JSONObject o, Map<String, Missing> into) {
        if (o == null) return;
        for (java.util.Iterator<String> it = o.keys(); it.hasNext(); ) {
            String id = it.next();
            JSONObject m = o.optJSONObject(id);
            if (m == null) continue;
            Missing x = new Missing(m.optString("u", null), m.optString("n", null), m.optLong("ls"), m.optLong("fm"));
            x.strikes = m.optInt("s");
            x.checks = m.optInt("c");
            into.put(id, x);
        }
    }

    private static JSONObject writeMissing(Map<String, Missing> missing) throws Exception {
        JSONObject o = new JSONObject();
        for (Map.Entry<String, Missing> e : missing.entrySet()) {
            Missing m = e.getValue();
            JSONObject x = new JSONObject();
            if (m.username != null) x.put("u", m.username);
            if (m.fullName != null) x.put("n", m.fullName);
            x.put("ls", m.lastSeen);
            x.put("fm", m.firstMissed);
            x.put("s", m.strikes);
            x.put("c", m.checks);
            o.put(e.getKey(), x);
        }
        return o;
    }

    private static void readState(JSONObject o, ListState into) {
        if (o == null) return;
        into.ts = o.optLong("t");
        into.expected = o.optInt("x", -1);
        into.complete = o.optInt("c", -1);
        into.limited = o.optInt("l") == 1;
    }

    private static JSONObject writeState(ListState st) throws Exception {
        JSONObject o = new JSONObject();
        o.put("t", st.ts);
        o.put("x", st.expected);
        o.put("c", st.complete);
        if (st.limited) o.put("l", 1);
        return o;
    }

    private static ListCheck readListCheck(JSONObject o) {
        if (o == null) return null;
        ListCheck q = new ListCheck();
        q.received = o.optInt("r", -1);
        q.repeated = o.optInt("rp", -1);
        q.distinct = o.optInt("d", -1);
        q.passes = o.optInt("p", -1);
        q.lastPassAdded = o.optInt("la", -1);
        q.f1 = o.optInt("f1", -1);
        q.f2 = o.optInt("f2", -1);
        q.estMissing = o.optDouble("em", -1);
        q.expected = o.optInt("x", -1);
        q.hidden = o.optInt("h");
        q.limited = o.optInt("l") == 1;
        q.complete = o.optInt("c") == 1;
        try {
            q.outcome = Outcome.valueOf(o.optString("o", ""));
        } catch (IllegalArgumentException ignored) {
            q.outcome = null; // written by a later version, or none
        }
        q.before = o.optInt("b", -1);
        q.prevListed = o.optInt("pl", -1);
        q.prevCount = o.optInt("pc", -1);
        q.gained = o.optInt("ga");
        q.uncertain = o.optInt("un");
        q.gone = o.optInt("go");
        q.notListed = o.optInt("nl");
        q.newlyMissing = o.optInt("nm");
        q.relisted = o.optInt("rl");
        q.filled = o.optInt("fi");
        return q;
    }

    private static JSONObject writeListCheck(ListCheck q) throws Exception {
        JSONObject o = new JSONObject();
        o.put("r", q.received);
        o.put("rp", q.repeated);
        o.put("d", q.distinct);
        o.put("p", q.passes);
        o.put("la", q.lastPassAdded);
        o.put("f1", q.f1);
        o.put("f2", q.f2);
        if (q.estMissing >= 0 && !Double.isInfinite(q.estMissing) && !Double.isNaN(q.estMissing)) o.put("em", q.estMissing);
        o.put("x", q.expected);
        if (q.hidden > 0) o.put("h", q.hidden);
        if (q.limited) o.put("l", 1);
        if (q.complete) o.put("c", 1);
        if (q.outcome != null) o.put("o", q.outcome.name());
        o.put("b", q.before);
        if (q.prevListed >= 0) o.put("pl", q.prevListed);
        if (q.prevCount >= 0) o.put("pc", q.prevCount);
        o.put("ga", q.gained);
        if (q.uncertain > 0) o.put("un", q.uncertain);
        o.put("go", q.gone);
        o.put("nl", q.notListed);
        if (q.newlyMissing > 0) o.put("nm", q.newlyMissing);
        if (q.relisted > 0) o.put("rl", q.relisted);
        if (q.filled > 0) o.put("fi", q.filled);
        return o;
    }

    private static void readUsers(JSONObject users, Map<String, String[]> into) {
        if (users == null) return;
        for (java.util.Iterator<String> it = users.keys(); it.hasNext(); ) {
            String id = it.next();
            JSONArray u = users.optJSONArray(id);
            into.put(id, new String[]{u != null ? u.optString(0, null) : null, u != null ? u.optString(1, null) : null});
        }
    }

    private static JSONObject writeUsers(Map<String, String[]> users) throws Exception {
        JSONObject o = new JSONObject();
        for (Map.Entry<String, String[]> e : users.entrySet()) {
            JSONArray u = new JSONArray();
            String[] v = e.getValue();
            u.put(v != null && v.length > 0 && v[0] != null ? v[0] : JSONObject.NULL);
            u.put(v != null && v.length > 1 && v[1] != null ? v[1] : JSONObject.NULL);
            o.put(e.getKey(), u);
        }
        return o;
    }

    private static String read(File f) throws Exception {
        return new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8);
    }

    /** Write to a temp file, then rename over the target, so a crash never leaves half a file. */
    private static void write(File f, String s) throws Exception {
        File tmp = new File(f.getPath() + ".tmp");
        try (FileOutputStream out = new FileOutputStream(tmp)) {
            out.write(s.getBytes(StandardCharsets.UTF_8));
        }
        if (!tmp.renameTo(f)) throw new java.io.IOException("rename " + tmp);
    }
}
