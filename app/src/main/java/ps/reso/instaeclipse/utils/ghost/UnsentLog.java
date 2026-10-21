package ps.reso.instaeclipse.utils.ghost;

import android.app.Activity;
import android.content.Context;
import android.content.ContextWrapper;
import android.util.AtomicFile;
import android.os.SystemClock;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import ps.reso.instaeclipse.utils.log.ModuleLog;

/**
 * Persistent log of observed local removals, including the current user's own unsends, captured by
 * KeepUnsentMessagesHook. Stored as JSON in Instagram's own filesDir, so it survives thread
 * refreshes and app restarts (unlike the in-thread copy, which IG re-syncs away). Read back by the
 * in-IG "Unsent Messages" viewer (DialogUtils) — both run in the Instagram process, so no
 * cross-process channel is needed.
 *
 * Each entry also records the account, thread and server/client message aliases. Older accountless entries
 * remain in the file but cannot safely be attributed to the account currently viewing it.
 * Cleared entries retain only IDs and time, so another local callback cannot restore their content.
 * A message without text keeps its old placeholder in "m" (older builds and backups read it) and
 * what it was ({@link UnsentDetail}: a reel, a voice message…) in "d".
 */
public class UnsentLog {

    public static class Entry {
        public final long time;       // when we captured it (wall clock)
        public final String thread;   // thread id it belongs to (for per-thread filtering)
        public final String sender;   // best-effort sender label (may be "")
        public final String text;
        public final String account;
        public final String messageId;
        public final boolean unsent;
        public final boolean hasBody;
        public final boolean cleared;
        /** Captured while "keep in chat" was off: Instagram removed the message, nothing stayed in the chat. */
        public final boolean logOnly;
        /** What a message without text was; null when unknown (entries from before this existed). */
        public final UnsentDetail detail;
        public Entry(long time, String account, String thread, String messageId, String sender, String text) {
            this(time, account, thread, messageId, sender, text, true, false, false, null);
        }
        private Entry(long time, String account, String thread, String messageId, String sender, String text,
                      boolean hasBody, boolean cleared, boolean logOnly, UnsentDetail detail) {
            this.time = time; this.account = account; this.thread = thread;
            this.messageId = messageId; this.unsent = true; this.cleared = cleared;
            this.sender = cleared ? "" : sender; this.text = cleared ? "" : text;
            this.hasBody = !cleared && hasBody;
            this.logOnly = logOnly;
            this.detail = cleared ? null : detail;
        }

        private Entry withDetail(UnsentDetail detail) {
            return new Entry(time, account, thread, messageId, sender, text, hasBody, cleared, logOnly, detail);
        }

        /** No text and no (or only the generic) description yet. */
        private boolean lacksDetail() {
            return !cleared && !hasBody && (detail == null || detail.kind == UnsentDetail.Kind.NO_TEXT);
        }
    }

    private static final String FILE_NAME = "ie_unsent_log.json";
    private static final int MAX_ENTRIES = 1000;

    private static final List<Entry> cache = new ArrayList<>();
    private static boolean loaded = false;
    private static long retryLoadAfter;
    private static AtomicFile storage;
    private static String currentAccount;
    private static UnsentMessageIndex deletions = new UnsentMessageIndex(MAX_ENTRIES);
    private static UnsentMessageIndex clearedIds = new UnsentMessageIndex(MAX_ENTRIES);
    private static long revisionBase;
    /** Entries already looked up in the restart copies this process (see getForThread). */
    private static final Set<UnsentMessageIndex.Key> copyLookups = new HashSet<>();

    public static synchronized boolean wasUnsent(String account, String thread, String messageId) {
        ensureLoaded();
        return deletions.contains(account, thread, messageId);
    }

    public static synchronized UnsentMessageIndex.Key findUnsent(String account, String thread, Iterable<String> ids) {
        ensureLoaded();
        return deletions.find(account, thread, ids);
    }

    public static synchronized UnsentMessageIndex.Key findCleared(String account, String thread, Iterable<String> ids) {
        ensureLoaded();
        return valid(account) && valid(thread) ? clearedIds.find(account, thread, ids) : null;
    }

