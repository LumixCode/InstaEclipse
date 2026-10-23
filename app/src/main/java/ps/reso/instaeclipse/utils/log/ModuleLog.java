package ps.reso.instaeclipse.utils.log;

import android.util.Log;

import ps.reso.instaeclipse.utils.log.LogLine.Level;

/**
 * Drop-in replacement for direct XposedBridge.log(...) calls: prefixes the caller's
 * class/method/line, still logs to logcat, and additionally appends to Logging's in-memory
 * ring buffer so the message shows up in InstaEclipse's in-app log viewer, not just adb logcat.
 *
 * Logs to logcat via android.util.Log rather than XposedBridge.log: Vector refuses to let a
 * module hook XposedBridge's own methods ("Do not allow hooking inner methods"), which is
 * what made the original auto-capture-via-hook approach a dead end, and Class.forName("de.robv
 * .android.xposed.XposedBridge") also fails under Vector's legacy-API compat even though a
 * direct compiled reference to XposedBridge works fine. android.util.Log has neither problem
 * and works identically in the companion app's own (un-hooked) process.
 *
 * Every line carries a level (see {@link LogLine}); the Logs tab colours and filters by it:
 *  - {@link #ok}: a feature is ready ("✅").
 *  - {@link #line}: plain information. A leading status emoji the message already has sets the
 *    level (❌ error, ⚠️ warning, ✅ ok), so existing call sites keep their meaning.
 *  - {@link #warn}: something optional is unavailable or a fallback was used; the feature
 *    still works, maybe partly.
 *  - {@link #error}: a feature the user turned on does not work, or an action failed.
 *  - {@link #detail}: technical diagnostics (resolved names, timings, counts). Kept in release
 *    logs for bug reports but hidden in the Logs tab unless "Technical details" is on.
 *  - {@link #probe}: debug builds only.
 */
public final class ModuleLog {

    private static final String TAG = "InstaEclipse";

    /** Verbose/debug logging. On only in debug builds so per-event PROBE/DEBUG chatter and the
     *  stack-walk they trigger don't ship in release. Normal status lines (line()) always log. */
    public static volatile boolean verbose = ps.reso.instaeclipse.BuildConfig.DEBUG;

    private ModuleLog() {}

    /** Debug-only line: logs nothing unless {@link #verbose} is on. Use for high-frequency or
     *  sensitive PROBE/DEBUG output so it stays out of release logs by default. */
    public static void probe(String msg) {
        if (verbose) log(Level.DETAIL, msg, null);
    }

    private static String getCallerInfo() {
        StackTraceElement[] stack = Thread.currentThread().getStackTrace();
        for (int i = 2; i < stack.length; i++) {
            String cn = stack[i].getClassName();
            if (!cn.equals(ModuleLog.class.getName()) && !cn.equals(Logging.class.getName())
                    && !cn.equals(Thread.class.getName()) && !cn.startsWith("de.robv.android.xposed.")
                    && !cn.startsWith("ps.reso.instaeclipse.xposedcompat.") && !cn.startsWith("io.github.libxposed.")
                    && !cn.startsWith("LSPHooker")) {
                String simpleName = cn.substring(cn.lastIndexOf('.') + 1);
                return "[" + simpleName + "." + stack[i].getMethodName() + ":" + stack[i].getLineNumber() + "] ";
            }
        }
        return "";
    }

    public static void line(String msg) {
        log(LogLine.emojiLevel(msg), msg, null);
    }

    public static void line(String msg, Throwable t) {
        log(LogLine.emojiLevel(msg), msg, t);
    }

    public static void ok(String msg) {
        log(Level.OK, msg, null);
    }

    public static void warn(String msg) {
        log(Level.WARN, msg, null);
    }

    public static void warn(String msg, Throwable t) {
        log(Level.WARN, msg, t);
    }

    public static void error(String msg) {
        log(Level.ERROR, msg, null);
    }

    public static void error(String msg, Throwable t) {
        log(Level.ERROR, msg, t);
    }

    public static void detail(String msg) {
        log(Level.DETAIL, msg, null);
    }

    public static void detail(String msg, Throwable t) {
        log(Level.DETAIL, msg, t);
    }

    private static void log(Level level, String msg, Throwable t) {
        String stackTraceStr = t != null ? Log.getStackTraceString(t) : "";
        String fullMsg = msg + (stackTraceStr.isEmpty() ? "" : "\n" + stackTraceStr);
        String formatted = getCallerInfo() + fullMsg;
        Logging.append(LogLine.marker(level) + formatted);
        switch (level) {
            case ERROR: Log.e(TAG, formatted); break;
            case WARN: Log.w(TAG, formatted); break;
            case DETAIL: Log.d(TAG, formatted); break;
            default: Log.i(TAG, formatted);
        }
    }
}
