package ps.reso.instaeclipse.utils.ghost;

import ps.reso.instaeclipse.xposedcompat.AndroidAppHelper;
import android.content.Context;

import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

import ps.reso.instaeclipse.utils.log.ModuleLog;

/**
 * Persistent best-effort map of DM thread id -> display name (username), stored as JSON in
 * Instagram's own filesDir. Populated whenever a thread is opened (KeepUnsentMessagesHook resolves
 * the open thread's header handle), and read back by the Unsent Messages viewer so its per-chat
 * folders can be labelled with a username instead of an opaque thread id.
 */
public class ThreadNames {

    private static final String FILE_NAME = "ie_thread_names.json";
    private static final Map<String, String> cache = new HashMap<>();
    private static final java.util.Set<String> notFound = new java.util.HashSet<>();
    private static boolean loaded = false;

    private static File file() {
        Context ctx = AndroidAppHelper.currentApplication();
        if (ctx == null) return null;
        return new File(ctx.getFilesDir(), FILE_NAME);
    }

    private static synchronized void ensureLoaded() {
        if (loaded) return;
        loaded = true;
        try {
            File f = file();
            if (f == null || !f.exists()) return;
            byte[] b = new byte[(int) f.length()];
            try (java.io.FileInputStream in = new java.io.FileInputStream(f)) {
                int off = 0, n;
                while (off < b.length && (n = in.read(b, off, b.length - off)) > 0) off += n;
            }
            JSONObject o = new JSONObject(new String(b, StandardCharsets.UTF_8));
            for (java.util.Iterator<String> it = o.keys(); it.hasNext(); ) {
                String k = it.next();
                cache.put(k, o.optString(k, ""));
            }
        } catch (Throwable t) {
            ModuleLog.detail("(IE|ThreadNames) load failed: " + t.getMessage());
        }
        cleanupOnce();
    }

    /**
     * Older builds labelled threads with whatever chat header (or even message word) was on screen,
     * so one name ended up on several unrelated ids. Drop every name shared by more than one id,
     * once; the real names are learned again the next time each chat is opened.
     */
    private static void cleanupOnce() {
        try {
            Context ctx = AndroidAppHelper.currentApplication();
            if (ctx == null) return;
            File marker = new File(ctx.getFilesDir(), "ie_thread_names.v2");
            if (marker.exists()) return;
            Map<String, Integer> counts = new HashMap<>();
            for (String v : cache.values()) counts.merge(v, 1, Integer::sum);
            int before = cache.size();
            cache.values().removeIf(v -> counts.getOrDefault(v, 0) > 1);
            if (cache.size() != before) persist();
            marker.createNewFile();
            ModuleLog.detail("(IE|ThreadNames) cleanup dropped " + (before - cache.size()) + " ambiguous name(s)");
        } catch (Throwable t) {
            ModuleLog.detail("(IE|ThreadNames) cleanup failed: " + t.getMessage());
        }
    }

    private static synchronized void persist() {
        try {
            File f = file();
            if (f == null) return;
            JSONObject o = new JSONObject();
            for (Map.Entry<String, String> e : cache.entrySet()) o.put(e.getKey(), e.getValue());
            // temp file + rename: a crash mid-write keeps the previous version intact
            ps.reso.instaeclipse.utils.core.AtomicFiles.write(f, o.toString());
        } catch (Throwable t) {
            ModuleLog.detail("(IE|ThreadNames) persist failed: " + t.getMessage());
        }
    }

    /** Record a thread's display name (no-op for empty inputs or an unchanged value). */
    public static synchronized void put(String threadId, String name) {
        if (threadId == null || threadId.isEmpty() || name == null || name.trim().isEmpty()) return;
        ensureLoaded();
        String v = name.trim();
        if (v.equals(cache.get(threadId))) return;
        cache.put(threadId, v);
        persist();
    }

    /** Snapshot of every known thread id -> name (chats opened while the module was active). */
    public static synchronized Map<String, String> all() {
        ensureLoaded();
        return new java.util.LinkedHashMap<>(cache);
    }

