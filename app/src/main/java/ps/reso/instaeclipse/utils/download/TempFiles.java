package ps.reso.instaeclipse.utils.download;

import java.io.File;
import java.util.concurrent.atomic.AtomicBoolean;

public final class TempFiles {
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
        return 0;
    }
}
