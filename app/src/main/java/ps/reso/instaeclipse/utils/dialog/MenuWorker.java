package ps.reso.instaeclipse.utils.dialog;

import android.os.Handler;
import android.os.Looper;

import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;

import ps.reso.instaeclipse.utils.log.ModuleLog;

public final class MenuWorker {

    private static ExecutorService executor;
    private static Handler main;

    private MenuWorker() {}

    private static synchronized ExecutorService executor() {
        if (executor == null) {
            executor = Executors.newSingleThreadExecutor(task -> {
                Thread thread = new Thread(task, "IE-Menu");
                thread.setDaemon(true);
                return thread;
            });
        }
        return executor;
    }

    private static synchronized Handler main() {
        if (main == null) main = new Handler(Looper.getMainLooper());
        return main;
    }

    public static void execute(Runnable task) {
        executor().execute(task);
    }

    public static void post(Runnable task) {
        main().post(task);
    }

    public static <T> void load(Callable<T> work, Consumer<T> onMain) {
        execute(() -> {
            T result = null;
            try {
                result = work.call();
            } catch (Throwable t) {
                ModuleLog.warn("(IE|Menu) Background work for the menu failed", t);
            }
            T done = result;
            post(() -> onMain.accept(done));
        });
    }
}
