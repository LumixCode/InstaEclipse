package ps.reso.instaeclipse.utils.ghost;

import android.content.Context;
import android.util.AtomicFile;
import android.util.Base64;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.FutureTask;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.Predicate;

import ps.reso.instaeclipse.utils.feature.FeatureFlags;
import ps.reso.instaeclipse.utils.log.ModuleLog;

/**
 * Copies of retained messages in Instagram's own serialized form, one file each in Instagram's
 * filesDir. Instagram keeps only each thread's newest messages on disk and reloads older history
 * from the server, which no longer returns unsent ones; these copies let them be restored.
 * Clearing the unsent log deletes the matching copies.
 */
public final class UnsentMessageStore {
    public static final class Saved {
        public final String account, thread;
        public final List<String> ids;
        public final long time;
        public final byte[] message;
        final File file;

        Saved(String account, String thread, List<String> ids, long time, byte[] message, File file) {
            this.account = account; this.thread = thread; this.ids = ids;
            this.time = time; this.message = message; this.file = file;
        }
    }

    private static final class Copy {
        final String account, thread;
        final List<String> ids;
        final long time;
        final File file;

        Copy(String account, String thread, List<String> ids, long time, File file) {
            this.account = account; this.thread = thread; this.ids = ids;
            this.time = time; this.file = file;
        }

        boolean matches(String account, String thread, List<String> ids) {
            return account.equals(this.account) && thread.equals(this.thread) && !Collections.disjoint(this.ids, ids);
        }
    }

    private static final String DIR = "ie_unsent_messages";
    private static final int MAX_SAVED = 500;
    private static final int RECENT_THREADS = 4;
    private static final long PREFETCH_DELAY_SECONDS = 5;

    private static File dir;
    private static List<Copy> saved;
    private static File loading;
    private static ScheduledExecutorService executor;
    private static final Map<String, List<Saved>> recent = new LinkedHashMap<>(8, 0.75f, true) {
        @Override protected boolean removeEldestEntry(Map.Entry<String, List<Saved>> eldest) {
            return size() > RECENT_THREADS;
        }
    };
    private static long generation;
    private static final List<Runnable> loadedListeners = new ArrayList<>();

    private UnsentMessageStore() {}

    public static synchronized void init(Context context) {
        if (dir != null) return;
        dir = new File(context.getFilesDir(), DIR);
        if (!FeatureFlags.keepUnsentMessages) return;
        File target = dir;
        executor().schedule(() -> prefetch(target), PREFETCH_DELAY_SECONDS, TimeUnit.SECONDS);
    }

    private static synchronized ScheduledExecutorService executor() {
        if (executor == null) {
            executor = Executors.newSingleThreadScheduledExecutor(task -> {
                Thread thread = new Thread(task, "IE-Unsent");
                thread.setDaemon(true);
                return thread;
            });
        }
        return executor;
    }

    public static synchronized boolean isEmpty() {
        if (saved == null) {
            requestLoad();
            return true;
        }
        return saved.isEmpty();
    }

    public static List<Saved> forThread(String account, String thread) {
        if (account == null || thread == null) return new ArrayList<>();
        String scope = account + '\n' + thread;
        List<Copy> matches = new ArrayList<>();
        long seen;
        synchronized (UnsentMessageStore.class) {
            if (saved == null) {
                requestLoad();
                return new ArrayList<>();
            }
            List<Saved> known = recent.get(scope);
            if (known != null) return new ArrayList<>(known);
            for (Copy copy : saved) if (account.equals(copy.account) && thread.equals(copy.thread)) matches.add(copy);
            seen = generation;
        }
        List<Saved> out = new ArrayList<>();
        for (Copy copy : matches) {
            Saved read = readCopy(copy.file);
            if (read != null && account.equals(read.account) && thread.equals(read.thread)) out.add(read);
        }
        synchronized (UnsentMessageStore.class) {
            if (generation == seen) recent.put(scope, out);
        }
        return new ArrayList<>(out);
    }

