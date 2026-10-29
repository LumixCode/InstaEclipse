package ps.reso.instaeclipse.mods.core;

import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.concurrent.ThreadPoolExecutor;

import ps.reso.instaeclipse.xposedcompat.XC_MethodHook;
import ps.reso.instaeclipse.xposedcompat.XposedHelpers;
import ps.reso.instaeclipse.utils.log.ModuleLog;

public class TerminatedExecutorGuard {

    enum Decision { DROP, THROW }

    private static volatile Set<String> dispatcherClasses = Collections.emptySet();

    static void setDispatcherClasses(Set<String> classes) {
        dispatcherClasses = classes == null ? Collections.emptySet()
                : Collections.unmodifiableSet(new LinkedHashSet<>(classes));
    }

    static Decision decide(boolean shutdown, StackTraceElement[] frames, Set<String> dispatchers) {
        if (!shutdown) return Decision.THROW;
        if (frames != null && dispatchers != null && !dispatchers.isEmpty()) {
            for (StackTraceElement frame : frames) {
                if (dispatchers.contains(frame.getClassName())) return Decision.THROW;
            }
        }
        return Decision.DROP;
    }

    public void install(ClassLoader classLoader) {
        try {
            XposedHelpers.findAndHookMethod(
                    "java.util.concurrent.ThreadPoolExecutor$AbortPolicy", classLoader,
                    "rejectedExecution",
                    Runnable.class, ThreadPoolExecutor.class,
                    new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) {
                            try {
                                ThreadPoolExecutor ex = (ThreadPoolExecutor) param.args[1];
                                if (ex == null || !ex.isShutdown()) return;
                                Set<String> dispatchers = dispatcherClasses;
                                StackTraceElement[] frames = dispatchers.isEmpty() ? null : Thread.currentThread().getStackTrace();
                                if (decide(true, frames, dispatchers) == Decision.DROP) {
                                    param.setResult(null);
                                    ModuleLog.probe("(IE|ExecGuard) dropped task rejected by shut-down executor");
                                }
                            } catch (Throwable ignored) {
                            }
                        }
                    }
            );
            ModuleLog.ok("(IE|ExecGuard) ready");
        } catch (Throwable t) {
            ModuleLog.warn("(IE|ExecGuard) Could not set up the crash guard; Instagram may crash in rare cases while closing videos or chats: "
                    + t.getClass().getSimpleName() + ": " + t.getMessage());
        }
    }
}