    public static synchronized List<List<String>> getClearedIds(String account, String thread) {
        ensureLoaded();
        List<List<String>> result = new ArrayList<>();
        if (!valid(account) || !valid(thread)) return result;
        for (Entry entry : cache) {
            if (!entry.cleared || !account.equals(entry.account) || !thread.equals(entry.thread)) continue;
            List<String> ids = clearedIds.aliases(key(entry));
            if (!ids.isEmpty()) result.add(ids);
        }
        return result;
    }

    public static synchronized long revision() { ensureLoaded(); return revisionBase + deletions.revision(); }

    public static synchronized void init(Context context) {
        if (storage != null) return;
        storage = new AtomicFile(new File(context.getFilesDir(), FILE_NAME));
        ensureLoaded();
    }

    private static synchronized void ensureLoaded() {
        if (loaded || SystemClock.elapsedRealtime() < retryLoadAfter) return;
        try {
            if (storage == null) return;
            UnsentMessageIndex restored = new UnsentMessageIndex(MAX_ENTRIES);
            UnsentMessageIndex restoredCleared = new UnsentMessageIndex(MAX_ENTRIES);
            List<Entry> entries = read(storage, restored, restoredCleared);
            cache.clear();
            cache.addAll(entries);
            revisionBase += deletions.revision() + 1;
            deletions = restored;
            clearedIds = restoredCleared;
            loaded = true;
            ModuleLog.line("(IE|UnsentLog) Restored " + entries.size() + " saved entries");
        } catch (Throwable t) {
            retryLoadAfter = SystemClock.elapsedRealtime() + 5000;
            ModuleLog.line("(IE|UnsentLog) load failed: " + t.getMessage());
        }
    }

    static List<Entry> read(AtomicFile file, UnsentMessageIndex index, UnsentMessageIndex cleared) throws Exception {
        byte[] bytes;
        try { bytes = file.readFully(); }
        catch (FileNotFoundException e) {
            if (file.getBaseFile().exists()) throw e;
            return new ArrayList<>();
        }
        JSONArray arr = new JSONArray(new String(bytes, StandardCharsets.UTF_8));
        List<Entry> entries = new ArrayList<>();
        for (int i = 0; i < arr.length(); i++) {
            JSONObject o = arr.getJSONObject(i);
            Entry entry = new Entry(o.optLong("t"), o.optString("a", ""),
                    o.optString("th", ""), o.optString("id", ""), o.optString("s", ""),
                    o.optString("m", ""), o.optBoolean("body", true), o.optBoolean("cleared", false),
                    o.optBoolean("logOnly", false), readDetail(o.optJSONObject("d")));
            entries.add(entry);
            List<String> ids = new ArrayList<>();
            ids.add(entry.messageId);
            JSONArray aliases = o.optJSONArray("ids");
            if (aliases != null) for (int j = 0; j < aliases.length(); j++) ids.add(aliases.optString(j));
            (entry.cleared ? cleared : index).recordAliases(entry.account, entry.thread, ids);
            if (entries.size() > MAX_ENTRIES) {
                Entry removed = entries.remove(0);
                (removed.cleared ? cleared : index).remove(key(removed));
            }
        }
        return entries;
    }

    private static synchronized void persist() {
        // A failed read must never turn into an empty overwrite of the user's saved messages.
        if (!loaded) return;
        try {
            write(storage, cache, deletions, clearedIds);
        } catch (Throwable t) {
            ModuleLog.line("(IE|UnsentLog) persist failed: " + t.getMessage());
        }
    }

