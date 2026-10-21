package ps.reso.instaeclipse.utils.media;

import ps.reso.instaeclipse.xposedcompat.AndroidAppHelper;
import android.content.Context;
import android.util.AtomicFile;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import ps.reso.instaeclipse.utils.feature.FeatureFlags;
import ps.reso.instaeclipse.utils.log.ModuleLog;

/**
 * Local cache of viewed stories, so they stay viewable after they expire or the poster deletes
 * them. Modeled on UnsentLog: a JSON index in Instagram's filesDir + the media files themselves
 * under filesDir/ie_stories/. Populated as the user views stories (StoryDownloadHook capture), read
 * back by the in-IG viewer (DialogUtils). Pruned by the selected retention and a total-size cap.
 *
 * Index entry: { id, author, url, video(bool), path, at(captured epoch ms) }.
 */
public class StoryCache {

    private static final String INDEX = "ie_story_cache.json";
    private static final String DIR = "ie_stories";
    private static final long MAX_BYTES = 500L * 1024 * 1024;    // 500 MB cap

    public static class Entry {
        public final String id, author, url, path;
        public final boolean video;
        public final long at;          // captured-at epoch ms
        public final long expiringAt;  // IG's expiring_at epoch ms (0 = unknown)
        Entry(String id, String author, String url, boolean video, String path, long at, long expiringAt) {
            this.id = id; this.author = author; this.url = url; this.video = video; this.path = path;
            this.at = at; this.expiringAt = expiringAt;
        }
        /** True once past IG's expiry (the story would no longer be in the live tray). */
        public boolean isExpired() { return expiringAt > 0 && System.currentTimeMillis() > expiringAt; }
    }

    private static final List<Entry> cache = new ArrayList<>();
    // Story ids being downloaded, so two binds of the same story cannot capture it twice.
    private static final Set<String> inFlight = new HashSet<>();
    private static boolean loaded = false;
    // The index could not be read or set aside: keep the cache off without re-reading it per call.
    private static boolean loadFailed = false;
    private static AtomicFile storage;

    private static Context ctx() { return AndroidAppHelper.currentApplication(); }
    /** Per-author folder: filesDir/ie_stories/<username>/ (falls back to "unknown"). */
    private static File storyDir(String author) {
        Context c = ctx(); if (c == null) return null;
        String sub = (author == null || author.isEmpty()) ? "unknown" : author.replaceAll("[^A-Za-z0-9._-]", "_");
        File d = new File(new File(c.getFilesDir(), DIR), sub);
        if (!d.exists()) d.mkdirs();
        return d;
    }

    private static synchronized void ensureLoaded() {
        if (loaded || loadFailed) return;
        try {
            if (storage == null) {
                Context context = ctx();
                if (context == null) return;
                storage = new AtomicFile(new File(context.getFilesDir(), INDEX));
            }
            List<Entry> restored = read(storage);
            cache.clear();
            cache.addAll(restored);
            loaded = true;
        } catch (Throwable t) {
            ModuleLog.line("(IE|StoryCache) load failed: " + t);
            setAsideUnreadableIndex();
        }
    }

    /**
     * Keeps the unreadable bytes as INDEX.corrupt and starts an empty index, so one damaged file
     * (e.g. truncated by an older non-atomic write) does not switch the cache off for good.
     */
    private static void setAsideUnreadableIndex() {
        File index = storage == null ? null : storage.getBaseFile();
        File corrupt = index == null ? null : new File(index.getPath() + ".corrupt");
        if (corrupt != null && index.renameTo(corrupt)) {
            cache.clear();
            loaded = true;
            ModuleLog.line("(IE|StoryCache) unreadable index kept as " + corrupt.getName() + "; starting empty");
        } else {
            loadFailed = true;
            ModuleLog.line("(IE|StoryCache) unreadable index could not be set aside; cache off until restart");
        }
    }

