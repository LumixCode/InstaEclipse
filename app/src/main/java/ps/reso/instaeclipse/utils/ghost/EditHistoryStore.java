package ps.reso.instaeclipse.utils.ghost;

import android.content.Context;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;

import ps.reso.instaeclipse.mods.media.DataBackup;
import ps.reso.instaeclipse.utils.core.AtomicFiles;
import ps.reso.instaeclipse.utils.log.ModuleLog;
import ps.reso.instaeclipse.xposedcompat.AndroidAppHelper;

public final class EditHistoryStore {

    public static final class Version {
        public final String text;
        public final long time;
        public final boolean original;
        public final boolean noticed;

        public Version(String text, long time, boolean original, boolean noticed) {
            this.text = text; this.time = Math.max(0, time); this.original = original; this.noticed = noticed;
        }

        boolean sameAs(Version other) {
            return text.equals(other.text) && time == other.time
                    && original == other.original && noticed == other.noticed;
        }
    }

    private static final class Entry {
        String account, thread;
        final LinkedHashSet<String> ids = new LinkedHashSet<>();
        final List<Version> versions = new ArrayList<>();
        long updated;
    }

    public static final int MAX_MESSAGES = 500;
    public static final int MAX_VERSIONS = 20;
    public static final int MAX_CHARS = 4000;

    private static final String FILE_NAME = "ie_edit_history.json";
    private static final EditHistoryStore SHARED = new EditHistoryStore(MAX_MESSAGES, MAX_VERSIONS);
    private static File file;
    private static boolean loaded;

    private final int capacity, maxVersions;
    private final List<Entry> entries = new ArrayList<>();

    public EditHistoryStore(int capacity, int maxVersions) {
        if (capacity < 1 || maxVersions < 2) throw new IllegalArgumentException("bounds");
        this.capacity = capacity;
        this.maxVersions = maxVersions;
    }

    public static String cut(String text) {
        return text == null || text.length() <= MAX_CHARS ? text : text.substring(0, MAX_CHARS);
    }

    public synchronized boolean recordEdit(String account, String thread, Collection<String> ids, String before,
                                           String after, boolean beforeIsOriginal, long sentAt, long now) {
        LinkedHashSet<String> keys = clean(ids);
        if (keys.isEmpty() || empty(before) || empty(after)) return false;
        before = cut(before);
        after = cut(after);
        if (before.equals(after)) return false;
        Entry entry = match(account, thread, keys);
        if (entry == null) {
            entry = new Entry();
            entry.versions.add(new Version(before, beforeIsOriginal ? sentAt : 0, beforeIsOriginal, false));
            entry.versions.add(new Version(after, now, false, true));
            entries.add(entry);
        } else {
            String last = entry.versions.isEmpty() ? null : entry.versions.get(entry.versions.size() - 1).text;
            if (after.equals(last)) return claim(entry, account, thread, keys);
            if (!before.equals(last)) {
                entry.versions.add(new Version(before, 0, beforeIsOriginal && entry.versions.isEmpty(), false));
            }
            entry.versions.add(new Version(after, now, false, true));
        }
        claim(entry, account, thread, keys);
        touched(entry, now);
        return true;
    }

    public synchronized boolean mergeServer(String account, String thread, Collection<String> ids,
                                            List<Version> server, boolean complete, long now) {
        LinkedHashSet<String> keys = clean(ids);
        if (keys.isEmpty()) return false;
        List<Version> sent = new ArrayList<>();
        if (server != null) {
            for (Version v : server) {
                if (v == null || empty(v.text)) continue;
                sent.add(new Version(cut(v.text), v.time, complete && sent.isEmpty(), false));
            }
        }
        Entry entry = match(account, thread, keys);
        if (entry == null) {
            if (sent.isEmpty()) return false;
            entry = new Entry();
            entry.versions.addAll(sent);
            entries.add(entry);
            claim(entry, account, thread, keys);
            touched(entry, now);
            return true;
        }
        boolean changed = claim(entry, account, thread, keys);
        if (sent.isEmpty()) return changed;
        List<Version> merged = merge(sent, entry.versions);
        if (!same(merged, entry.versions)) {
            entry.versions.clear();
            entry.versions.addAll(merged);
            touched(entry, now);
            changed = true;
        }
        return changed;
    }