    static void write(AtomicFile file, List<Entry> entries, UnsentMessageIndex index,
                      UnsentMessageIndex cleared) throws Exception {
        JSONArray arr = new JSONArray();
        for (Entry e : entries) {
            JSONObject o = new JSONObject();
            o.put("t", e.time); o.put("th", e.thread);
            o.put("a", e.account); o.put("id", e.messageId);
            if (e.cleared) {
                o.put("cleared", true);
            } else {
                o.put("s", e.sender); o.put("m", e.text); o.put("body", e.hasBody);
                if (e.logOnly) o.put("logOnly", true);
                if (e.detail != null) o.put("d", writeDetail(e.detail));
            }
            o.put("ids", new JSONArray((e.cleared ? cleared : index).aliases(key(e))));
            arr.put(o);
        }
        FileOutputStream out = file.startWrite();
        try {
            out.write(arr.toString().getBytes(StandardCharsets.UTF_8));
            file.finishWrite(out);
        } catch (IOException | RuntimeException e) {
            file.failWrite(out);
            throw e;
        }
    }

    /** "d": {k: kind, u: owner handle, ms: play length, x: extra words}; null when absent or of a
     *  kind this build does not know. */
    static UnsentDetail readDetail(JSONObject d) {
        if (d == null) return null;
        return UnsentDetail.of(d.optString("k", ""), d.optString("u", null), d.optLong("ms", 0),
                d.optString("x", null));
    }

    static JSONObject writeDetail(UnsentDetail detail) throws org.json.JSONException {
        JSONObject d = new JSONObject();
        d.put("k", detail.kind.id);
        if (detail.user != null) d.put("u", detail.user);
        if (detail.durationMs > 0) d.put("ms", detail.durationMs);
        if (detail.extra != null) d.put("x", detail.extra);
        return d;
    }

    /** Capture only a known unsend, keyed by account + thread + message, never by message text. */
    public static synchronized void add(String account, String thread, String messageId, String sender, String text) {
        add(account, thread, Collections.singletonList(messageId), sender, text);
    }

    public static synchronized void add(String account, String thread, List<String> ids, String sender, String text) {
        add(account, thread, ids, sender, text, false);
    }

    /** @param logOnly the message was not kept in the chat (it stays only here); fixed at first capture. */
    public static synchronized void add(String account, String thread, List<String> ids, String sender, String text,
                                        boolean logOnly) {
        add(account, thread, ids, sender, text, logOnly, null);
    }

    /** @param detail what a message without text was; fills an entry that lacks one. */
    public static synchronized void add(String account, String thread, List<String> ids, String sender, String text,
                                        boolean logOnly, UnsentDetail detail) {
        ensureLoaded();
        if (!loaded) return;
        UnsentMessageIndex.Key cleared = findCleared(account, thread, ids);
        if (cleared != null) {
            if (ids != null) for (String id : ids) if (deletions.contains(account, thread, id)) return;
            long revision = clearedIds.revision();
            clearedIds.recordAliases(account, thread, ids);
            if (revision != clearedIds.revision()) persist();
            return;
        }
        // Ambiguous aliases must not resurrect either of two cleared messages.
        if (ids != null) for (String id : ids) if (clearedIds.contains(account, thread, id)) return;
        long revision = deletions.revision();
        UnsentMessageIndex.Key key = deletions.recordAliases(account, thread, ids);
        if (key == null) return;
        boolean hasBody = text != null && !text.isEmpty();
        for (int i = 0; i < cache.size(); i++) {
            Entry old = cache.get(i);
            if (!old.account.equals(key.account) || !old.thread.equals(key.thread) || !old.messageId.equals(key.message)) continue;
            // A removal is useful even when the native cache has already dropped its body. A later
            // exact row/reconcile capture may fill it in without duplicating the deletion event.
            if (!old.hasBody && hasBody) {
                cache.set(i, new Entry(old.time, old.account, old.thread, old.messageId, old.sender, text,
                        true, false, old.logOnly, old.detail));
                persist();
            } else if (!hasBody && improves(old, detail)) {
                cache.set(i, old.withDetail(detail));
                persist();
            } else if (revision != deletions.revision()) persist();
            return;
        }
        cache.add(new Entry(System.currentTimeMillis(), account, thread, key.message, sender == null ? "" : sender,
                // Kept as before for older builds and backups; the viewer shows the detail instead.
                // Log-only mode keeps nothing in the chat, so "retained" would be wrong there.
                hasBody ? text : logOnly ? "[Unsent message]" : "[Retained message]", hasBody, false, logOnly,
                hasBody ? null : detail));
        while (cache.size() > MAX_ENTRIES) {
            Entry removed = cache.remove(0);
            (removed.cleared ? clearedIds : deletions).remove(key(removed));
        }
        persist();
    }

