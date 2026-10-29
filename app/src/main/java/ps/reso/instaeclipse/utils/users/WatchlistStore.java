package ps.reso.instaeclipse.utils.users;

import android.app.Activity;
import android.content.Context;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import ps.reso.instaeclipse.R;
import ps.reso.instaeclipse.mods.ghost.HideChatsHook;
import ps.reso.instaeclipse.mods.ui.UIHookManager;
import ps.reso.instaeclipse.utils.core.AtomicFiles;
import ps.reso.instaeclipse.utils.log.ModuleLog;
import ps.reso.instaeclipse.utils.toast.IeToast;

public final class WatchlistStore {

    public static final String B_FOLLOWED = "b_followed";
    public static final String B_UNFOLLOWED = "b_unfollowed";
    public static final String GAINED_FOLLOWER = "gained";
    public static final String LOST_FOLLOWER = "lost";
    public static final String NOT_LISTED = "not_listed";
    public static final String B_NOT_LISTED = "b_not_listed";
    static final int MAX_EVENTS = 3000;
    static final int MAX_PROFILES = 100;
    static final int MAX_CHECKS = 500;
    static final String SET_ASIDE_SUFFIX = ".corrupt";
    private static final String TAG = "(IE|Watchlist) ";

    public static int maxEvents() {
        return MAX_EVENTS;
    }

    public static boolean isChange(String type) {
        return B_FOLLOWED.equals(type) || B_UNFOLLOWED.equals(type) || GAINED_FOLLOWER.equals(type) || LOST_FOLLOWER.equals(type);
    }

    public static int changeCount(List<Event> events) {
        int n = 0;
        for (Event e : events) if (isChange(e.type)) n++;
        return n;
    }

    public static final class Event {
        public final long ts, since;
        public final String type, id, username, fullName;
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

    public static final class Missing {
        public final String username, fullName;
        public final long lastSeen, firstMissed;
        public int strikes, checks;

        public Missing(String username, String fullName, long lastSeen, long firstMissed) {
            this.username = username;
            this.fullName = fullName;
            this.lastSeen = lastSeen;
            this.firstMissed = firstMissed;
        }
    }

    public static final class ListState {
        public long ts;
        public int expected = -1, complete = -1;
        public boolean limited;
    }

    public enum Outcome {
        FIRST,
        BASELINE,
        FULL,
        REBASELINED,
        KEPT_SAVED,
        ADDITIONS,
        NOT_COMPARED
    }

    public static final class ListCheck {
        public int received = -1, repeated = -1;
        public int distinct = -1, passes = -1, lastPassAdded = -1, f1 = -1, f2 = -1;
        public double estMissing = -1;
        public int expected = -1;
        public int hidden;
        public boolean limited;

        public boolean complete;
        public Outcome outcome;
        public int before = -1;
        public int prevListed = -1, prevCount = -1;
        public int gained, uncertain, gone, notListed, newlyMissing, relisted, filled;
    }

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

    public static final class Entry {
        public String id, username;
        public long added, lastCheck;
        public int followers = -1, following = -1, events;
        public int baseFollowers = -1, baseFollowing = -1;
        public long baseAt;
    }

    public static final class Profile {
        public String id, username;
        public long lastCheck;
        public Map<String, String[]> followers = new LinkedHashMap<>();
        public Map<String, String[]> following = new LinkedHashMap<>();
        public Map<String, Missing> missingFollowers = new LinkedHashMap<>();
        public Map<String, Missing> missingFollowing = new LinkedHashMap<>();
        public ListState followersState = new ListState(), followingState = new ListState();
        public List<Event> events = new ArrayList<>();
        public int baseFollowers = -1, baseFollowing = -1;
        public long baseAt;
        public List<Check> checks = new ArrayList<>();
        public List<Long> unreliable = new ArrayList<>();
        public long importedHistoryMtime;
        boolean unreadable;
    }

    public interface CountSource {
        int[] counts(String id, long now);
    }

    private static volatile CountSource countSource;

    public static void setCountSource(CountSource source) {
        countSource = source;
    }

    public static final class Result {
        public final boolean first;
        public final int followers, following;
        public final long previousCheck;
        public final List<Event> added;
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

        public List<Event> of(String type) {
            List<Event> out = new ArrayList<>();
            for (Event e : added) if (type.equals(e.type)) out.add(e);
            for (Event e : notListed) if (type.equals(e.type)) out.add(e);
            return out;
        }
    }

    private WatchlistStore() {}