    public synchronized List<Version> versions(String account, String thread, Collection<String> ids) {
        Entry entry = match(account, thread, clean(ids));
        return entry == null ? Collections.emptyList() : new ArrayList<>(entry.versions);
    }

    public synchronized int size() { return entries.size(); }

    static List<Version> merge(List<Version> server, List<Version> local) {
        List<Version> merged = new ArrayList<>(server);
        int last = -1;
        for (Version v : local) {
            int at = indexOf(merged, v.text);
            if (at >= 0) {
                Version s = merged.get(at);
                merged.set(at, new Version(s.text, s.time > 0 ? s.time : v.time, s.original || v.original,
                        s.time > 0 ? s.noticed : v.noticed));
                last = Math.max(last, at);
                continue;
            }
            int pos = last + 1;
            if (v.time > 0) {
                while (pos < merged.size() && (merged.get(pos).time == 0 || merged.get(pos).time <= v.time)) pos++;
            }
            merged.add(pos, v);
            last = pos;
        }
        return merged;
    }

    private static int indexOf(List<Version> versions, String text) {
        for (int i = 0; i < versions.size(); i++) if (versions.get(i).text.equals(text)) return i;
        return -1;
    }

    private static boolean same(List<Version> a, List<Version> b) {
        if (a.size() != b.size()) return false;
        for (int i = 0; i < a.size(); i++) if (!a.get(i).sameAs(b.get(i))) return false;
        return true;
    }

    private Entry match(String account, String thread, LinkedHashSet<String> ids) {
        if (ids.isEmpty()) return null;
        for (int i = entries.size() - 1; i >= 0; i--) {
            Entry e = entries.get(i);
            if (Collections.disjoint(e.ids, ids)) continue;
            if (!empty(account) && !empty(e.account) && !account.equals(e.account)) continue;
            if (!empty(thread) && !empty(e.thread) && !thread.equals(e.thread)) continue;
            return e;
        }
        return null;
    }

    private static boolean claim(Entry entry, String account, String thread, Collection<String> ids) {
        boolean changed = entry.ids.addAll(ids);
        if (empty(entry.account) && !empty(account)) { entry.account = account; changed = true; }
        if (empty(entry.thread) && !empty(thread)) { entry.thread = thread; changed = true; }
        return changed;
    }

    private void touched(Entry entry, long now) {
        entry.updated = now;
        entries.remove(entry);
        entries.add(entry);
        while (entry.versions.size() > maxVersions) {
            entry.versions.remove(entry.versions.get(0).original ? 1 : 0);
        }
        while (entries.size() > capacity) entries.remove(0);
    }

    private static LinkedHashSet<String> clean(Collection<String> ids) {
        LinkedHashSet<String> out = new LinkedHashSet<>();
        if (ids != null) for (String id : ids) if (!empty(id)) out.add(id);
        return out;
    }

    private static boolean empty(String s) { return s == null || s.isEmpty(); }

    public static EditHistoryStore shared() {
        synchronized (SHARED) {
            if (!loaded) load();
        }
        return SHARED;
    }

    public static void persist() {
        if (DataBackup.restoring()) return;
        String json;
        synchronized (SHARED) {
            if (!loaded) load();
            if (file == null) return;
            try {
                json = SHARED.toJson().toString();
            } catch (Throwable t) {
                ModuleLog.warn("(IE|EditHistoryStore) Could not save the edit history: " + t.getClass().getSimpleName() + ": " + t.getMessage());
                return;
            }
        }
        AtomicFiles.writeAsync(file, json);
    }

