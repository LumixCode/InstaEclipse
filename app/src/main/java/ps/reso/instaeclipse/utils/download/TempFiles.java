package ps.reso.instaeclipse.utils.download;

import java.io.File;
import java.util.concurrent.atomic.AtomicBoolean;

public final class TempFiles {
    static final long MAX_AGE_MS = 60L * 60L * 1000L;
    private static final TempFiles PROCESS = new TempFiles();
    private final AtomicBoolean swept = new AtomicBoolean();

    public static void sweepOnce(File cacheDir) {
        PROCESS.sweepFirstTime(cacheDir, System.currentTimeMillis());
    }

    int sweepFirstTime(File cacheDir, long now) {
        if (cacheDir == null || !swept.compareAndSet(false, true)) return 0;
        return sweep(cacheDir, now);
    }

    static int sweep(File cacheDir, long now) {
        File[] files = cacheDir.listFiles((dir, name) -> name.startsWith("ie_"));
        if (files == null) return 0;
        int deleted = 0;
        for (File file : files) {
            if (file.isFile() && now - file.lastModified() > MAX_AGE_MS && file.delete()) deleted++;
        }
        return deleted;
    }
}
