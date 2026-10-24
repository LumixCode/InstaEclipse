package ps.reso.instaeclipse.utils.users;

import android.content.Context;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

public final class FollowersHistory {

    public static final String LOST = "lost";
    public static final String GAINED = "gained";

    public static final class Event {
        public final long ts, since;
        public final String type, id, username, fullName;

        public Event(long ts, long since, String type, String id, String username, String fullName) {
            this.ts = ts;
            this.since = since;
            this.type = type;
            this.id = id;
            this.username = username;
            this.fullName = fullName;
        }
    }

    public static final class State {
        public long lastCheck;
        public int expected = -1;
        public Map<String, String[]> users = new LinkedHashMap<>();
        public List<Event> events = new ArrayList<>();
        public Throwable error;
    }

    public interface ProfileSaver {
        void save(WatchlistStore.Profile p) throws Exception;
    }

    public enum Import { NONE, UNCHANGED, ERROR, IMPORTED }

    private FollowersHistory() {}

    public static boolean shouldCompare(boolean complete, boolean interrupted, boolean limited, int listed) {
        return complete && !interrupted && !limited && listed > 0;
    }

    public static File file(Context ctx, String selfId) {
        return new File(ctx.getFilesDir(), "ie_followers_history_" + selfId + ".json");
    }

    public static State load(Context ctx, String selfId) {
        return read(file(ctx, selfId));
    }

    public static State read(File f) {
        State s = new State();
        if (!f.exists()) return s;
        try {
            JSONObject o = new JSONObject(new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8));
            s.lastCheck = o.optLong("lastCheck");
            s.expected = o.optInt("exp", -1);
            JSONObject users = o.optJSONObject("users");
            if (users != null) {
                for (java.util.Iterator<String> it = users.keys(); it.hasNext(); ) {
                    String id = it.next();
                    JSONArray u = users.optJSONArray(id);
                    s.users.put(id, new String[]{text(u, 0), text(u, 1)});
                }
            }
            JSONArray ev = o.optJSONArray("events");
            if (ev != null) {
                for (int i = 0; i < ev.length(); i++) {
                    JSONObject e = ev.getJSONObject(i);
                    s.events.add(new Event(e.optLong("ts"), e.optLong("since"), e.optString("type"),
                            e.optString("id"), text(e, "u"), text(e, "n")));
                }
            }
        } catch (Throwable t) {
            State failed = new State();
            failed.error = t;
            return failed;
        }
        return s;
    }

    private static String text(JSONArray a, int i) {
        return a == null || a.isNull(i) ? null : a.optString(i, null);
    }

    private static String text(JSONObject o, String key) {
        return o.isNull(key) ? null : o.optString(key, null);
    }

    public static Import importInto(File history, WatchlistStore.Profile p, ProfileSaver saver) throws Exception {
        if (!history.exists()) return Import.NONE;
        long mtime = history.lastModified();
        if (mtime <= p.importedHistoryMtime) return Import.UNCHANGED;
        State h = read(history);
        if (h.error != null) return Import.ERROR;
        merge(h, p);
        saver.save(p);
        p.importedHistoryMtime = mtime;
        saver.save(p);
        return Import.IMPORTED;
    }

    public static int merge(State h, WatchlistStore.Profile p) {
        long saved = p.followersState.ts > 0 ? p.followersState.ts : p.lastCheck;
        if (h.lastCheck > saved && !h.users.isEmpty()) {
            boolean neverChecked = p.lastCheck == 0;
            p.followers = new LinkedHashMap<>(h.users);
            p.missingFollowers.clear();
            p.followersState.ts = h.lastCheck;
            p.followersState.expected = h.expected;
            p.followersState.complete = -1;
            p.followersState.limited = false;
            if (neverChecked) p.followingState.ts = -1;
            p.lastCheck = Math.max(p.lastCheck, h.lastCheck);
        }
        Set<String> known = new HashSet<>();
        for (WatchlistStore.Event e : p.events) known.add(key(e.id, e.type, e.ts));
        List<WatchlistStore.Event> added = new ArrayList<>();
        for (Event e : h.events) {
            String type = LOST.equals(e.type) ? WatchlistStore.LOST_FOLLOWER : GAINED.equals(e.type) ? WatchlistStore.GAINED_FOLLOWER : null;
            if (type == null || e.id == null || !known.add(key(e.id, type, e.ts))) continue;
            added.add(new WatchlistStore.Event(e.ts, e.since, type, e.id, e.username, e.fullName));
        }
        if (added.isEmpty()) return 0;
        List<WatchlistStore.Event> all = new ArrayList<>(p.events);
        all.addAll(added);
        all.sort((a, b) -> Long.compare(b.ts, a.ts));
        while (all.size() > WatchlistStore.maxEvents()) all.remove(all.size() - 1);
        p.events = all;
        return added.size();
    }

    private static String key(String id, String type, long ts) {
        return id + '\u0000' + type + '\u0000' + ts;
    }

    public static void save(Context ctx, String selfId, State s) throws Exception {
        JSONObject users = new JSONObject();
        for (Map.Entry<String, String[]> e : s.users.entrySet()) {
            JSONArray u = new JSONArray();
            u.put(e.getValue()[0] != null ? e.getValue()[0] : JSONObject.NULL);
            u.put(e.getValue().length > 1 && e.getValue()[1] != null ? e.getValue()[1] : JSONObject.NULL);
            users.put(e.getKey(), u);
        }
        JSONArray ev = new JSONArray();
        for (Event e : s.events) {
            JSONObject o = new JSONObject();
            o.put("ts", e.ts);
            o.put("since", e.since);
            o.put("type", e.type);
            o.put("id", e.id);
            if (e.username != null) o.put("u", e.username);
            if (e.fullName != null) o.put("n", e.fullName);
            ev.put(o);
        }
        JSONObject root = new JSONObject();
        root.put("lastCheck", s.lastCheck);
        root.put("users", users);
        root.put("events", ev);
        File f = file(ctx, selfId);
        File tmp = new File(f.getPath() + ".tmp");
        try (FileOutputStream out = new FileOutputStream(tmp)) {
            out.write(root.toString().getBytes(StandardCharsets.UTF_8));
        }
        if (!tmp.renameTo(f)) throw new java.io.IOException("rename " + tmp);
    }
}
