package ps.reso.instaeclipse.utils.ghost;

import android.content.Context;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import ps.reso.instaeclipse.mods.media.DataBackup;
import ps.reso.instaeclipse.utils.core.AtomicFiles;
import ps.reso.instaeclipse.utils.log.ModuleLog;
import ps.reso.instaeclipse.xposedcompat.AndroidAppHelper;

public final class ReelSeenTimes {

    private static final String TAG = "(IE|ReelSeen) ";
    private static final String FILE = "ie_reel_seen.txt";
    private static final int KEPT = 5000;
    private static final long SAVE_DELAY_MS = 3000;
    private static final Object LOCK = new Object();
    private static final LinkedHashMap<String, Long> TIMES = new LinkedHashMap<>();
    private static final ScheduledExecutorService WORKER = Executors.newSingleThreadScheduledExecutor();
    private static volatile boolean loaded;
    private static boolean loading, saveQueued, dirty;

    private ReelSeenTimes() {}

    public static void load() {
        synchronized (LOCK) {
            if (loaded || loading) return;
            loading = true;
        }
        WORKER.execute(ReelSeenTimes::read);
    }

    public static long get(String account, String reel) {
        if (account == null || reel == null) return 0;
        if (!loaded) {
            load();
            return 0;
        }
        synchronized (LOCK) {
            Long time = TIMES.get(key(account, reel));
            return time == null ? 0 : time;
        }
    }

    public static boolean put(String account, String reel, long time) {
        if (account == null || reel == null || time <= 0) return false;
        if (!loaded) load();
        String key = key(account, reel);
        synchronized (LOCK) {
            Long known = TIMES.get(key);
            if (known != null && known >= time) return false;
            TIMES.remove(key);
            TIMES.put(key, time);
            trim(TIMES);
            changed();
            return true;
        }
    }

    public static boolean remove(String account, String reel) {
        if (account == null || reel == null) return false;
        synchronized (LOCK) {
            if (TIMES.remove(key(account, reel)) == null) return false;
            changed();
            return true;
        }
    }

    private static String key(String account, String reel) {
        return account + "\t" + reel;
    }

    private static void changed() {
        dirty = true;
        if (!loaded || saveQueued) return;
        saveQueued = true;
        WORKER.schedule(ReelSeenTimes::save, SAVE_DELAY_MS, TimeUnit.MILLISECONDS);
    }

    private static void trim(Map<String, Long> times) {
        Iterator<String> it = times.keySet().iterator();
        while (times.size() > KEPT && it.hasNext()) {
            it.next();
            it.remove();
        }
    }

    private static File file() {
        Context ctx = AndroidAppHelper.currentApplication();
        return ctx == null ? null : new File(ctx.getFilesDir(), FILE);
    }

    private static void read() {
        LinkedHashMap<String, Long> disk = new LinkedHashMap<>();
        try {
            File f = file();
            if (f != null && f.exists()) {
                for (String line : new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8).split("\n")) {
                    int last = line.lastIndexOf('\t');
                    if (last <= 0) continue;
                    try {
                        long time = Long.parseLong(line.substring(last + 1).trim());
                        if (time > 0) disk.put(line.substring(0, last), time);
                    } catch (NumberFormatException ignored) { }
                }
            }
        } catch (Throwable t) {
            ModuleLog.warn(TAG + "Could not read the stories and highlights you watched: " + t);
        }
        synchronized (LOCK) {
            for (Map.Entry<String, Long> session : TIMES.entrySet()) {
                Long known = disk.remove(session.getKey());
                disk.put(session.getKey(), known == null ? session.getValue() : Math.max(known, session.getValue()));
            }
            boolean pending = dirty;
            TIMES.clear();
            TIMES.putAll(disk);
            trim(TIMES);
            loaded = true;
            loading = false;
            if (pending) changed();
        }
        ModuleLog.detail(TAG + "kept watched times for " + disk.size() + " stories and highlights");
    }

    private static void save() {
        StringBuilder out = new StringBuilder();
        synchronized (LOCK) {
            saveQueued = false;
            if (!dirty) return;
            dirty = false;
            for (Map.Entry<String, Long> e : TIMES.entrySet()) {
                out.append(e.getKey()).append('\t').append(e.getValue()).append('\n');
            }
        }
        if (DataBackup.restoring()) return;
        try {
            File f = file();
            if (f != null) AtomicFiles.write(f, out.toString());
        } catch (Throwable t) {
            ModuleLog.warn(TAG + "Could not save the stories and highlights you watched: " + t);
        }
    }
}