    private static Event event(long now, long since, String type, String id, String[] u, boolean uncertain) {
        return new Event(now, since, type, id, u != null && u.length > 0 ? u[0] : null,
                u != null && u.length > 1 ? u[1] : null, uncertain);
    }

    public static Result apply(Profile p, Map<String, String[]> followers, Map<String, String[]> following,
                               ListCheck qFollowers, ListCheck qFollowing, long now) {
        boolean first = p.lastCheck == 0;
        long previous = p.lastCheck;
        ListCheck qFol = qFollowers != null ? qFollowers : new ListCheck();
        ListCheck qFing = following == null ? null : qFollowing != null ? qFollowing : new ListCheck();
        boolean header = p.baseAt > 0 && p.baseAt == p.lastCheck;
        List<Event> added = new ArrayList<>(), notListed = new ArrayList<>();
        if (following != null) {
            p.following = applyList(p.following, p.missingFollowing, p.followingState, following, qFing, first, previous,
                    header ? p.baseFollowing : -1, now, B_UNFOLLOWED, B_FOLLOWED, B_NOT_LISTED, added, notListed);
        } else if (first) {
            p.followingState.ts = -1;
        }
        p.followers = applyList(p.followers, p.missingFollowers, p.followersState, followers, qFol, first, previous,
                header ? p.baseFollowers : -1, now, LOST_FOLLOWER, GAINED_FOLLOWER, NOT_LISTED, added, notListed);
        List<Event> all = new ArrayList<>(added);
        all.addAll(notListed);
        all.addAll(p.events);
        while (all.size() > MAX_EVENTS) all.remove(all.size() - 1);
        p.events = all;
        p.lastCheck = now;
        applyBaseline(p, now);
        int followingSize = following != null ? following.size() : -1;
        Check c = new Check(now, previous, first, followers.size(), followingSize,
                p.baseFollowers, p.baseFollowing, added.size());
        c.followersList = qFol;
        c.followingList = qFing;
        List<Check> checks = new ArrayList<>();
        checks.add(c);
        checks.addAll(p.checks);
        while (checks.size() > MAX_CHECKS) checks.remove(checks.size() - 1);
        p.checks = checks;
        return new Result(first, followers.size(), followingSize, previous, added, notListed, qFol, qFing);
    }