    /** A real description replaces none or the generic "without text" one, never the other way. */
    private static boolean improves(Entry old, UnsentDetail detail) {
        return detail != null && old.lacksDetail()
                && (old.detail == null || detail.kind != UnsentDetail.Kind.NO_TEXT);
    }

    /** True when the message's entry has no text and no description yet (or none exists), so a
     *  capture should take the trouble to describe it. */
    public static synchronized boolean needsDetail(String account, String thread, List<String> ids) {
        ensureLoaded();
        if (!loaded) return false;
        UnsentMessageIndex.Key key = deletions.find(account, thread, ids);
        if (key == null) return true;
        for (Entry e : cache) {
            if (e.account.equals(key.account) && e.thread.equals(key.thread) && e.messageId.equals(key.message)) {
                return e.lacksDetail();
            }
        }
        return true;
    }

    /** An entry logged before descriptions existed and the ids its restart copy may be saved under. */
    private record CopyLookup(Entry entry, List<String> ids) {}

    /** The entries to describe from their restart copy, each once per process. Call under the lock. */
    private static List<CopyLookup> pendingCopyLookups(List<Entry> shown) {
        List<CopyLookup> out = new ArrayList<>();
        for (Entry entry : shown) {
            if (entry.cleared || entry.hasBody || entry.detail != null) continue;
            UnsentMessageIndex.Key key = key(entry);
            if (!copyLookups.add(key)) continue;
            List<String> ids = new ArrayList<>(deletions.aliases(key));
            if (!ids.contains(entry.messageId)) ids.add(0, entry.messageId);
            out.add(new CopyLookup(entry, ids));
        }
        return out;
    }

    /** Reads the restart copies: disk I/O, so never under the lock (the removal hook logs through
     *  it on Instagram's thread). A copy exists only for a message that was captured, so without
     *  text it is at least "a message without text". */
    private static Map<Entry, UnsentDetail> readCopies(List<CopyLookup> lookups) {
        Map<Entry, UnsentDetail> found = new IdentityHashMap<>();
        for (CopyLookup lookup : lookups) {
            Entry entry = lookup.entry();
            UnsentMessageStore.Saved copy = UnsentMessageStore.find(entry.account, entry.thread, lookup.ids());
            if (copy == null) continue;
            UnsentDetail detail = UnsentDetail.fromMessageJson(new String(copy.message, StandardCharsets.UTF_8));
            found.put(entry, detail != null ? detail : UnsentDetail.NO_TEXT);
        }
        return found;
    }

    /** Stores what readCopies found for entries still in the log unchanged. Call under the lock. */
    private static void applyDescriptions(Map<Entry, UnsentDetail> found) {
        int described = 0;
        for (Map.Entry<Entry, UnsentDetail> description : found.entrySet()) {
            int index = cache.indexOf(description.getKey());
            if (index < 0) continue; // cleared, filled in or dropped meanwhile
            cache.set(index, description.getKey().withDetail(description.getValue()));
            described++;
        }
        if (described == 0) return;
        persist();
        ModuleLog.line("(IE|UnsentLog) described " + described + " saved entr" + (described == 1 ? "y" : "ies")
                + " from their copies");
    }

    /** All entries, newest first. */
    public static synchronized List<Entry> getAll(Context context) {
        refreshAccount(context);
        ensureLoaded();
        List<Entry> out = new ArrayList<>();
        for (Entry entry : cache) if (!entry.cleared && belongsToCurrentAccount(entry)) out.add(entry);
        java.util.Collections.reverse(out);
        return out;
    }

