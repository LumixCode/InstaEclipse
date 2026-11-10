package ps.reso.instaeclipse.utils.media;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.util.AtomicFile;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import ps.reso.instaeclipse.mods.media.DownloadFiles;
import ps.reso.instaeclipse.utils.download.DownloadEngine;
import ps.reso.instaeclipse.utils.log.ModuleLog;
import ps.reso.instaeclipse.xposedcompat.AndroidAppHelper;

public final class InstantHistory {

    private static final String TAG = "(IE|InstantHistory) ";
    private static final String INDEX = "ie_instant_history.json";
    private static final String DIR = "ie_instants";

    public static final class Source {
        final String author, url;
        final boolean video;

        public Source(String author, String url, boolean video) {
            this.author = author == null ? "" : author;
            this.url = url;
            this.video = video;
        }
    }

    private static final Object LOCK = new Object();
    private static final List<StoryCache.Entry> kept = new ArrayList<>();
    private static final Set<String> tried = ConcurrentHashMap.newKeySet();
    private static volatile Set<String> ids = Collections.emptySet();
    private static volatile boolean unreadable;
    private static AtomicFile storage;
    private static boolean loaded;
    private static final ExecutorService WORKER = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(() -> {
            android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_BACKGROUND);
            r.run();
        }, "IE-InstantHistory");
        t.setDaemon(true);
        return t;
    });

    private InstantHistory() {}

    public static void keepAsync(String id, Callable<Source> resolve) {
        if (id == null || id.isEmpty() || unreadable || ids.contains(id) || !tried.add(id)) return;
        WORKER.execute(() -> {
            try {
                capture(id, resolve);
            } catch (Throwable t) {
                ModuleLog.warn(TAG + "Could not keep a copy of an Instant: " + t.getClass().getSimpleName() + ": " + t.getMessage());
            }
        });
    }

    private static void capture(String id, Callable<Source> resolve) throws Exception {
        Context c = AndroidAppHelper.currentApplication();
        if (c == null || !load(c) || ids.contains(id)) return;
        Source source = resolve.call();
        if (source == null || source.url == null || source.url.isEmpty()) {
            ModuleLog.detail(TAG + "instant " + id + " has no file that can be kept");
            return;
        }
        File temp = File.createTempFile("ie_instant_", ".bin", c.getCacheDir());
        try {
            String mime = DownloadEngine.fetchMedia(c, source.url, temp, source.video ? "video/mp4" : "image/jpeg",
                    new DownloadEngine.Cancellation());
            String ext = StoryCache.extension(mime);
            if (ext == null) throw new IOException("Unsupported Instant type " + mime);
            File dir = new File(new File(c.getFilesDir(), DIR), DownloadFiles.safeSegment(source.author, "unknown"));
            File out = new File(dir, StoryCache.fileStem(id) + "." + ext);
            synchronized (LOCK) {
                if (!dir.isDirectory() && !dir.mkdirs()) throw new IOException("The folder for kept Instants could not be created");
                if (out.exists() && !out.delete()) throw new IOException("A kept Instant could not be replaced");
                if (!temp.renameTo(out)) throw new IOException("A kept Instant could not be moved into place");
                kept.add(new StoryCache.Entry(id, source.author, mime, source.video, out.getAbsolutePath(),
                        System.currentTimeMillis(), 0));
                publish();
                prune();
                save();
            }
            ModuleLog.probe(TAG + "kept instant " + id + " by " + source.author);
        } finally {
            if (temp.exists() && !temp.delete()) ModuleLog.detail(TAG + "a temporary Instant file could not be removed");
        }
    }

    public static List<StoryCache.Entry> entries() {
        List<StoryCache.Entry> copy = new ArrayList<>();
        Context c = AndroidAppHelper.currentApplication();
        if (c == null || !load(c)) return copy;
        synchronized (LOCK) {
            if (prune()) save();
            removeOrphans(c);
            copy.addAll(kept);
        }
        Collections.reverse(copy);
        return copy;
    }

    public static void clearAllAsync(Runnable done) {
        WORKER.execute(() -> {
            try {
                clearAll();
            } catch (Throwable t) {
                ModuleLog.warn(TAG + "Could not clear the kept Instants: " + t.getClass().getSimpleName() + ": " + t.getMessage());
            } finally {
                if (done != null) new Handler(Looper.getMainLooper()).post(done);
            }
        });
    }

    private static void clearAll() {
        Context c = AndroidAppHelper.currentApplication();
        if (c == null || !load(c)) return;
        synchronized (LOCK) {
            List<StoryCache.Entry> removed = new ArrayList<>(kept);
            kept.clear();
            publish();
            save();
            for (StoryCache.Entry e : removed) delete(e);
            removeOrphans(c);
        }
        tried.clear();
    }

    private static boolean load(Context c) {
        synchronized (LOCK) {
            if (loaded) return true;
            if (unreadable) return false;
            if (storage == null) storage = new AtomicFile(new File(c.getFilesDir(), INDEX));
            boolean setAside = false;
            try {
                kept.addAll(StoryCache.read(storage));
            } catch (Throwable t) {
                ModuleLog.detail(TAG + "load failed: " + t);
                File index = storage.getBaseFile();
                File corrupt = new File(index.getPath() + ".corrupt");
                if (!index.renameTo(corrupt)) {
                    unreadable = true;
                    ModuleLog.error(TAG + "The list of kept Instants could not be read; Instants are not kept until Instagram restarts");
                    return false;
                }
                ModuleLog.warn(TAG + "The list of kept Instants could not be read; it was set aside as " + corrupt.getName() + " and the list starts empty");
                setAside = true;
            }
            loaded = true;
            publish();
            if (setAside) removeOrphans(c);
            return true;
        }
    }

    private static void publish() {
        Set<String> now = new HashSet<>();
        for (StoryCache.Entry e : kept) now.add(e.id);
        ids = now;
    }

    private static boolean prune() {
        List<StoryCache.Entry> drop = StoryCache.surplus(kept, e -> false);
        if (drop.isEmpty()) return false;
        kept.removeAll(drop);
        publish();
        for (StoryCache.Entry e : drop) delete(e);
        return true;
    }

    private static void save() {
        try {
            StoryCache.write(storage, kept);
        } catch (Throwable t) {
            ModuleLog.warn(TAG + "Could not save the list of kept Instants: " + t.getClass().getSimpleName() + ": " + t.getMessage());
        }
    }

    private static void delete(StoryCache.Entry e) {
        File file = new File(e.path);
        if (file.exists() && !file.delete()) ModuleLog.detail(TAG + "a kept Instant file could not be removed");
    }

    private static void removeOrphans(Context c) {
        List<String> keep = new ArrayList<>();
        for (StoryCache.Entry e : kept) keep.add(e.path);
        int removed = StoryCache.removeOrphans(new File(c.getFilesDir(), DIR), keep, Collections.emptyList());
        if (removed > 0) ModuleLog.detail(TAG + "removed " + removed + " files and folders no kept Instant uses");
    }
}
