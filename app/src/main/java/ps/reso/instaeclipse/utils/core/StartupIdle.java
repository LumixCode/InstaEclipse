package ps.reso.instaeclipse.utils.core;

import android.os.Handler;
import android.os.Looper;

import java.util.ArrayList;
import java.util.List;

public final class StartupIdle {

    public interface Poster {
        void postDelayed(Runnable task, long delayMs);
    }

    static final long FALLBACK_MS = 15_000L;

    private static StartupIdle instance;

    private final Poster poster;
    private final List<Runnable> tasks = new ArrayList<>();
    private final List<Long> delays = new ArrayList<>();
    private boolean armed;
    private boolean released;

    StartupIdle(Poster poster) {
        this.poster = poster;
    }

    private static synchronized StartupIdle get() {
        if (instance == null) instance = new StartupIdle(new MainPoster());
        return instance;
    }

    public static void afterFirstFrame(Runnable task, long delayMs) {
        get().add(task, delayMs);
    }

    public static void armFallback() {
        get().arm();
    }

    public static void firstFrame() {
        get().release();
    }

    void add(Runnable task, long delayMs) {
        synchronized (this) {
            if (!released) {
                tasks.add(task);
                delays.add(delayMs);
                return;
            }
        }
        poster.postDelayed(task, Math.max(0L, delayMs));
    }

    void arm() {
        synchronized (this) {
            if (armed || released) return;
            armed = true;
        }
        poster.postDelayed(this::release, FALLBACK_MS);
    }

    void release() {
        List<Runnable> run;
        List<Long> after;
        synchronized (this) {
            if (released) return;
            released = true;
            run = new ArrayList<>(tasks);
            after = new ArrayList<>(delays);
            tasks.clear();
            delays.clear();
        }
        for (int i = 0; i < run.size(); i++) poster.postDelayed(run.get(i), Math.max(0L, after.get(i)));
    }

    synchronized boolean isReleased() {
        return released;
    }

    private static final class MainPoster implements Poster {
        private Handler handler;

        @Override
        public synchronized void postDelayed(Runnable task, long delayMs) {
            if (handler == null) handler = new Handler(Looper.getMainLooper());
            handler.postDelayed(task, delayMs);
        }
    }
}
