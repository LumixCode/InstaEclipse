package ps.reso.instaeclipse.utils.ghost;

import ps.reso.instaeclipse.xposedcompat.AndroidAppHelper;
import android.content.Context;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;

import ps.reso.instaeclipse.utils.log.ModuleLog;

/**
 * Persistent set of DM thread ids the user has chosen to hide from the inbox (Hide Specific Chats).
 * Stored as JSON in Instagram's own filesDir, so it survives restarts. A captured title is kept
 * alongside each id purely so the unhide manager can show a human-readable label. Runs in the
 * Instagram process (same as the inbox filter + the hide button), so no cross-process channel.
 *
 * JSON: { "<threadId>": "<title>", ... }
 */
public class HiddenThreads {

    private static final String FILE_NAME = "ie_hidden_threads.json";
    // Insertion-ordered so the unhide manager lists most-recently-hidden sensibly.
    private static final Map<String, String> map = new LinkedHashMap<>();
    private static boolean loaded = false;
    // The file is there but could not be read: not retried, and never overwritten (loaded stays false).
    private static boolean loadFailed = false;

    private static File file() {
        Context ctx = AndroidAppHelper.currentApplication();
        if (ctx == null) return null;
        return new File(ctx.getFilesDir(), FILE_NAME);
    }

    /** Loaded once a read with a context succeeds; without a context yet, the next call tries again. */
    private static synchronized void ensureLoaded() {
        if (loaded || loadFailed) return;
        File f = file();
        if (f == null) return;
        try {
            if (f.exists()) {
                byte[] b = new byte[(int) f.length()];
                try (FileInputStream in = new FileInputStream(f)) {
                    int off = 0, n;
                    while (off < b.length && (n = in.read(b, off, b.length - off)) > 0) off += n;
                }
                String json = new String(b, StandardCharsets.UTF_8).trim();
                JSONObject o = new JSONObject(json.isEmpty() ? "{}" : json);
                for (java.util.Iterator<String> it = o.keys(); it.hasNext(); ) {
                    String k = it.next();
                    map.put(k, o.optString(k, ""));
                }
            }
            loaded = true;
        } catch (Throwable t) {
            loadFailed = true;
            ModuleLog.error("(IE|HiddenThreads) Could not read the list of hidden chats; changes are not saved until Instagram restarts: "
                    + t.getClass().getSimpleName() + ": " + t.getMessage());
        }
    }

    private static synchronized void persist() {
        // Not read yet (no context) or unreadable (logged once in ensureLoaded): keep the file as is.
        if (!loaded) return;
        try {
            File f = file();
            if (f == null) return;
            JSONObject o = new JSONObject();
            for (Map.Entry<String, String> e : map.entrySet()) o.put(e.getKey(), e.getValue());
            // temp file + rename: a crash mid-write keeps the previous version intact
            ps.reso.instaeclipse.utils.core.AtomicFiles.write(f, o.toString());
        } catch (Throwable t) {
            ModuleLog.error("(IE|HiddenThreads) Could not save the list of hidden chats: " + t.getClass().getSimpleName() + ": " + t.getMessage());
        }
    }

    public static synchronized boolean isHidden(String threadId) {
        if (threadId == null || threadId.isEmpty()) return false;
        ensureLoaded();
        return map.containsKey(threadId);
    }

    /** Explicitly hides a thread. Repeated calls update its label without reversing its state. */
    public static synchronized void hide(String threadId, String title) {
        if (threadId == null || threadId.isEmpty()) return;
        ensureLoaded();
        String value = title == null ? "" : title;
        if (value.equals(map.get(threadId)) && map.containsKey(threadId)) return;
        map.put(threadId, value);
        persist();
    }

    public static synchronized void setHidden(String threadId, String title, boolean hidden) {
        if (hidden) hide(threadId, title);
        else unhide(threadId);
    }

    public static synchronized void unhide(String threadId) {
        ensureLoaded();
        if (map.remove(threadId) != null) persist();
    }

    /** Snapshot of hidden threads (id -> title) for the unhide manager. */
    public static synchronized Map<String, String> all() {
        ensureLoaded();
        return new LinkedHashMap<>(map);
    }

    public static synchronized boolean isEmpty() {
        ensureLoaded();
        return map.isEmpty();
    }
}
