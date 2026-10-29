package ps.reso.instaeclipse.utils.log;

import android.util.Log;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;

import ps.reso.instaeclipse.utils.log.LogLine.Level;

public final class ModuleLog {

    private static final String TAG = "InstaEclipse";

    public static volatile boolean verbose = ps.reso.instaeclipse.BuildConfig.DEBUG;

    static volatile Sink sink = Boolean.getBoolean("instaeclipse.jvmTest") ? new MemorySink() : new AndroidSink();

    private ModuleLog() {}

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
        boolean caller = verbose || level == Level.WARN || level == Level.ERROR;
        sink.write(level, caller ? getCallerInfo() + msg : msg, t);
    }

    interface Sink {
        void write(Level level, String formatted, Throwable t);
    }

    static final class AndroidSink implements Sink {
        @Override
        public void write(Level level, String formatted, Throwable t) {
            String stackTraceStr = t != null ? Log.getStackTraceString(t) : "";
            String line = formatted + (stackTraceStr.isEmpty() ? "" : "\n" + stackTraceStr);
            Logging.append(LogLine.marker(level) + line);
            switch (level) {
                case ERROR: Log.e(TAG, line); break;
                case WARN: Log.w(TAG, line); break;
                case DETAIL: Log.d(TAG, line); break;
                default: Log.i(TAG, line);
            }
        }
    }

    static final class MemorySink implements Sink {
        static final int CAPACITY = 1000;

        static final class Record {
            final Level level;
            final String formatted;
            final Throwable throwable;

            Record(Level level, String formatted, Throwable throwable) {
                this.level = level;
                this.formatted = formatted;
                this.throwable = throwable;
            }
        }

        private final ArrayDeque<Record> records = new ArrayDeque<>();

        @Override
        public synchronized void write(Level level, String formatted, Throwable t) {
            if (records.size() == CAPACITY) records.removeFirst();
            records.addLast(new Record(level, formatted, t));
        }

        synchronized List<Record> records() {
            return new ArrayList<>(records);
        }
    }
}