    /** The copy of one message, matched by any of its ids within the account's thread, or null.
     *  Before the copies are loaded only that message's own file is read (it is named after the
     *  first id it was saved under), so the Unsent viewer never reads every copy to label a row. */
    public static Saved find(String account, String thread, List<String> ids) {
        if (account == null || thread == null || ids == null || ids.isEmpty()) return null;
        List<File> candidates = new ArrayList<>();
        synchronized (UnsentMessageStore.class) {
            if (dir == null) return null;
            if (saved == null) {
                for (String id : ids) {
                    try {
                        candidates.add(new File(dir, name(account, thread, id)));
                    } catch (Exception e) {
                        ModuleLog.detail("(IE|UnsentStore) unreadable copy skipped: " + e.getClass().getSimpleName());
                    }
                }
            } else {
                for (Copy copy : saved) {
                    if (copy.matches(account, thread, ids)) {
                        candidates.add(copy.file);
                        break;
                    }
                }
            }
        }
        for (File file : candidates) {
            Saved copy = file.isFile() ? readCopy(file) : null;
            if (copy != null && account.equals(copy.account) && thread.equals(copy.thread)
                    && !Collections.disjoint(copy.ids, ids)) return copy;
        }
        return null;
    }

    public static void save(String account, String thread, List<String> ids, long time, byte[] message) {
        if (account == null || thread == null || ids.isEmpty() || message == null) return;
        List<String> copyIds = new ArrayList<>(ids);
        synchronized (UnsentMessageStore.class) {
            if (dir == null) return;
            requestLoad();
            File target = dir;
            executor().execute(() -> write(target, account, thread, copyIds, time, message));
        }
    }

    public static void remove(Saved copy) {
        removeWhere(known -> known.file.equals(copy.file), copy.file);
    }

    /** Delete the copies of one message, matched by any of its ids within the account's thread. */
    public static void removeMessage(String account, String thread, List<String> ids) {
        if (account == null || thread == null || ids == null || ids.isEmpty()) return;
        removeWhere(copy -> copy.matches(account, thread, ids), null);
    }

    /** Delete copies for one thread, or for the whole account when {@code thread} is null. */
    public static void clear(String account, String thread) {
        if (account == null) return;
        removeWhere(copy -> account.equals(copy.account) && (thread == null || thread.equals(copy.thread)), null);
    }

    public static void whenLoaded(Runnable listener) {
        synchronized (UnsentMessageStore.class) {
            if (saved == null) {
                loadedListeners.add(listener);
                requestLoad();
                return;
            }
        }
        listener.run();
    }