    private static void load() {
        Context ctx = AndroidAppHelper.currentApplication();
        if (ctx == null) return;
        file = new File(ctx.getFilesDir(), FILE_NAME);
        loaded = true;
        if (!file.isFile()) return;
        try {
            byte[] b = new byte[(int) file.length()];
            try (FileInputStream in = new FileInputStream(file)) {
                int off = 0, n;
                while (off < b.length && (n = in.read(b, off, b.length - off)) > 0) off += n;
            }
            List<Entry> recorded = new ArrayList<>(SHARED.entries);
            SHARED.entries.clear();
            SHARED.fromJson(new JSONObject(new String(b, StandardCharsets.UTF_8)));
            for (Entry e : recorded) {
                SHARED.entries.removeIf(old -> !Collections.disjoint(old.ids, e.ids));
                SHARED.entries.add(e);
            }
            while (SHARED.entries.size() > SHARED.capacity) SHARED.entries.remove(0);
            ModuleLog.detail("(IE|EditHistoryStore) loaded " + SHARED.entries.size() + " message(s)");
        } catch (Throwable t) {
            ModuleLog.warn("(IE|EditHistoryStore) Could not read the saved edit history: " + t.getClass().getSimpleName() + ": " + t.getMessage());
        }
    }

    public static String merged(JSONObject phone, JSONObject backup) throws Exception {
        EditHistoryStore out = new EditHistoryStore(MAX_MESSAGES, MAX_VERSIONS);
        if (phone != null) out.fromJson(phone);
        EditHistoryStore saved = new EditHistoryStore(MAX_MESSAGES, MAX_VERSIONS);
        saved.fromJson(backup);
        List<Entry> added = new ArrayList<>();
        for (Entry e : saved.entries) if (!e.ids.isEmpty() && out.match(e.account, e.thread, e.ids) == null) added.add(e);
        out.entries.addAll(added);
        out.entries.sort(Comparator.comparingLong(e -> e.updated));
        if (out.entries.size() > out.capacity) out.entries.subList(0, out.entries.size() - out.capacity).clear();
        return out.toJson().toString();
    }

    private JSONObject toJson() throws Exception {
        JSONArray messages = new JSONArray();
        for (Entry e : entries) {
            JSONObject m = new JSONObject();
            if (!empty(e.account)) m.put("a", e.account);
            if (!empty(e.thread)) m.put("th", e.thread);
            m.put("ids", new JSONArray(e.ids));
            m.put("u", e.updated);
            JSONArray vs = new JSONArray();
            for (Version v : e.versions) {
                JSONObject o = new JSONObject();
                o.put("x", v.text);
                o.put("ts", v.time);
                if (v.original) o.put("o", true);
                if (v.noticed) o.put("n", true);
                vs.put(o);
            }
            m.put("vs", vs);
            messages.put(m);
        }
        JSONObject root = new JSONObject();
        root.put("v", 1);
        root.put("m", messages);
        return root;
    }

    private void fromJson(JSONObject root) throws Exception {
        JSONArray messages = root.optJSONArray("m");
        if (messages == null) return;
        for (int i = 0; i < messages.length(); i++) {
            JSONObject m = messages.optJSONObject(i);
            if (m == null) continue;
            Entry e = new Entry();
            e.account = m.has("a") ? m.optString("a", null) : null;
            e.thread = m.has("th") ? m.optString("th", null) : null;
            JSONArray ids = m.optJSONArray("ids");
            if (ids != null) for (int j = 0; j < ids.length(); j++) if (!empty(ids.optString(j))) e.ids.add(ids.optString(j));
            e.updated = m.optLong("u");
            JSONArray vs = m.optJSONArray("vs");
            if (vs != null) {
                for (int j = 0; j < vs.length(); j++) {
                    JSONObject o = vs.optJSONObject(j);
                    if (o == null || empty(o.optString("x"))) continue;
                    e.versions.add(new Version(o.optString("x"), o.optLong("ts"), o.optBoolean("o"), o.optBoolean("n")));
                }
            }
            if (!e.ids.isEmpty() && !e.versions.isEmpty()) entries.add(e);
        }
    }
}
