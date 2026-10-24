package ps.reso.instaeclipse.utils.ghost;

import android.app.Activity;
import android.content.Context;
import android.content.ContextWrapper;
import android.util.AtomicFile;

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
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;

import ps.reso.instaeclipse.utils.feature.FeatureFlags;
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
        public final String by;
        public final boolean hasBody;
        public final boolean cleared;
        /** Captured while "keep in chat" was off: Instagram removed the message, nothing stayed in the chat. */
        public final boolean logOnly;
        /** What a message without text was; null when unknown (entries from before this existed). */
        public final UnsentDetail detail;
        public Entry(long time, String account, String thread, String messageId, String sender, String text) {
            this(time, account, thread, messageId, sender, "", text, true, false, false, null);
        }
        private Entry(long time, String account, String thread, String messageId, String sender, String by,
                      String text, boolean hasBody, boolean cleared, boolean logOnly, UnsentDetail detail) {
            this.time = time; this.account = account; this.thread = thread;
            this.messageId = messageId; this.cleared = cleared;
            this.sender = cleared ? "" : sender; this.text = cleared ? "" : text;
            this.by = cleared || by == null ? "" : by;
            this.hasBody = !cleared && hasBody;
            this.logOnly = logOnly;
            this.detail = cleared ? null : detail;
        }

        private Entry with(UnsentDetail detail, String by) {
            return new Entry(time, account, thread, messageId, sender, by, text, hasBody, cleared, logOnly, detail);
        }

        public String senderLabel() {
            return by.isEmpty() ? sender : "@" + by;
        }

        public boolean showsSender(String chat) {
            if (by.isEmpty()) return false;
            String name = chat == null ? "" : chat.trim();
            if (name.startsWith("@")) name = name.substring(1);
            return !by.equalsIgnoreCase(name);
        }

        /** No text and no (or only the generic) description yet. */
        private boolean lacksDetail() {
            return !cleared && !hasBody && (detail == null || detail.kind == UnsentDetail.Kind.NO_TEXT);
        }
    }

    interface Disk {
        byte[] read() throws IOException;

        void write(List<Entry> entries, List<List<String>> aliases) throws Exception;

        boolean setAside();
    }

    static final class FileDisk implements Disk {
        final AtomicFile file;

        FileDisk(AtomicFile file) { this.file = file; }

        @Override public byte[] read() throws IOException { return readBytes(file); }

        @Override public void write(List<Entry> entries, List<List<String>> aliases) throws Exception {
            UnsentLog.write(file, entries, aliases);
        }

        @Override public boolean setAside() {
            File base = file.getBaseFile();
            return base.renameTo(new File(base.getPath() + SET_ASIDE_SUFFIX));
        }
    }

    private static final String FILE_NAME = "ie_unsent_log.json";
    static final String SET_ASIDE_SUFFIX = ".corrupt";
    private static final int MAX_ENTRIES = 1000;
    private static final long VIEWER_WAIT_MS = 3000;
    static long retryNanos = TimeUnit.SECONDS.toNanos(5);

    private static final List<Entry> cache = new ArrayList<>();
    private static volatile boolean loaded = false;
    private static long retryLoadAfter;
    private static AtomicFile storage;
    private static Disk disk;
    private static Disk loading;
    private static Executor executor;
    private static ExecutorService background;
    private static int failedParses;
    private static boolean quarantined;
    private static boolean setAsideNoticeShown;
    private static boolean writeQueued;
    private static final List<Runnable> loadedListeners = new ArrayList<>();
    private static String currentAccount;
    private static UnsentMessageIndex deletions = new UnsentMessageIndex(MAX_ENTRIES);
    private static UnsentMessageIndex clearedIds = new UnsentMessageIndex(MAX_ENTRIES);
    private static long revisionBase;
    private static volatile long revision;
    /** Entries already looked up in the restart copies this process (see getForThread). */
    private static final Set<UnsentMessageIndex.Key> copyLookups = new HashSet<>();

    public static synchronized UnsentMessageIndex.Key findUnsent(String account, String thread, Iterable<String> ids) {
        return ready() ? deletions.find(account, thread, ids) : null;
    }

    public static synchronized UnsentMessageIndex.Key findCleared(String account, String thread, Iterable<String> ids) {
        return ready() && valid(account) && valid(thread) ? clearedIds.find(account, thread, ids) : null;
    }

    public static synchronized List<List<String>> getClearedIds(String account, String thread) {
        List<List<String>> result = new ArrayList<>();
        if (!ready() || !valid(account) || !valid(thread)) return result;
        for (Entry entry : cache) {
            if (!entry.cleared || !account.equals(entry.account) || !thread.equals(entry.thread)) continue;
            List<String> ids = clearedIds.aliases(key(entry));
            if (!ids.isEmpty()) result.add(ids);
        }
        return result;
    }

    public static long revision() { return revision; }

    public static synchronized void init(Context context) {
        if (storage != null) return;
        storage = new AtomicFile(new File(context.getFilesDir(), FILE_NAME));
        open(new FileDisk(storage), background());
        if (FeatureFlags.keepUnsentMessages) requestLoad();
    }

    static synchronized Executor background() {
        if (background == null) {
            background = Executors.newSingleThreadExecutor(task -> {
                Thread thread = new Thread(task, "IE-Unsent");
                thread.setDaemon(true);
                return thread;
            });
        }
        return background;
    }

    static synchronized void open(Disk target, Executor runner) {
        disk = target;
        executor = runner;
        loading = null;
        loaded = false;
        retryLoadAfter = 0;
        failedParses = 0;
        quarantined = false;
        setAsideNoticeShown = false;
        writeQueued = false;
        loadedListeners.clear();
        cache.clear();
        copyLookups.clear();
        deletions = new UnsentMessageIndex(MAX_ENTRIES);
        clearedIds = new UnsentMessageIndex(MAX_ENTRIES);
        revisionBase = Math.max(revisionBase, revision) + 1;
        revision = 0;
        UnsentLog.class.notifyAll();
    }

    private static boolean ready() {
        requestLoad();
        return loaded;
    }

    private static void requestLoad() {
        if (loaded || disk == null || loading == disk) return;
        if (retryLoadAfter != 0 && System.nanoTime() - retryLoadAfter < 0) return;
        Disk target = disk;
        loading = target;
        executor.execute(() -> load(target));
    }

    private static void load(Disk target) {
        byte[] bytes;
        try {
            bytes = target.read();
        } catch (Throwable t) {
            readFailed(target, t);
            return;
        }
        UnsentMessageIndex restored = new UnsentMessageIndex(MAX_ENTRIES);
        UnsentMessageIndex restoredCleared = new UnsentMessageIndex(MAX_ENTRIES);
        List<Entry> entries;
        try {
            entries = bytes == null ? new ArrayList<>() : parse(bytes, restored, restoredCleared);
        } catch (Throwable t) {
            parseFailed(target, t);
            runLoadedListeners();
            return;
        }
        publish(target, entries, restored, restoredCleared);
        runLoadedListeners();
    }

    private static synchronized void readFailed(Disk target, Throwable t) {
        if (disk != target || loaded) return;
        loading = null;
        retryLoadAfter = System.nanoTime() + retryNanos;
        ModuleLog.warn("(IE|UnsentLog) Could not read the saved unsent messages, trying again in a few seconds: "
                + t.getClass().getSimpleName() + ": " + t.getMessage());
        UnsentLog.class.notifyAll();
    }

    private static synchronized void parseFailed(Disk target, Throwable t) {
        if (disk != target || loaded) return;
        if (++failedParses < 2) {
            ModuleLog.detail("(IE|UnsentLog) saved list unreadable, reading it once more: " + t.getClass().getSimpleName());
            executor.execute(() -> load(target));
            return;
        }
        if (!target.setAside()) {
            loading = null;
            retryLoadAfter = System.nanoTime() + retryNanos;
            ModuleLog.warn("(IE|UnsentLog) Could not read the saved unsent messages and could not set them aside: "
                    + t.getClass().getSimpleName() + ": " + t.getMessage());
            UnsentLog.class.notifyAll();
            return;
        }
        quarantined = true;
        ModuleLog.warn("(IE|UnsentLog) Could not read the saved unsent messages; the file was set aside as "
                + FILE_NAME + SET_ASIDE_SUFFIX + " and new ones are saved again: "
                + t.getClass().getSimpleName() + ": " + t.getMessage());
        publish(target, new ArrayList<>(), new UnsentMessageIndex(MAX_ENTRIES), new UnsentMessageIndex(MAX_ENTRIES));
    }

    private static synchronized void publish(Disk target, List<Entry> entries, UnsentMessageIndex restored,
                                             UnsentMessageIndex restoredCleared) {
        if (disk != target || loaded) return;
        List<Entry> captured = new ArrayList<>(cache);
        UnsentMessageIndex capturedIds = deletions;
        cache.clear();
        cache.addAll(entries);
        deletions = restored;
        clearedIds = restoredCleared;
        loaded = true;
        loading = null;
        for (Entry entry : captured) merge(entry, capturedIds.aliases(key(entry)));
        publishRevision();
        if (!captured.isEmpty() || quarantined) persist();
        ModuleLog.detail("(IE|UnsentLog) Restored " + entries.size() + " saved entries"
                + (captured.isEmpty() ? "" : ", merged " + captured.size() + " captured while loading"));
        UnsentLog.class.notifyAll();
    }

    private static void runLoadedListeners() {
        List<Runnable> listeners;
        synchronized (UnsentLog.class) {
            if (!loaded || loadedListeners.isEmpty()) return;
            listeners = new ArrayList<>(loadedListeners);
            loadedListeners.clear();
        }
        for (Runnable listener : listeners) {
            try {
                listener.run();
            } catch (Throwable t) {
                ModuleLog.warn("(IE|UnsentLog) A task waiting for the saved unsent messages failed", t);
            }
        }
    }

    public static void whenLoaded(Runnable listener) {
        synchronized (UnsentLog.class) {
            if (!loaded) {
                loadedListeners.add(listener);
                requestLoad();
                return;
            }
        }
        listener.run();
    }

    public static boolean awaitLoaded(long timeoutMs) {
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMs);
        synchronized (UnsentLog.class) {
            requestLoad();
            while (!loaded && loading != null) {
                long left = deadline - System.nanoTime();
                if (left <= 0) break;
                try {
                    UnsentLog.class.wait(Math.max(1, TimeUnit.NANOSECONDS.toMillis(left)));
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
            return loaded;
        }
    }

    public static boolean flush(long timeoutMs) {
        Executor runner;
        synchronized (UnsentLog.class) {
            runner = executor;
        }
        if (runner == null) return true;
        FutureTask<Void> marker = new FutureTask<>(() -> null);
        runner.execute(marker);
        try {
            marker.get(timeoutMs, TimeUnit.MILLISECONDS);
            return true;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        } catch (Exception e) {
            return false;
        }
    }

    public static synchronized boolean takeSetAsideNotice() {
        if (!quarantined || setAsideNoticeShown) return false;
        setAsideNoticeShown = true;
        return true;
    }

    static byte[] readBytes(AtomicFile file) throws IOException {
        try {
            return file.readFully();
        } catch (FileNotFoundException e) {
            if (file.getBaseFile().exists()) throw e;
            return null;
        }
    }

    static List<Entry> read(AtomicFile file, UnsentMessageIndex index, UnsentMessageIndex cleared) throws Exception {
        byte[] bytes = readBytes(file);
        return bytes == null ? new ArrayList<>() : parse(bytes, index, cleared);
    }

    static List<Entry> parse(byte[] bytes, UnsentMessageIndex index, UnsentMessageIndex cleared) {
        if (!(UnsentJson.parse(new String(bytes, StandardCharsets.UTF_8)) instanceof List<?> arr)) {
            throw new IllegalArgumentException("not a list");
        }
        List<Entry> entries = new ArrayList<>();
        for (Object item : arr) {
            if (!(item instanceof Map<?, ?> o)) throw new IllegalArgumentException("not an entry");
            Entry entry = new Entry(number(o.get("t")), text(o.get("a"), ""),
                    text(o.get("th"), ""), text(o.get("id"), ""), text(o.get("s"), ""), text(o.get("by"), ""),
                    text(o.get("m"), ""), flag(o.get("body"), true), flag(o.get("cleared"), false),
                    flag(o.get("logOnly"), false), readDetail(o.get("d") instanceof Map<?, ?> d ? d : null));
            entries.add(entry);
            List<String> ids = new ArrayList<>();
            ids.add(entry.messageId);
            if (o.get("ids") instanceof List<?> aliases) for (Object alias : aliases) ids.add(text(alias, ""));
            (entry.cleared ? cleared : index).recordAliases(entry.account, entry.thread, ids);
            if (entries.size() > MAX_ENTRIES) {
                Entry removed = entries.remove(0);
                (removed.cleared ? cleared : index).remove(key(removed));
            }
        }
        return entries;
    }

    private static String text(Object value, String fallback) {
        if (value instanceof String s) return s;
        if (value instanceof Double d && d == Math.rint(d) && Math.abs(d) < 1e15) return Long.toString(d.longValue());
        return value == null ? fallback : String.valueOf(value);
    }

    private static long number(Object value) {
        if (value instanceof Number n) return n.longValue();
        if (value instanceof String s) {
            try {
                return (long) Double.parseDouble(s);
            } catch (NumberFormatException ignored) {
            }
        }
        return 0;
    }

    private static boolean flag(Object value, boolean fallback) {
        if (value instanceof Boolean b) return b;
        if (value instanceof String s) {
            if ("true".equalsIgnoreCase(s)) return true;
            if ("false".equalsIgnoreCase(s)) return false;
        }
        return fallback;
    }

    private static void persist() {
        if (!loaded || disk == null || writeQueued) return;
        writeQueued = true;
        Disk target = disk;
        executor.execute(() -> writeNow(target));
    }

    private static void writeNow(Disk target) {
        List<Entry> entries;
        List<List<String>> aliases;
        synchronized (UnsentLog.class) {
            if (disk != target) return;
            writeQueued = false;
            if (!loaded) return;
            entries = new ArrayList<>(cache);
            aliases = aliasesOf(entries, deletions, clearedIds);
        }
        try {
            target.write(entries, aliases);
        } catch (Throwable t) {
            ModuleLog.warn("(IE|UnsentLog) Could not save the unsent messages log: " + t.getClass().getSimpleName() + ": " + t.getMessage());
        }
    }

    private static List<List<String>> aliasesOf(List<Entry> entries, UnsentMessageIndex index, UnsentMessageIndex cleared) {
        List<List<String>> out = new ArrayList<>(entries.size());
        for (Entry e : entries) out.add((e.cleared ? cleared : index).aliases(key(e)));
        return out;
    }

    static void write(AtomicFile file, List<Entry> entries, UnsentMessageIndex index,
                      UnsentMessageIndex cleared) throws Exception {
        write(file, entries, aliasesOf(entries, index, cleared));
    }

    static void write(AtomicFile file, List<Entry> entries, List<List<String>> aliases) throws Exception {
        JSONArray arr = new JSONArray();
        for (int i = 0; i < entries.size(); i++) {
            Entry e = entries.get(i);
            JSONObject o = new JSONObject();
            o.put("t", e.time); o.put("th", e.thread);
            o.put("a", e.account); o.put("id", e.messageId);
            if (e.cleared) {
                o.put("cleared", true);
            } else {
                o.put("s", e.sender); o.put("m", e.text); o.put("body", e.hasBody);
                if (!e.by.isEmpty()) o.put("by", e.by);
                if (e.logOnly) o.put("logOnly", true);
                if (e.detail != null) o.put("d", writeDetail(e.detail));
            }
            o.put("ids", new JSONArray(aliases.get(i)));
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
    static UnsentDetail readDetail(Map<?, ?> d) {
        if (d == null) return null;
        return UnsentDetail.of(text(d.get("k"), ""), text(d.get("u"), null), number(d.get("ms")),
                text(d.get("x"), null));
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
        add(account, thread, ids, sender, text, logOnly, detail, null);
    }

    public static synchronized void add(String account, String thread, List<String> ids, String sender, String text,
                                        boolean logOnly, UnsentDetail detail, String by) {
        requestLoad();
        if (!record(account, thread, ids, sender, by, text, logOnly, detail, System.currentTimeMillis(), false)) return;
        publishRevision();
        persist();
    }

    private static void merge(Entry entry, List<String> aliases) {
        List<String> ids = aliases.isEmpty() ? Collections.singletonList(entry.messageId) : aliases;
        record(entry.account, entry.thread, ids, entry.sender, entry.by, entry.hasBody ? entry.text : null,
                entry.logOnly, entry.detail, entry.time, true);
    }

    private static boolean record(String account, String thread, List<String> ids, String sender, String by,
                                  String text, boolean logOnly, UnsentDetail detail, long time, boolean newerWins) {
        UnsentMessageIndex.Key cleared = valid(account) && valid(thread) ? clearedIds.find(account, thread, ids) : null;
        if (cleared != null) {
            if (ids != null) for (String id : ids) if (deletions.contains(account, thread, id)) return false;
            long revision = clearedIds.revision();
            clearedIds.recordAliases(account, thread, ids);
            return revision != clearedIds.revision();
        }
        // Ambiguous aliases must not resurrect either of two cleared messages.
        if (ids != null) for (String id : ids) if (clearedIds.contains(account, thread, id)) return false;
        long revision = deletions.revision();
        UnsentMessageIndex.Key key = deletions.recordAliases(account, thread, ids);
        if (key == null) return false;
        boolean hasBody = text != null && !text.isEmpty();
        for (int i = 0; i < cache.size(); i++) {
            Entry old = cache.get(i);
            if (!old.account.equals(key.account) || !old.thread.equals(key.thread) || !old.messageId.equals(key.message)) continue;
            String who = valid(by) && (old.by.isEmpty() || newerWins) ? by : old.by;
            // A removal is useful even when the native cache has already dropped its body. A later
            // exact row/reconcile capture may fill it in without duplicating the deletion event.
            if (hasBody && (!old.hasBody || newerWins)) {
                String label = newerWins && sender != null && !sender.isEmpty() ? sender : old.sender;
                cache.set(i, new Entry(old.time, old.account, old.thread, old.messageId, label, who, text,
                        true, false, old.logOnly, old.detail));
                return true;
            }
            if (!hasBody && improves(old, detail)) {
                cache.set(i, old.with(detail, who));
                return true;
            }
            if (!who.equals(old.by)) {
                cache.set(i, old.with(old.detail, who));
                return true;
            }
            return revision != deletions.revision();
        }
        cache.add(new Entry(time, account, thread, key.message, sender == null ? "" : sender, by,
                // Kept as before for older builds and backups; the viewer shows the detail instead.
                // Log-only mode keeps nothing in the chat, so "retained" would be wrong there.
                hasBody ? text : logOnly ? "[Unsent message]" : "[Retained message]", hasBody, false, logOnly,
                hasBody ? null : detail));
        while (cache.size() > MAX_ENTRIES) {
            Entry removed = cache.remove(0);
            (removed.cleared ? clearedIds : deletions).remove(key(removed));
        }
        return true;
    }

    private static void publishRevision() {
        revision = loaded ? revisionBase + deletions.revision() : 0;
    }

    /** A real description replaces none or the generic "without text" one, never the other way. */
    private static boolean improves(Entry old, UnsentDetail detail) {
        return detail != null && old.lacksDetail()
                && (old.detail == null || detail.kind != UnsentDetail.Kind.NO_TEXT);
    }

    /** True when the message's entry has no text and no description yet (or none exists), so a
     *  capture should take the trouble to describe it. */
    public static synchronized boolean needsDetail(String account, String thread, List<String> ids) {
        if (!ready()) return false;
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
            cache.set(index, description.getKey().with(description.getValue(), description.getKey().by));
            described++;
        }
        if (described == 0) return;
        persist();
        ModuleLog.detail("(IE|UnsentLog) described " + described + " saved entr" + (described == 1 ? "y" : "ies")
                + " from their copies");
    }

    /** All entries, newest first. */
    public static List<Entry> getAll(Context context) {
        awaitLoaded(VIEWER_WAIT_MS);
        synchronized (UnsentLog.class) {
            refreshAccount(context);
            List<Entry> out = new ArrayList<>();
            for (Entry entry : cache) if (!entry.cleared && belongsToCurrentAccount(entry)) out.add(entry);
            java.util.Collections.reverse(out);
            return out;
        }
    }

    /** Entries for one thread id, newest first; entries without text are described where possible. */
    public static List<Entry> getForThread(Context context, String thread) {
        awaitLoaded(VIEWER_WAIT_MS);
        List<CopyLookup> lookups;
        synchronized (UnsentLog.class) {
            refreshAccount(context);
            lookups = loaded ? pendingCopyLookups(threadEntries(thread)) : List.of();
        }
        Map<Entry, UnsentDetail> found = Collections.emptyMap();
        if (!lookups.isEmpty()) {
            try {
                found = readCopies(lookups);
            } catch (Throwable t) {
                ModuleLog.detail("(IE|UnsentLog) describe failed: " + t.getClass().getSimpleName());
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

    public static String clear(Context context) {
        return markCleared(viewer(context), null);
    }

    /** True when the viewer's saved entries in {@code thread} (all threads when null) include a
     *  message that was kept in the chat, which only Instagram's own local removal can take out. */
    public static boolean hasRetained(Context context, String thread) {
        awaitLoaded(VIEWER_WAIT_MS);
        return hasRetained(viewer(context), thread);
    }

    static synchronized boolean hasRetained(String account, String thread) {
        if (!ready()) return false;
        for (Entry e : cache) {
            if (!e.cleared && !e.logOnly && valid(account) && account.equals(e.account)
                    && (thread == null || thread.equals(e.thread))) return true;
        }
        return false;
    }

    /** Clear content only in this thread; return its account only after the durable write succeeds. */
    public static String clearThread(Context context, String thread) {
        String account = viewer(context);
        return thread != null ? markCleared(account, thread) : null;
    }

    private static synchronized String viewer(Context context) {
        refreshAccount(context);
        return currentAccount;
    }

    static String markCleared(String account, String thread) {
        if (!valid(account) || !awaitLoaded(VIEWER_WAIT_MS)) return null;
        FutureTask<String> task = new FutureTask<>(() -> clearNow(account, thread));
        Executor runner;
        synchronized (UnsentLog.class) {
            runner = executor;
        }
        runner.execute(task);
        try {
            return task.get();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return null;
        } catch (ExecutionException e) {
            ModuleLog.error("(IE|UnsentLog) Could not clear the unsent messages log: " + e.getCause());
            return null;
        }
    }

    private static synchronized String clearNow(String account, String thread) {
        if (!loaded) return null;
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
            updated.set(i, new Entry(entry.time, entry.account, entry.thread, entry.messageId, "", "", "", false, true, false, null));
            changed = true;
        }
        if (changed) {
            try {
                disk.write(updated, aliasesOf(updated, deletions, updatedCleared));
            } catch (Exception e) {
                ModuleLog.error("(IE|UnsentLog) Could not clear the unsent messages log: " + e.getClass().getSimpleName() + ": " + e.getMessage());
                return null;
            }
            cache.clear();
            cache.addAll(updated);
            clearedIds = updatedCleared;
            if (thread == null || valid(thread)) deletions.clear(account, thread);
            publishRevision();
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