    /** Entries for one thread id, newest first; entries without text are described where possible. */
    public static List<Entry> getForThread(Context context, String thread) {
        List<CopyLookup> lookups;
        synchronized (UnsentLog.class) {
            refreshAccount(context);
            ensureLoaded();
            lookups = loaded ? pendingCopyLookups(threadEntries(thread)) : List.of();
        }
        Map<Entry, UnsentDetail> found = Collections.emptyMap();
        if (!lookups.isEmpty()) {
            try {
                found = readCopies(lookups);
            } catch (Throwable t) {
                ModuleLog.line("(IE|UnsentLog) describe failed: " + t.getClass().getSimpleName());
            }
        }
        synchronized (UnsentLog.class) {
            if (!found.isEmpty()) applyDescriptions(found);
            List<Entry> out = threadEntries(thread);
            java.util.Collections.reverse(out);
            return out;
        }
    }

    private static List<Entry> threadEntries(String thread) {
        List<Entry> out = new ArrayList<>();
        for (Entry e : cache) if (!e.cleared && belongsToCurrentAccount(e) && e.thread.equals(thread)) out.add(e);
        return out;
    }

    public static synchronized String clear(Context context) {
        refreshAccount(context);
        return markCleared(currentAccount, null);
    }

    /** True when the viewer's saved entries in {@code thread} (all threads when null) include a
     *  message that was kept in the chat, which only Instagram's own local removal can take out. */
    public static synchronized boolean hasRetained(Context context, String thread) {
        refreshAccount(context);
        return hasRetained(currentAccount, thread);
    }

    static synchronized boolean hasRetained(String account, String thread) {
        ensureLoaded();
        for (Entry e : cache) {
            if (!e.cleared && !e.logOnly && valid(account) && account.equals(e.account)
                    && (thread == null || thread.equals(e.thread))) return true;
        }
        return false;
    }

    /** Clear content only in this thread; return its account only after the durable write succeeds. */
    public static synchronized String clearThread(Context context, String thread) {
        refreshAccount(context);
        return valid(thread) ? markCleared(currentAccount, thread) : null;
    }

    static synchronized String markCleared(String account, String thread) {
        ensureLoaded();
        if (!loaded || !valid(account) || (thread != null && !valid(thread))) return null;
        boolean changed = false;
        List<Entry> updated = new ArrayList<>(cache);
        UnsentMessageIndex updatedCleared = new UnsentMessageIndex(MAX_ENTRIES);
        for (int i = 0; i < cache.size(); i++) {
            Entry entry = cache.get(i);
            if (entry.cleared) {
                updatedCleared.recordAliases(entry.account, entry.thread, clearedIds.aliases(key(entry)));
                continue;
            }
            if (!account.equals(entry.account)
                    || (thread != null && !thread.equals(entry.thread))) continue;
            updatedCleared.recordAliases(account, entry.thread, deletions.aliases(key(entry)));
            updated.set(i, new Entry(entry.time, entry.account, entry.thread, entry.messageId, "", "", false, true, false, null));
            changed = true;
        }
        if (changed) {
            try {
                write(storage, updated, deletions, updatedCleared);
            } catch (Exception e) {
                ModuleLog.line("(IE|UnsentLog) clear failed: " + e.getMessage());
                return null;
            }
            cache.clear();
            cache.addAll(updated);
            clearedIds = updatedCleared;
            deletions.clear(account, thread);
        }
        return account;
    }

    private static UnsentMessageIndex.Key key(Entry entry) {
        return new UnsentMessageIndex.Key(entry.account, entry.thread, entry.messageId);
    }

    private static boolean valid(String value) { return value != null && !value.isEmpty(); }

    private static boolean belongsToCurrentAccount(Entry entry) {
        // Old records lacked account identity. Preserve the file, but never attribute them to a
        // guessed account or display another account's messages after an account switch.
        return currentAccount != null && !currentAccount.isEmpty() && currentAccount.equals(entry.account);
    }

    private static void refreshAccount(Context context) {
        // Use the viewer's actual activity, including ModalActivity, not the last MainActivity
        // observed by another hook or the account that most recently bound a message.
        currentAccount = null;
        for (int i = 0; context != null && i < 10; i++) {
            if (context instanceof Activity) {
                currentAccount = UnsentIdentity.account(context);
                return;
            }
            if (!(context instanceof ContextWrapper wrapper)) return;
            Context base = wrapper.getBaseContext();
            if (base == context) return;
            context = base;
        }
    }
}