    static List<Entry> read(AtomicFile file) throws Exception {
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
            entries.add(new Entry(o.optString("id"), o.optString("author"), o.optString("url"),
                    o.optBoolean("video"), o.optString("path"), o.optLong("at"), o.optLong("expiringAt")));
        }
        return entries;
    }

    private static synchronized void persist() {
        // Do not overwrite a saved index after a failed or premature read.
        if (!loaded) return;
        try {
            write(storage, cache);
        } catch (Throwable t) { ModuleLog.line("(IE|StoryCache) save failed: " + t); }
    }

    static void write(AtomicFile file, List<Entry> entries) throws Exception {
        JSONArray arr = new JSONArray();
        for (Entry e : entries) {
            JSONObject o = new JSONObject();
            o.put("id", e.id); o.put("author", e.author); o.put("url", e.url);
            o.put("video", e.video); o.put("path", e.path); o.put("at", e.at);
            o.put("expiringAt", e.expiringAt);
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

    /** Applies the current duration to existing entries, without a timer or another download. */
    public static synchronized void refresh() {
        ensureLoaded();
        if (loaded && prune()) persist();
    }

    /** Drops expired entries, then oldest-first while over the size cap; deletes their files. */
    private static synchronized boolean prune() {
        long now = System.currentTimeMillis();
        int hours = StoryCacheRetention.normalize(FeatureFlags.storyCacheHours);
        long total = 0;
        boolean changed = false;
        for (java.util.Iterator<Entry> it = cache.iterator(); it.hasNext(); ) {
            Entry e = it.next();
            File mf = new File(e.path);
            if (StoryCacheRetention.expired(e.at, now, hours) || !mf.exists()) {
                //noinspection ResultOfMethodCallIgnored
                mf.delete();
                it.remove();
                changed = true;
            } else {
                total += mf.length();
            }
        }
        // Size cap: cache is append-ordered (oldest first), so evict from the front.
        for (java.util.Iterator<Entry> it = cache.iterator(); it.hasNext() && total > MAX_BYTES; ) {
            Entry e = it.next();
            File mf = new File(e.path);
            total -= mf.length();
            //noinspection ResultOfMethodCallIgnored
            mf.delete();
            it.remove();
            changed = true;
        }
        return changed;
    }

    /**
     * Runs on the UI thread for every story shown, so it only checks memory: no file stat, prune
     * or write. Expired and missing-file entries are dropped by {@link #capture}, {@link #refresh}
     * and {@link #entries}.
     */
    public static synchronized boolean has(String id) {
        if (id == null || id.isEmpty()) return false;
        ensureLoaded();
        long now = System.currentTimeMillis();
        int hours = StoryCacheRetention.normalize(FeatureFlags.storyCacheHours);
        for (Entry e : cache) {
            if (id.equals(e.id) && !StoryCacheRetention.expired(e.at, now, hours)) return true;
        }
        return false;
    }

    private static synchronized boolean contains(String id) {
        for (Entry e : cache) if (id.equals(e.id)) return true;
        return false;
    }

    /**
     * Captures a story: downloads its media to the private cache dir and indexes it. Runs the
     * network I/O on the caller's thread — call from a background executor. No-op if already cached.
     */
    public static void capture(String id, String author, String url, boolean video, long expiringAt) {
        if (id == null || id.isEmpty() || url == null || url.isEmpty()) return;
        synchronized (StoryCache.class) {
            ensureLoaded();
            if (!loaded) return;
            // Prune before the duplicate check: a re-viewed story whose entry expired downloads to
            // that entry's path, so pruning the stale entry later would delete the fresh file.
            if (prune()) persist();
            if (contains(id) || !inFlight.add(id)) return;
        }
        try {
            File dir = storyDir(author); if (dir == null) return;
            String ext = video ? "mp4" : "jpg";
            File out = new File(dir, id.replaceAll("[^A-Za-z0-9._-]", "_") + "." + ext);
            if (!download(url, out)) return;
            synchronized (StoryCache.class) {
                if (contains(id)) return;
                cache.add(new Entry(id, author == null ? "" : author, url, video, out.getAbsolutePath(),
                        System.currentTimeMillis(), expiringAt));
                prune();
                persist();
            }
            ModuleLog.line("(IE|StoryCache) cached story " + id + " by " + author);
        } catch (Throwable t) {
            ModuleLog.line("(IE|StoryCache) capture failed: " + t);
        } finally {
            synchronized (StoryCache.class) { inFlight.remove(id); }
        }
    }

    private static boolean download(String urlStr, File out) {
        HttpURLConnection c = null;
        try {
            c = (HttpURLConnection) new URL(urlStr).openConnection();
            c.setConnectTimeout(15000); c.setReadTimeout(20000);
            c.setRequestProperty("User-Agent", "Instagram");
            if (c.getResponseCode() / 100 != 2) return false;
            try (InputStream in = c.getInputStream(); FileOutputStream fo = new FileOutputStream(out)) {
                byte[] buf = new byte[8192]; int n;
                while ((n = in.read(buf)) > 0) fo.write(buf, 0, n);
            }
            return out.length() > 0;
        } catch (Throwable t) {
            ModuleLog.line("(IE|StoryCache) download failed: " + t);
            //noinspection ResultOfMethodCallIgnored
            out.delete();
            return false;
        } finally { if (c != null) c.disconnect(); }
    }

    /** Snapshot of cached stories, newest first, for the viewer. */
    public static synchronized List<Entry> entries() {
        refresh();
        List<Entry> copy = new ArrayList<>(cache);
        java.util.Collections.reverse(copy);
        return copy;
    }

    public static synchronized void clearAll() {
        ensureLoaded();
        if (!loaded) return;
        for (Entry e : cache) { //noinspection ResultOfMethodCallIgnored
            new File(e.path).delete(); }
        cache.clear();
        persist();
    }
}