    private static Map<String, String[]> applyList(Map<String, String[]> saved, Map<String, Missing> missing, ListState st,
                                                   Map<String, String[]> now, ListCheck q, boolean first, long previous,
                                                   int headerCount, long ts, String goneType, String newType, String missType,
                                                   List<Event> changes, List<Event> notListed) {
        q.distinct = now.size();
        q.complete = WatchlistPolicy.complete(q.passes, q.repeated, q.lastPassAdded, q.estMissing, q.expected, q.hidden,
                now.size(), q.limited);
        q.before = first || st.ts < 0 ? -1 : saved.size();
        WatchlistPolicy.DiffMode mode = WatchlistPolicy.diffMode(!first && st.ts >= 0 && !st.limited, q.limited);
        if (mode == WatchlistPolicy.DiffMode.FULL) {
            q.prevListed = saved.size() + missing.size();
            q.prevCount = st.expected >= 0 ? st.expected : headerCount;
            q.outcome = switch (WatchlistPolicy.trust(q.prevListed, q.prevCount, now.size(), q.expected)) {
                case REBASELINE -> Outcome.REBASELINED;
                case KEEP_SAVED -> Outcome.KEPT_SAVED;
                default -> Outcome.FULL;
            };
        } else if (mode == WatchlistPolicy.DiffMode.ADDITIONS_ONLY) q.outcome = Outcome.ADDITIONS;
        else q.outcome = q.limited ? Outcome.NOT_COMPARED : first || st.ts < 0 ? Outcome.FIRST : Outcome.BASELINE;

        boolean uncertain = st.complete != 1;
        switch (q.outcome) {
            case KEPT_SAVED, ADDITIONS -> {
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
                    if (missing.remove(e.getKey()) != null) q.relisted++;
                    else if (q.outcome == Outcome.REBASELINED) q.filled++;
                    else gained(changes, q, ts, previous, newType, e, uncertain);
                }
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

    private static void saved(ListState st, ListCheck q, long ts) {
        st.ts = ts;
        st.expected = q.expected;
        st.complete = q.complete ? 1 : 0;
        st.limited = q.limited;
    }

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

    public static List<String> touch(List<Entry> index, Profile p, long now) {
        Entry e = find(index, p.id);
        if (e == null) {
            e = new Entry();
            e.id = p.id;
            e.added = now;
        } else index.remove(e);
        if (p.username != null) e.username = p.username;
        e.lastCheck = p.lastCheck;
        e.followers = p.lastCheck > 0 ? p.followers.size() : -1;
        e.following = p.lastCheck > 0 && p.followingState.ts >= 0 ? p.following.size() : -1;
        e.baseFollowers = p.baseFollowers;
        e.baseFollowing = p.baseFollowing;
        e.baseAt = p.baseAt;
        e.events = changeCount(p.events);
        index.add(0, e);
        return trim(index);
    }

    private static List<String> trim(List<Entry> index) {
        List<String> evicted = new ArrayList<>();
        while (index.size() > MAX_PROFILES) evicted.add(index.remove(index.size() - 1).id);
        return evicted;
    }

    public static void recount(List<Entry> index, Profile p) {
        Entry e = find(index, p.id);
        if (e != null) e.events = changeCount(p.events);
    }

    public static Entry find(List<Entry> index, String id) {
        for (Entry e : index) if (e.id.equals(id)) return e;
        return null;
    }

    private static File indexFile(Context ctx, String self) {
        return new File(ctx.getFilesDir(), "ie_watchlist_" + self + ".json");
    }

    private static File profileFile(Context ctx, String self, String id) {
        return profileFile(ctx.getFilesDir(), self, id);
    }

    static File profileFile(File dir, String self, String id) {
        return new File(dir, "ie_watch_" + self + "_" + id + ".json");
    }

    private static File selfFile(Context ctx) {
        return new File(ctx.getFilesDir(), "ie_watchlist_self");
    }

    private static volatile String lastSelf;
    private static volatile long indexVersion;
    private static final Set<String> held = ConcurrentHashMap.newKeySet();

    public static long indexVersion() {
        return indexVersion;
    }

    public static void setSelf(Context ctx, String self) {
        if (self == null || self.equals(lastSelf)) return;
        lastSelf = self;
        AtomicFiles.writeAsync(selfFile(ctx), self);
    }

    public static String self(Context ctx) {
        String live = liveAccount(ctx);
        if (live != null && !live.isEmpty()) setSelf(ctx, live);
        return pickSelf(live, rememberedSelf(ctx));
    }

    static String pickSelf(String live, String remembered) {
        if (live != null && !live.isEmpty()) return live;
        return remembered != null && !remembered.isEmpty() ? remembered : null;
    }

    private static String rememberedSelf(Context ctx) {
        if (lastSelf != null) return lastSelf;
        try {
            File f = selfFile(ctx);
            if (f.exists()) lastSelf = new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8).trim();
        } catch (Throwable ignored) {}
        return lastSelf;
    }

    private static String liveAccount(Context ctx) {
        try {
            Activity a = ctx instanceof Activity activity ? activity : UIHookManager.topActivity();
            return a == null ? null : HideChatsHook.currentAccount(a);
        } catch (Throwable t) {
            return null;
        }
    }

    public static synchronized List<Entry> loadIndex(Context ctx, String self) {
        return readIndex(indexFile(ctx, self));
    }

    static synchronized List<Entry> readIndex(File f) {
        List<Entry> out = new ArrayList<>();
        if (!f.exists()) return out;
        try {
            JSONArray a = new JSONObject(read(f)).optJSONArray("profiles");
            for (int i = 0; a != null && i < a.length(); i++) {
                JSONObject o = a.getJSONObject(i);
                Entry e = new Entry();
                e.id = o.optString("id");
                e.username = UsersCodec.text(o, "u");
                e.added = o.optLong("added");
                e.lastCheck = o.optLong("last");
                e.followers = o.optInt("fc", -1);
                e.following = o.optInt("gc", -1);
                e.baseFollowers = o.optInt("bf", -1);
                e.baseFollowing = o.optInt("bg", -1);
                e.baseAt = o.optLong("bt");
                e.events = o.optInt("ev");
                if (!e.id.isEmpty()) out.add(e);
            }
        } catch (Throwable t) {
            setAside(f, t);
            return new ArrayList<>();
        }
        held.remove(f.getPath());
        return out;
    }

    public static synchronized void saveIndex(Context ctx, String self, List<Entry> index) throws Exception {
        writeIndex(indexFile(ctx, self), index);
    }

    static synchronized void writeIndex(File f, List<Entry> index) throws Exception {
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
        write(f, root.toString());
        indexVersion++;
    }

    public static synchronized List<String> add(Context ctx, String self, String id, String username) {
        try {
            List<Entry> index = loadIndex(ctx, self);
            Entry e = find(index, id);
            if (e != null) {
                if (username != null && !username.equals(e.username)) { e.username = username; saveIndex(ctx, self, index); }
                return new ArrayList<>();
            }
            e = new Entry();
            e.id = id;
            e.username = username;
            e.added = System.currentTimeMillis();
            index.add(0, e);
            List<String> evicted = trim(index);
            saveIndex(ctx, self, index);
            removeEvicted(ctx, self, evicted);
            return evicted;
        } catch (Throwable ignored) {
            return new ArrayList<>();
        }
    }

    public static synchronized void removeEvicted(Context ctx, String self, List<String> evicted) {
        if (evicted == null || evicted.isEmpty()) return;
        deleteProfiles(ctx.getFilesDir(), self, evicted);
        ModuleLog.warn(TAG + "Watchlist full: the oldest profile was removed (" + String.join(", ", evicted) + ")");
        try {
            IeToast.makeText(ctx, R.string.ig_pa_watch_full, Toast.LENGTH_LONG).show();
        } catch (Throwable ignored) {
        }
    }

    static synchronized void deleteProfiles(File dir, String self, List<String> ids) {
        for (String id : ids) {
            //noinspection ResultOfMethodCallIgnored
            profileFile(dir, self, id).delete();
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
        return readProfile(profileFile(ctx, self, id), id);
    }

    static synchronized Profile readProfile(File f, String id) {
        Profile p = new Profile();
        p.id = id;
        if (!f.exists()) return p;
        try {
            JSONObject o = new JSONObject(read(f));
            p.username = UsersCodec.text(o, "u");
            p.lastCheck = o.optLong("last");
            p.baseFollowers = o.optInt("bf", -1);
            p.baseFollowing = o.optInt("bg", -1);
            p.baseAt = o.optLong("bt");
            UsersCodec.read(o.optJSONObject("followers"), p.followers);
            UsersCodec.read(o.optJSONObject("following"), p.following);
            readMissing(o.optJSONObject("mf"), p.missingFollowers);
            readMissing(o.optJSONObject("mg"), p.missingFollowing);
            readState(o.optJSONObject("sf"), p.followersState);
            readState(o.optJSONObject("sg"), p.followingState);
            JSONArray ev = o.optJSONArray("events");
            if (ev != null) {
                for (int i = 0; i < ev.length(); i++) {
                    JSONObject e = ev.getJSONObject(i);
                    p.events.add(new Event(e.optLong("ts"), e.optLong("since"), e.optString("type"),
                            e.optString("id"), UsersCodec.text(e, "u"), UsersCodec.text(e, "n"), e.optInt("q") == 1));
                }
            }
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
            p.importedHistoryMtime = o.optLong("ih");
        } catch (Throwable t) {
            setAside(f, t);
            Profile unread = new Profile();
            unread.id = id;
            unread.unreadable = true;
            return unread;
        }
        held.remove(f.getPath());
        return p;
    }

    public static synchronized void saveProfile(Context ctx, String self, Profile p) throws Exception {
        writeProfile(profileFile(ctx, self, p.id), p);
    }

    static synchronized void writeProfile(File f, Profile p) throws Exception {
        if (p.unreadable) throw new IOException("not saved, " + f.getName() + " could not be read");
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
        root.put("followers", UsersCodec.write(p.followers));
        root.put("following", UsersCodec.write(p.following));
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
        if (p.importedHistoryMtime > 0) root.put("ih", p.importedHistoryMtime);
        write(f, root.toString());
    }

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
            Missing x = new Missing(UsersCodec.text(m, "u"), UsersCodec.text(m, "n"), m.optLong("ls"), m.optLong("fm"));
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
            q.outcome = null;
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

    private static String read(File f) throws Exception {
        return new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8);
    }

    private static void write(File f, String s) throws Exception {
        if (held.contains(f.getPath()) && f.exists()) throw new IOException("not saved over the unreadable " + f.getName());
        AtomicFiles.write(f, s);
    }

    private static void setAside(File f, Throwable t) {
        File aside = new File(f.getPath() + SET_ASIDE_SUFFIX);
        if (aside.exists()) aside = new File(f.getPath() + "." + System.currentTimeMillis() + SET_ASIDE_SUFFIX);
        String why = t.getClass().getSimpleName() + ": " + t.getMessage();
        if (f.renameTo(aside)) {
            held.remove(f.getPath());
            ModuleLog.warn(TAG + "Could not read " + f.getName() + ", so it was set aside as " + aside.getName()
                    + " and is not saved over: " + why);
        } else {
            held.add(f.getPath());
            ModuleLog.warn(TAG + "Could not read " + f.getName() + " or set it aside, so it is not saved over: " + why);
        }
    }
}
