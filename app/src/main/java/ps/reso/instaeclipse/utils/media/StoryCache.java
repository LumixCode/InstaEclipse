package ps.reso.instaeclipse.utils.media;

import ps.reso.instaeclipse.xposedcompat.AndroidAppHelper;
import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.util.AtomicFile;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.IntConsumer;

import ps.reso.instaeclipse.utils.core.StartupIdle;
import ps.reso.instaeclipse.utils.download.DownloadEngine;
import ps.reso.instaeclipse.utils.feature.FeatureFlags;
import ps.reso.instaeclipse.utils.log.ModuleLog;

/**
 * Local cache of viewed stories, so they stay viewable after they expire or the poster deletes
 * them. Modeled on UnsentLog: a JSON index in Instagram's filesDir + the media files themselves
 * under filesDir/ie_stories/. Populated as the user views stories (StoryDownloadHook capture), read
 * back by the in-IG viewer (DialogUtils). Pruned by the selected retention and a total-size cap.
 */
public class StoryCache {

    private static final String INDEX = "ie_story_cache.json";
    private static final String DIR = "ie_stories";
    private static final long MAX_BYTES = 500L * 1024 * 1024;    // 500 MB cap
    private static final long STARTUP_PRUNE_DELAY_MS = 10_000L;

    public static class Entry {
        public final String id, author, mime, path;
        public final boolean video;
        public final long at;          // captured-at epoch ms
        public final long expiringAt;  // IG's expiring_at epoch ms (0 = unknown)
        Entry(String id, String author, String mime, boolean video, String path, long at, long expiringAt) {
            this.id = id; this.author = author; this.mime = mime; this.video = video; this.path = path;
            this.at = at; this.expiringAt = expiringAt;
        }
        /** True once past IG's expiry (the story would no longer be in the live tray). */
        public boolean isExpired() { return expiringAt > 0 && System.currentTimeMillis() > expiringAt; }
    }

    interface Fetcher {
        String fetch(Context context, String url, File destination, String expectedMime) throws Exception;
    }

    static final class Queue {
        private final Executor executor;
        private final Map<String, Runnable> pending = new LinkedHashMap<>();
        private boolean running;
        private int coalesced, reported;
        private volatile IntConsumer onCoalesced;

        Queue(Executor executor) {
            this.executor = executor;
        }

        void onCoalesced(IntConsumer listener) {
            onCoalesced = listener;
        }

        void post(String key, Runnable task) {
            boolean start;
            synchronized (this) {
                if (pending.put(key, task) != null) coalesced++;
                start = !running;
                running = true;
            }
            if (start) executor.execute(this::drain);
        }

        synchronized int coalesced() {
            return coalesced;
        }

        void drain() {
            while (true) {
                Runnable task;
                int merged = -1;
                synchronized (this) {
                    Iterator<Runnable> it = pending.values().iterator();
                    if (!it.hasNext()) {
                        running = false;
                        if (coalesced > reported) merged = reported = coalesced;
                        task = null;
                    } else {
                        task = it.next();
                        it.remove();
                    }
                }
                if (task == null) {
                    IntConsumer listener = onCoalesced;
                    if (merged > 0 && listener != null) listener.accept(merged);
                    return;
                }
                try {
                    task.run();
                } catch (Throwable ignored) {
                }
            }
        }
    }

    private static final List<Entry> cache = new ArrayList<>();
    // Story ids being downloaded, so two binds of the same story cannot capture it twice.
    private static final Set<String> inFlight = new HashSet<>();
    private static volatile boolean loaded = false;
    // The index could not be read or set aside: keep the cache off without re-reading it per call.
    private static volatile boolean loadFailed = false;
    private static AtomicFile storage;
    private static volatile Map<String, Long> index = Collections.emptyMap();
    static volatile Fetcher fetcher = (context, url, destination, mime) ->
            DownloadEngine.fetchMedia(context, url, destination, mime, new DownloadEngine.Cancellation());
    private static final Object LOAD = new Object();
    private static final Object WRITE = new Object();
    private static long version, written;
    private static final AtomicBoolean startupPrune = new AtomicBoolean();
    private static final List<Runnable> clearDone = new ArrayList<>();
    private static final AtomicInteger counts = new AtomicInteger();
    private static ExecutorService worker;
    private static final Queue QUEUE = new Queue(StoryCache::execute);

