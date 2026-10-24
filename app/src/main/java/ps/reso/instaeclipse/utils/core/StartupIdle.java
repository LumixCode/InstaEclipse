package ps.reso.instaeclipse.utils.core;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;

public final class StartupIdle {

    public interface Poster {
        void postDelayed(Runnable task, long delayMs);
    }

    static final long FALLBACK_MS = 15_000L;
    static final String STATE_FILE = "ie_startup_state";
    static final String STARTING = "starting";
    static final String OK = "ok";

    private static StartupIdle instance;

    private final Poster poster;
    private final List<Runnable> tasks = new ArrayList<>();
    private final List<Long> delays = new ArrayList<>();
    private boolean armed;
    private boolean released;
    private Boolean endedEarly;

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

    public static boolean previousStartEndedEarly(Context context) {
        File dir;
        try {
            dir = context.getFilesDir();
        } catch (Throwable t) {
            return true;
        }
        return dir == null || get().beginStart(dir);
    }

    boolean beginStart(File dir) {
        File state = new File(dir, STATE_FILE);
        boolean early;
        synchronized (this) {
            if (endedEarly != null) return endedEarly;
            try {
                early = state.isFile() && STARTING.equals(read(state));
                write(state, STARTING);
            } catch (IOException | RuntimeException e) {
                early = true;
            }
            endedEarly = early;
        }
        add(() -> {
            try {
                write(state, OK);
            } catch (IOException | RuntimeException ignored) {
            }
        }, 0);
        return early;
    }

    private static String read(File file) throws IOException {
        return new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8).trim();
    }

    private static void write(File file, String value) throws IOException {
        Files.write(file.toPath(), value.getBytes(StandardCharsets.UTF_8));
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