    public static boolean awaitLoaded(long timeoutMs) {
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMs);
        synchronized (UnsentMessageStore.class) {
            requestLoad();
            while (saved == null && loading != null) {
                long left = deadline - System.nanoTime();
                if (left <= 0) break;
                try {
                    UnsentMessageStore.class.wait(Math.max(1, TimeUnit.NANOSECONDS.toMillis(left)));
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
            return saved != null;
        }
    }

    public static boolean flush(long timeoutMs) {
        FutureTask<Void> marker = new FutureTask<>(() -> null);
        executor().execute(marker);
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

    private static void requestLoad() {
        if (saved != null || dir == null || loading == dir) return;
        File target = dir;
        loading = target;
        executor().execute(() -> load(target));
    }

    private static void prefetch(File target) {
        synchronized (UnsentMessageStore.class) {
            if (dir != target || saved != null || loading == target) return;
            loading = target;
        }
        load(target);
    }

    private static void load(File target) {
        List<Copy> found = new ArrayList<>();
        File[] files = target.listFiles();
        if (files != null) {
            for (File file : files) {
                if (file.getName().endsWith(".bak") || file.getName().endsWith(".new")) continue;
                try {
                    JSONObject o = json(file);
                    o.getString("m");
                    found.add(new Copy(o.getString("a"), o.getString("th"), ids(o), o.getLong("ts"), file));
                } catch (Exception e) {
                    ModuleLog.detail("(IE|UnsentStore) unreadable copy skipped: " + e.getClass().getSimpleName());
                }
            }
        }
        List<Runnable> listeners;
        synchronized (UnsentMessageStore.class) {
            if (dir != target) return;
            loading = null;
            if (saved == null) {
                saved = found;
                changed();
            }
            UnsentMessageStore.class.notifyAll();
            listeners = new ArrayList<>(loadedListeners);
            loadedListeners.clear();
        }
        for (Runnable listener : listeners) {
            try {
                listener.run();
            } catch (Throwable t) {
                ModuleLog.warn("(IE|UnsentStore) A task waiting for the saved copies failed", t);
            }
        }
    }

    private static void write(File target, String account, String thread, List<String> ids, long time, byte[] message) {
        synchronized (UnsentMessageStore.class) {
            if (dir != target || saved == null) return;
            for (Copy copy : saved) if (copy.matches(account, thread, ids)) return;
        }
        try {
            if (!target.isDirectory() && !target.mkdirs()) return;
            JSONObject o = new JSONObject();
            o.put("a", account); o.put("th", thread); o.put("ids", new JSONArray(ids));
            o.put("ts", time); o.put("m", Base64.encodeToString(message, Base64.NO_WRAP));
            File file = new File(target, name(account, thread, ids.get(0)));
            AtomicFile atomic = new AtomicFile(file);
            FileOutputStream out = atomic.startWrite();
            try {
                out.write(o.toString().getBytes(StandardCharsets.UTF_8));
                atomic.finishWrite(out);
            } catch (Exception e) {
                atomic.failWrite(out);
                throw e;
            }
            List<File> evicted = new ArrayList<>();
            synchronized (UnsentMessageStore.class) {
                if (dir != target || saved == null) return;
                saved.add(new Copy(account, thread, ids, time, file));
                changed();
                while (saved.size() > MAX_SAVED) {
                    Copy oldest = Collections.min(saved, Comparator.comparingLong(copy -> copy.time));
                    saved.remove(oldest);
                    evicted.add(oldest.file);
                }
            }
            for (File gone : evicted) new AtomicFile(gone).delete();
        } catch (Exception e) {
            ModuleLog.warn("(IE|UnsentStore) Could not keep a copy of an unsent message: " + e.getClass().getSimpleName() + ": " + e.getMessage());
        }
    }

    private static void removeWhere(Predicate<Copy> match, File file) {
        synchronized (UnsentMessageStore.class) {
            if (dir == null) return;
            File target = dir;
            List<File> gone = new ArrayList<>();
            if (file != null) gone.add(file);
            if (saved != null) take(match, gone);
            else requestLoad();
            changed();
            executor().execute(() -> removeLoaded(target, match, gone));
        }
    }

    private static void removeLoaded(File target, Predicate<Copy> match, List<File> gone) {
        synchronized (UnsentMessageStore.class) {
            if (dir == target && saved != null && take(match, gone)) changed();
        }
        for (File file : gone) new AtomicFile(file).delete();
    }

    private static boolean take(Predicate<Copy> match, List<File> gone) {
        boolean taken = false;
        for (Iterator<Copy> it = saved.iterator(); it.hasNext(); ) {
            Copy copy = it.next();
            if (!match.test(copy)) continue;
            it.remove();
            if (!gone.contains(copy.file)) gone.add(copy.file);
            taken = true;
        }
        return taken;
    }

    private static void changed() {
        generation++;
        recent.clear();
    }

    private static Saved readCopy(File file) {
        try {
            JSONObject o = json(file);
            return new Saved(o.getString("a"), o.getString("th"), ids(o), o.getLong("ts"),
                    Base64.decode(o.getString("m"), Base64.NO_WRAP), file);
        } catch (Exception e) {
            ModuleLog.detail("(IE|UnsentStore) unreadable copy skipped: " + e.getClass().getSimpleName());
            return null;
        }
    }

    private static JSONObject json(File file) throws Exception {
        return new JSONObject(new String(new AtomicFile(file).readFully(), StandardCharsets.UTF_8));
    }

    private static List<String> ids(JSONObject o) throws Exception {
        JSONArray idArray = o.getJSONArray("ids");
        List<String> ids = new ArrayList<>();
        for (int i = 0; i < idArray.length(); i++) ids.add(idArray.getString(i));
        return ids;
    }

    private static String name(String account, String thread, String message) throws Exception {
        byte[] hash = MessageDigest.getInstance("SHA-256")
                .digest((account + '\n' + thread + '\n' + message).getBytes(StandardCharsets.UTF_8));
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < 16; i++) b.append(String.format("%02x", hash[i]));
        return b.append(".json").toString();
    }
}
