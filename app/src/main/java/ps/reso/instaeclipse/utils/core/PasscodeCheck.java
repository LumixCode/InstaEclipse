package ps.reso.instaeclipse.utils.core;

import android.os.Handler;
import android.os.Looper;
import android.view.View;

import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BiFunction;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.Predicate;
import java.util.function.Supplier;

import ps.reso.instaeclipse.mods.ui.LockDirectMessagesHook;
import ps.reso.instaeclipse.utils.log.ModuleLog;

public final class PasscodeCheck {

    private static final Executor WORKER = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "IE-Passcode");
        t.setDaemon(true);
        return t;
    });
    private static final AtomicInteger PENDING = new AtomicInteger();

    static volatile Executor worker = WORKER;
    static volatile Executor main = task -> MainThread.HANDLER.post(task);
    static volatile Predicate<String> checker = LockDirectMessagesHook::checkPass;
    static volatile BiFunction<String, String, String> hasher = PasscodeHasher::hash;

    private PasscodeCheck() {}

    public static void check(String code, Consumer<Boolean> onMain) {
        run(() -> checker.test(code), Boolean.FALSE, onMain);
    }

    public static void hash(String code, String salt, Consumer<String> onMain) {
        run(() -> hasher.apply(code, salt), null, onMain);
    }

    public static int pending() {
        return PENDING.get();
    }

    public static void overrideWorkerForTest(Executor testWorker) {
        worker = testWorker != null ? testWorker : WORKER;
    }

    public static void dim(View button, boolean checking) {
        if (button == null) return;
        button.setEnabled(!checking);
        button.setAlpha(checking ? 0.5f : 1f);
    }

    private static <T> void run(Supplier<T> work, T failed, Consumer<T> onMain) {
        PENDING.incrementAndGet();
        worker.execute(() -> {
            T result = failed;
            try {
                result = work.get();
            } catch (Throwable t) {
                ModuleLog.warn("(IE|Lock) Could not check the passcode", t);
            }
            T delivered = result;
            main.execute(() -> {
                try {
                    onMain.accept(delivered);
                } finally {
                    PENDING.decrementAndGet();
                }
            });
        });
    }

    public static final class Prompt {
        private final BooleanSupplier open;
        private final Consumer<Boolean> busy;
        private boolean running;

        public Prompt(BooleanSupplier open, Consumer<Boolean> busy) {
            this.open = open;
            this.busy = busy;
        }

        public boolean running() {
            return running;
        }

        public boolean check(String code, Consumer<Boolean> onResult) {
            if (!begin()) return false;
            PasscodeCheck.check(code, ok -> end(ok, onResult));
            return true;
        }

        public boolean hash(String code, String salt, Consumer<String> onResult) {
            if (!begin()) return false;
            PasscodeCheck.hash(code, salt, hashed -> end(hashed, onResult));
            return true;
        }

        private boolean begin() {
            if (running) return false;
            running = true;
            busy.accept(true);
            return true;
        }

        private <T> void end(T result, Consumer<T> onResult) {
            running = false;
            if (!open.getAsBoolean()) return;
            busy.accept(false);
            onResult.accept(result);
        }
    }

    private static final class MainThread {
        static final Handler HANDLER = new Handler(Looper.getMainLooper());
    }
}