    static {
        QUEUE.onCoalesced(count -> ModuleLog.detail("(IE|StoryCache) " + count + " repeated story requests were merged so far"));
    }

    private static synchronized void execute(Runnable task) {
        if (worker == null) {
            worker = Executors.newSingleThreadExecutor(r -> {
                Thread t = new Thread(() -> {
                    android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_BACKGROUND);
                    r.run();
                }, "IE-StoryCache");
                t.setDaemon(true);
                return t;
            });
        }
        worker.execute(task);
    }

    private static Context ctx() { return AndroidAppHelper.currentApplication(); }
    /** Per-author folder: filesDir/ie_stories/<username>/ (falls back to "unknown"). */
    private static File storyDir(String author) {
        Context c = ctx(); if (c == null) return null;
        String sub = ps.reso.instaeclipse.mods.media.DownloadFiles.safeSegment(author, "unknown");
        File d = new File(new File(c.getFilesDir(), DIR), sub);
        if (!d.exists()) d.mkdirs();
        return d;
    }

    static boolean ensureLoaded() {
        if (loaded) return true;
        synchronized (LOAD) {
            AtomicFile file;
            synchronized (StoryCache.class) {
                if (loaded) return true;
                if (loadFailed) return false;
                if (storage == null) {
                    Context context = ctx();
                    if (context == null) return false;
                    storage = new AtomicFile(new File(context.getFilesDir(), INDEX));
                }
                file = storage;
            }
            List<Entry> restored;
            try {
                restored = read(file);
            } catch (Throwable t) {
                ModuleLog.detail("(IE|StoryCache) load failed: " + t);
                setAsideUnreadableIndex(file);
                return loaded;
            }
            synchronized (StoryCache.class) {
                if (!loaded && !loadFailed && storage == file) {
                    cache.clear();
                    cache.addAll(restored);
                    loaded = true;
                    publish();
                }
                return loaded;
            }
        }
    }

    /**
     * Keeps the unreadable bytes as INDEX.corrupt and starts an empty index, so one damaged file
     * (e.g. truncated by an older non-atomic write) does not switch the cache off for good.
     */
    private static void setAsideUnreadableIndex(AtomicFile file) {
        File index = file == null ? null : file.getBaseFile();
        File corrupt = index == null ? null : new File(index.getPath() + ".corrupt");
        if (corrupt != null && index.renameTo(corrupt)) {
            synchronized (StoryCache.class) {
                cache.clear();
                loaded = true;
                publish();
            }
            ModuleLog.warn("(IE|StoryCache) The list of kept stories could not be read; it was set aside as " + corrupt.getName() + " and the cache starts empty");
            removeOrphans();
        } else {
            loadFailed = true;
            ModuleLog.error("(IE|StoryCache) The list of kept stories could not be read; stories are not kept until Instagram restarts");
        }
    }

    private static void publish() {
        Map<String, Long> map = new HashMap<>();
        for (Entry e : cache) map.put(e.id, e.at);
        index = Collections.unmodifiableMap(map);
    }

    static List<Entry> read(AtomicFile file) throws Exception {
        byte[] bytes;
        try { bytes = file.readFully(); }
        catch (FileNotFoundException e) {
            if (file.getBaseFile().exists()) throw e;
            return new ArrayList<>();
        }
        return parse(new String(bytes, StandardCharsets.UTF_8));
    }

    static List<Entry> parse(String json) throws JSONException {
        JSONArray arr = new JSONArray(json);
        List<Entry> entries = new ArrayList<>();
        for (int i = 0; i < arr.length(); i++) {
            JSONObject o = arr.getJSONObject(i);
            String path = o.optString("path");
            boolean video = o.optBoolean("video");
            String mime = o.optString("mime", "");
            entries.add(new Entry(o.optString("id"), o.optString("author"),
                    mime.isEmpty() ? mimeOfPath(path, video) : mime, video, path, o.optLong("at"), o.optLong("expiringAt")));
        }
        return entries;
    }

    private static void persist() {
        List<Entry> snapshot;
        AtomicFile file;
        long at;
        synchronized (StoryCache.class) {
            // Do not overwrite a saved index after a failed or premature read.
            if (!loaded || storage == null) return;
            snapshot = new ArrayList<>(cache);
            file = storage;
            at = ++version;
        }
        synchronized (WRITE) {
            if (at <= written) return;
            try {
                write(file, snapshot);
                written = at;
            } catch (Throwable t) { ModuleLog.warn("(IE|StoryCache) Could not save the list of kept stories: " + t.getClass().getSimpleName() + ": " + t.getMessage()); }
        }
    }

    static void write(AtomicFile file, List<Entry> entries) throws Exception {
        byte[] bytes = json(entries).getBytes(StandardCharsets.UTF_8);
        FileOutputStream out = file.startWrite();
        try {
            out.write(bytes);
            file.finishWrite(out);
        } catch (IOException | RuntimeException e) {
            file.failWrite(out);
            throw e;
        }
    }

    static String json(List<Entry> entries) throws JSONException {
        JSONArray arr = new JSONArray();
        for (Entry e : entries) {
            JSONObject o = new JSONObject();
            o.put("id", e.id); o.put("author", e.author); o.put("mime", e.mime);
            o.put("video", e.video); o.put("path", e.path); o.put("at", e.at);
            o.put("expiringAt", e.expiringAt);
            arr.put(o);
        }
        return arr.toString();
    }

    private static final String[][] TYPES = {{"image/jpeg", "jpg"}, {"image/png", "png"}, {"image/webp", "webp"},
            {"image/gif", "gif"}, {"image/avif", "avif"}, {"image/heic", "heic"}, {"image/heif", "heif"},
            {"video/mp4", "mp4"}, {"video/webm", "webm"}, {"video/quicktime", "mov"}, {"video/3gpp", "3gp"}};

    static String extension(String mime) {
        for (String[] type : TYPES) if (type[0].equals(mime)) return type[1];
        return null;
    }

    static String mimeOfPath(String path, boolean video) {
        int dot = path == null ? -1 : path.lastIndexOf('.');
        String ext = dot < 0 ? "" : path.substring(dot + 1).toLowerCase(Locale.ROOT);
        if (ext.equals("jpeg")) ext = "jpg";
        for (String[] type : TYPES) if (type[1].equals(ext)) return type[0];
        return video ? "video/mp4" : "image/jpeg";
    }

    public static String mimeOf(String path, boolean video) {
        synchronized (StoryCache.class) {
            for (Entry e : cache) if (e.path.equals(path) && e.mime != null && !e.mime.isEmpty()) return e.mime;
        }
        return mimeOfPath(path, video);
    }

    private static String fileStem(String id) {
        return id.replaceAll("[^A-Za-z0-9._-]", "_");
    }

    /** Applies the current duration to existing entries, without a timer or another download. */
    public static void refresh() {
        if (!ensureLoaded()) return;
        if (prune()) persist();
        removeOrphans();
    }

    public static void refreshAsync() {
        QUEUE.post("refresh", StoryCache::refresh);
    }

    public static void pruneAfterStart() {
        if (!startupPrune.compareAndSet(false, true)) return;
        StartupIdle.afterFirstFrame(() -> QUEUE.post("prune", () -> {
            Context c = ctx();
            if (c != null && new File(c.getFilesDir(), INDEX).exists()) refresh();
        }), STARTUP_PRUNE_DELAY_MS);
    }

    /** Drops expired entries, then oldest-first while over the size cap; deletes their files. */
    private static boolean prune() {
        List<Entry> snapshot;
        synchronized (StoryCache.class) {
            if (!loaded) return false;
            snapshot = new ArrayList<>(cache);
        }
        long now = System.currentTimeMillis();
        int hours = StoryCacheRetention.normalize(FeatureFlags.storyCacheHours);
        List<Entry> drop = new ArrayList<>();
        long[] sizes = new long[snapshot.size()];
        long total = 0;
        for (int i = 0; i < snapshot.size(); i++) {
            Entry e = snapshot.get(i);
            File mf = new File(e.path);
            if (StoryCacheRetention.expired(e.at, now, hours) || !mf.exists()) {
                drop.add(e);
                sizes[i] = -1;
            } else {
                sizes[i] = mf.length();
                total += sizes[i];
            }
        }
        // Size cap: cache is append-ordered (oldest first), so evict from the front.
        for (int i = 0; i < snapshot.size() && total > MAX_BYTES; i++) {
            if (sizes[i] < 0) continue;
            total -= sizes[i];
            drop.add(snapshot.get(i));
        }
        if (drop.isEmpty()) return false;
        List<Entry> removed = new ArrayList<>();
        synchronized (StoryCache.class) {
            for (Entry e : drop) if (cache.remove(e)) removed.add(e);
            if (!removed.isEmpty()) publish();
        }
        for (Entry e : removed) {
            //noinspection ResultOfMethodCallIgnored
            new File(e.path).delete();
        }
        return !removed.isEmpty();
    }

    public static boolean hasCached(String id) {
        if (id == null || id.isEmpty()) return false;
        if (!loaded) {
            if (!loadFailed) QUEUE.post("load", StoryCache::ensureLoaded);
            return false;
        }
        Long at = index.get(id);
        return at != null && !StoryCacheRetention.expired(at, System.currentTimeMillis(),
                StoryCacheRetention.normalize(FeatureFlags.storyCacheHours));
    }

    public static boolean has(String id) {
        return hasCached(id);
    }

    private static boolean contains(String id) {
        for (Entry e : cache) if (id.equals(e.id)) return true;
        return false;
    }

    public static void captureAsync(String id, String author, String url, boolean video, long expiringAt) {
        if (id == null || id.isEmpty() || url == null || url.isEmpty()) return;
        QUEUE.post("capture:" + id, () -> capture(id, author, url, video, expiringAt));
    }

    /**
     * Captures a story: downloads its media to the private cache dir and indexes it. Runs the
     * network I/O on the caller's thread — call from a background executor. No-op if already cached.
     */
    public static void capture(String id, String author, String url, boolean video, long expiringAt) {
        if (id == null || id.isEmpty() || url == null || url.isEmpty()) return;
        if (!ensureLoaded()) return;
        // Prune before the duplicate check: a re-viewed story whose entry expired downloads to
        // that entry's path, so pruning the stale entry later would delete the fresh file.
        if (prune()) persist();
        synchronized (StoryCache.class) {
            if (!loaded || contains(id) || !inFlight.add(id)) return;
        }
        File temp = null;
        try {
            Context c = ctx(); if (c == null) return;
            temp = File.createTempFile("ie_story_", ".bin", c.getCacheDir());
            String mime = fetcher.fetch(c, url, temp, video ? "video/mp4" : "image/jpeg");
            String ext = extension(mime);
            if (ext == null) throw new IOException("Unsupported story type " + mime);
            File dir = storyDir(author); if (dir == null) return;
            File out = new File(dir, fileStem(id) + "." + ext);
            if (out.exists() && !out.delete()) throw new IOException("A kept story could not be replaced");
            if (!temp.renameTo(out)) throw new IOException("A kept story could not be moved into place");
            synchronized (StoryCache.class) {
                if (!loaded || contains(id)) return;
                cache.add(new Entry(id, author == null ? "" : author, mime, video, out.getAbsolutePath(),
                        System.currentTimeMillis(), expiringAt));
                publish();
            }
            prune();
            persist();
            ModuleLog.probe("(IE|StoryCache) cached story " + id + " by " + author);
        } catch (Throwable t) {
            ModuleLog.warn("(IE|StoryCache) Could not keep a copy of a story: " + t.getClass().getSimpleName() + ": " + t.getMessage());
        } finally {
            if (temp != null && temp.exists() && !temp.delete())
                ModuleLog.detail("(IE|StoryCache) a temporary story file could not be removed");
            synchronized (StoryCache.class) { inFlight.remove(id); }
        }
    }

    /** Snapshot of cached stories, newest first, for the viewer. */
    public static List<Entry> entries() {
        refresh();
        List<Entry> copy;
        synchronized (StoryCache.class) { copy = new ArrayList<>(cache); }
        Collections.reverse(copy);
        return copy;
    }

    public static void clearAll() {
        if (!ensureLoaded()) return;
        List<Entry> removed;
        synchronized (StoryCache.class) {
            removed = new ArrayList<>(cache);
            cache.clear();
            publish();
        }
        persist();
        for (Entry e : removed) { //noinspection ResultOfMethodCallIgnored
            new File(e.path).delete(); }
        removeOrphans();
    }

    public static void clearAllAsync(Runnable done) {
        synchronized (clearDone) { if (done != null) clearDone.add(done); }
        QUEUE.post("clear", () -> {
            try {
                clearAll();
            } finally {
                List<Runnable> run;
                synchronized (clearDone) {
                    run = new ArrayList<>(clearDone);
                    clearDone.clear();
                }
                if (!run.isEmpty()) {
                    Handler main = new Handler(Looper.getMainLooper());
                    for (Runnable r : run) main.post(r);
                }
            }
        });
    }

    public static void countAsync(IntConsumer done) {
        QUEUE.post("count" + counts.incrementAndGet(), () -> {
            int count = 0;
            try {
                Context c = ctx();
                if (c != null && new File(c.getFilesDir(), INDEX).exists()) {
                    refresh();
                    synchronized (StoryCache.class) { count = loaded ? cache.size() : 0; }
                }
            } finally {
                int n = count;
                new Handler(Looper.getMainLooper()).post(() -> done.accept(n));
            }
        });
    }

    private static void removeOrphans() {
        Context c = ctx();
        if (c == null) return;
        List<String> keep = new ArrayList<>();
        List<String> busy = new ArrayList<>();
        synchronized (StoryCache.class) {
            if (!loaded) return;
            for (Entry e : cache) keep.add(e.path);
            for (String id : inFlight) busy.add(fileStem(id));
        }
        int removed = removeOrphans(new File(c.getFilesDir(), DIR), keep, busy);
        if (removed > 0) ModuleLog.detail("(IE|StoryCache) removed " + removed + " files and folders no kept story uses");
    }

    static int removeOrphans(File root, Collection<String> keepPaths, Collection<String> busyStems) {
        File[] authors = root.listFiles();
        if (authors == null) return 0;
        Set<String> keep = new HashSet<>();
        for (String path : keepPaths) keep.add(canonical(new File(path)));
        Set<String> busy = new HashSet<>(busyStems);
        int removed = 0;
        for (File author : authors) {
            if (!author.isDirectory()) {
                if (!keep.contains(canonical(author)) && author.delete()) removed++;
                continue;
            }
            File[] files = author.listFiles();
            if (files != null) {
                for (File f : files) {
                    String name = f.getName();
                    int dot = name.lastIndexOf('.');
                    String stem = dot > 0 ? name.substring(0, dot) : name;
                    if (f.isFile() && !keep.contains(canonical(f)) && !busy.contains(stem) && f.delete()) removed++;
                }
            }
            String[] left = author.list();
            if (left != null && left.length == 0 && author.delete()) removed++;
        }
        return removed;
    }

    private static String canonical(File f) {
        try {
            return f.getCanonicalPath();
        } catch (IOException e) {
            return f.getAbsolutePath();
        }
    }
}