    /** Best-known name for a thread id, or "" if unknown. */
    public static synchronized String get(String threadId) {
        if (threadId == null) return "";
        ensureLoaded();
        String v = cache.get(threadId);
        return v == null ? "" : v;
    }

    /**
     * Like {@link #get}, but when the chat was never opened (so its header name was never seen)
     * look the name up in Instagram's own thread cache (direct.db, table threads, thread_info
     * JSON) and remember it. Must run inside Instagram's process.
     */
    public static synchronized String resolve(String threadId) {
        String v = get(threadId);
        if (!v.isEmpty() || threadId == null || threadId.isEmpty()) return v;
        String found = lookupDb(java.util.Collections.singletonList(threadId)).get(threadId);
        if (found != null && !found.isEmpty()) {
            put(threadId, found);
            return found;
        }
        return "";
    }

    public static Map<String, String> resolveAll(java.util.Collection<String> threadIds) {
        Map<String, String> found = new java.util.LinkedHashMap<>();
        java.util.List<String> missing = new java.util.ArrayList<>();
        synchronized (ThreadNames.class) {
            ensureLoaded();
            for (String id : threadIds) {
                if (id == null || id.isEmpty() || found.containsKey(id) || missing.contains(id)) continue;
                String known = cache.get(id);
                if (known != null && !known.isEmpty()) found.put(id, known);
                else if (!notFound.contains(id)) missing.add(id);
            }
        }
        if (missing.isEmpty()) return found;
        Map<String, String> looked = lookupDb(missing);
        synchronized (ThreadNames.class) {
            boolean changed = false;
            for (String id : missing) {
                String name = looked.get(id);
                if (name == null || name.trim().isEmpty()) {
                    notFound.add(id);
                    continue;
                }
                name = name.trim();
                found.put(id, name);
                if (!name.equals(cache.get(id))) {
                    cache.put(id, name);
                    changed = true;
                }
            }
            if (changed) persist();
        }
        return found;
    }

    private static Map<String, String> lookupDb(java.util.Collection<String> threadIds) {
        Map<String, String> names = new HashMap<>();
        Context ctx = AndroidAppHelper.currentApplication();
        if (ctx == null) return names;
        File db = ctx.getDatabasePath("direct.db");
        if (!db.exists()) return names;
        try (android.database.sqlite.SQLiteDatabase d = android.database.sqlite.SQLiteDatabase.openDatabase(
                db.getPath(), null, android.database.sqlite.SQLiteDatabase.OPEN_READONLY)) {
            for (String threadId : threadIds) {
                try (android.database.Cursor c = d.rawQuery(
                        "SELECT thread_info FROM threads WHERE thread_id = ? LIMIT 1", new String[]{threadId})) {
                    if (!c.moveToFirst()) continue;
                    String raw = c.getType(0) == android.database.Cursor.FIELD_TYPE_BLOB
                            ? new String(c.getBlob(0), StandardCharsets.UTF_8) : c.getString(0);
                    String name = nameOf(new JSONObject(raw));
                    if (name != null) names.put(threadId, name);
                } catch (Throwable t) {
                    ModuleLog.detail("(IE|ThreadNames) db lookup failed: " + t.getMessage());
                }
            }
        } catch (Throwable t) {
            ModuleLog.detail("(IE|ThreadNames) db lookup failed: " + t.getMessage());
        }
        return names;
    }

    private static String nameOf(JSONObject info) {
        String title = info.optString("thread_title", "").trim();
        if (!title.isEmpty()) return title;
        java.util.List<String> names = new java.util.ArrayList<>();
        org.json.JSONArray users = info.optJSONArray("recipients");
        if (users != null) {
            for (int i = 0; i < users.length(); i++) {
                JSONObject u = users.optJSONObject(i);
                if (u == null) continue;
                String n = u.optString("full_name", "").trim();
                if (n.isEmpty()) n = u.optString("username", "").trim();
                if (!n.isEmpty()) names.add(n);
            }
        }
        return names.isEmpty() ? null : String.join(", ", names);
    }
}
