package ps.reso.instaeclipse.utils.log;

import android.content.Context;
import android.net.Uri;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.os.ParcelFileDescriptor;
import android.os.Process;
import android.system.Os;
import android.util.Log;
import android.util.AtomicFile;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.text.SimpleDateFormat;
import java.util.ArrayDeque;
import java.util.Collections;
import java.util.Date;
import java.util.Iterator;
import java.util.Locale;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

public final class Logging {

    private static final long FLUSH_DELAY_MS = 750;
    private static final int IPC_MAX_CHARS = 150000;
    private static final long MAX_FILE_BYTES = 4 * 1024 * 1024;
    private static final long MAX_LOCAL_BYTES = 2 * MAX_FILE_BYTES;
    private static final int MAX_LINES = 10000;
    private static final int MAX_LINE_CHARS = 4096;
    private static final int MAX_ENTRY_CHARS = MAX_LINE_CHARS + 128;
    private static final int READ_CHUNK_CHARS = 16 * 1024;

    private static final Object LOCK = new Object();
    private static final ArrayDeque<Entry> LINES = new ArrayDeque<>(MAX_LINES + 16);
    private static final ThreadLocal<SimpleDateFormat> TS =
            ThreadLocal.withInitial(() -> new SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US));

    private static boolean initialized;
    private static File logFile;
    private static HandlerThread ioThread;
    private static Handler ioHandler;
    private static boolean dirtyFile;
    private static boolean flushScheduled;
    private static boolean clearedBeforeInit;
    private static long bufferedBytes;
    private static long revision;
    private static String lastBody;
    private static String lastBodyTime;
    private static int lastBodyRepeat = 1;
    private static long nextSeq;
    private static long writtenSeq;
    private static boolean rewriteAll;
    private static boolean replaceLast;

    private static final Uri MIRROR_URI = Uri.parse("content://ps.reso.instaeclipse.logmirror/log");
    private static Context mirrorContext;
    private static ParcelFileDescriptor mirrorPfd;
    private static volatile FileOutputStream mirrorOut;
    private static long mirrorBytes;
    private static long mirrorLastStart = -1;
    private static FileOutputStream localOut;
    private static long localBytes;
    private static long localLastStart = -1;
    private static boolean localUnsynced;

    private static final Runnable FLUSH_RUNNABLE = () -> persistOnIoThread(false);

    private static final class Entry {
        final long seq;
        final String text;
        final long bytes;

        Entry(long seq, String text) {
            this.seq = seq;
            this.text = text;
            bytes = text.getBytes(StandardCharsets.UTF_8).length + 1L;
        }
    }

    private static final class History {
        final ArrayDeque<String> lines = new ArrayDeque<>();
        long bytes;
        boolean appendable;
    }

    private Logging() {}

    public static void init(Context context) {
        init(context, "instaeclipse_logging.log");
    }

    public static void init(Context context, String filename) {
        synchronized (LOCK) {
            if (logFile != null) return;
            logFile = new File(context.getFilesDir(), filename);
            ensureIoThread();
            ioHandler.post(Logging::loadHistory);
        }
    }

    private static void loadHistory() {
        try {
            File file;
            synchronized (LOCK) {
                file = logFile;
            }
            History history = readHistory(file);
            localBytes = history.bytes;
            localLastStart = -1;
            synchronized (LOCK) {
                List<Entry> pending = new ArrayList<>(LINES);
                LINES.clear();
                bufferedBytes = 0;
                if (!clearedBeforeInit) for (String text : history.lines) addEntryLocked(new Entry(0, text));
                for (Entry entry : pending) addEntryLocked(entry);
                if (!history.appendable) rewriteAll = true;
                initialized = true;
                dirtyFile = true;
                revision++;
                scheduleFlushLocked(0);
            }
        } catch (Throwable t) {
            Log.w("InstaEclipse", "[Logging] log history unavailable (" + t.getClass().getSimpleName() + ")");
        }
    }

    public static void enableMirror(Context context) {
        refreshMirror(context, null);
    }

    public static void refreshMirror(Context context, Runnable after) {
        if (context == null) {
            if (after != null) after.run();
            return;
        }
        Context app = context.getApplicationContext() != null ? context.getApplicationContext() : context;
        Handler h;
        synchronized (LOCK) {
            mirrorContext = app;
            ensureIoThread();
            h = ioHandler;
        }
        h.post(() -> {
            try {
                attachMirror(app);
            } catch (Throwable t) {
                Log.w("InstaEclipse", "[Logging] log mirror unavailable (" + t.getClass().getSimpleName() + ")");
            }
            if (after != null) {
                try { after.run(); } catch (Throwable ignored) {}
            }
        });
    }

    private static void attachMirror(Context app) {
        ParcelFileDescriptor fresh = null;
        try {
            ParcelFileDescriptor pfd = app.getContentResolver().openFileDescriptor(MIRROR_URI, "wa");
            if (pfd != null) {
                fresh = pfd.dup();
                try { pfd.close(); } catch (Throwable ignored) {}
            }
        } catch (Throwable t) {
            Log.w("InstaEclipse", "[Logging] log mirror unavailable (" + t.getClass().getSimpleName() + ")");
        }
        closeMirror();
        if (fresh == null) return;
        mirrorPfd = fresh;
        mirrorOut = new FileOutputStream(fresh.getFileDescriptor());
        List<Entry> early = null;
        synchronized (LOCK) {
            rewriteAll = true;
            dirtyFile = true;
            if (!initialized) early = new ArrayList<>(LINES);
        }
        if (early == null) persistOnIoThread(false);
        else rewriteMirror(encode(early), lastBytes(early));
    }

    public static boolean isMirrorActive() {
        return mirrorOut != null;
    }

    private static void closeMirror() {
        if (mirrorPfd != null) {
            try { mirrorPfd.close(); } catch (Throwable ignored) {}
        }
        mirrorPfd = null;
        mirrorOut = null;
        mirrorBytes = 0;
        mirrorLastStart = -1;
    }

    private static void closeLocal() {
        if (localOut != null) {
            try { localOut.close(); } catch (Throwable ignored) {}
        }
        localOut = null;
        localLastStart = -1;
        localUnsynced = false;
    }

    private static void ensureIoThread() {
        if (ioThread != null) return;
        ioThread = new HandlerThread("InstaEclipse-Logging-IO", Process.THREAD_PRIORITY_BACKGROUND);
        ioThread.start();
        ioHandler = new Handler(ioThread.getLooper());
    }

    private static History readHistory(File file) {
        History history = new History();
        try (FileInputStream input = new AtomicFile(file).openRead()) {
            long size = input.getChannel().size();
            long skip = Math.max(0, size - MAX_FILE_BYTES);
            if (skip > 0) input.getChannel().position(skip);
            Reader reader = new InputStreamReader(input, StandardCharsets.UTF_8);
            char[] buf = new char[READ_CHUNK_CHARS];
            StringBuilder line = new StringBuilder();
            boolean discard = skip > 0;
            char last = '\n';
            int n;
            while ((n = reader.read(buf)) > 0) {
                int from = 0;
                for (int i = 0; i < n; i++) {
                    if (buf[i] != '\n') continue;
                    appendBounded(line, buf, from, i);
                    if (discard) discard = false;
                    else addHistoryLine(history.lines, line);
                    line.setLength(0);
                    from = i + 1;
                }
                appendBounded(line, buf, from, n);
                last = buf[n - 1];
            }
            if (line.length() > 0 && !discard) addHistoryLine(history.lines, line);
            history.bytes = size;
            history.appendable = last == '\n';
        } catch (FileNotFoundException missing) {
            history.appendable = !file.exists();
        } catch (Throwable ignored) {}
        return history;
    }

    private static void appendBounded(StringBuilder line, char[] buf, int from, int to) {
        int room = MAX_ENTRY_CHARS - line.length();
        if (room > 0 && to > from) line.append(buf, from, Math.min(room, to - from));
    }

    private static void addHistoryLine(ArrayDeque<String> tail, StringBuilder line) {
        int end = line.length();
        if (end > 0 && line.charAt(end - 1) == '\r') end--;
        if (end > 0 && Character.isHighSurrogate(line.charAt(end - 1))) end--;
        if (tail.size() == MAX_LINES) tail.removeFirst();
        tail.addLast(line.substring(0, end));
    }

    public static void append(String line) {
        if (line == null || line.isEmpty()) return;
        if (line.length() > MAX_LINE_CHARS) {
            int end = Character.isHighSurrogate(line.charAt(MAX_LINE_CHARS - 1)) ? MAX_LINE_CHARS - 1 : MAX_LINE_CHARS;
            line = line.substring(0, end) + " [truncated]";
        }
        String time = TS.get().format(new Date());
        synchronized (LOCK) {
            if (line.equals(lastBody) && !LINES.isEmpty()) {
                lastBodyRepeat++;
                Entry last = LINES.removeLast();
                bufferedBytes -= last.bytes;
                if (last.seq <= writtenSeq) replaceLast = true;
                addLineLocked(lastBodyTime + " " + line + " (×" + lastBodyRepeat + ")");
                dirtyFile = true;
                revision++;
                scheduleFlushLocked(FLUSH_DELAY_MS);
                return;
            }
            String entry = time + " " + line;
            dirtyFile = true;
            revision++;
            lastBody = line;
            lastBodyRepeat = 1;
            lastBodyTime = time;
            addLineLocked(entry);
            scheduleFlushLocked(FLUSH_DELAY_MS);
        }
    }

    private static void addLineLocked(String text) {
        addEntryLocked(new Entry(++nextSeq, text));
    }

    private static void addEntryLocked(Entry entry) {
        LINES.addLast(entry);
        bufferedBytes += entry.bytes;
        while (LINES.size() > MAX_LINES || bufferedBytes > MAX_FILE_BYTES)
            bufferedBytes -= LINES.removeFirst().bytes;
    }

    private static void scheduleFlushLocked(long delayMs) {
        if (!initialized || ioHandler == null) return;
        if (flushScheduled && delayMs > 0) return;
        ioHandler.removeCallbacks(FLUSH_RUNNABLE);
        flushScheduled = ioHandler.postDelayed(FLUSH_RUNNABLE, delayMs);
    }

    private static boolean persistOnIoThread(boolean sync) {
        try {
            List<Entry> batch = new ArrayList<>();
            boolean full;
            boolean trim;
            long capturedRevision;
            File file;
            synchronized (LOCK) {
                flushScheduled = false;
                if (!initialized) return false;
                long pendingBytes = 0;
                Iterator<Entry> newest = LINES.descendingIterator();
                while (newest.hasNext()) {
                    Entry entry = newest.next();
                    if (entry.seq <= writtenSeq) break;
                    batch.add(entry);
                    pendingBytes += entry.bytes;
                }
                full = rewriteAll || (replaceLast && localLastStart < 0)
                        || localBytes + pendingBytes > MAX_LOCAL_BYTES;
                if (full) batch = new ArrayList<>(LINES);
                else Collections.reverse(batch);
                trim = replaceLast && !full;
                writtenSeq = nextSeq;
                rewriteAll = false;
                replaceLast = false;
                capturedRevision = revision;
                file = logFile;
            }
            boolean saved = full ? rewriteBoth(file, batch) : appendBoth(file, batch, trim, sync);
            synchronized (LOCK) {
                if (!saved) rewriteAll = true;
                else if (revision == capturedRevision) dirtyFile = false;
            }
            return saved;
        } catch (Throwable t) {
            synchronized (LOCK) {
                rewriteAll = true;
            }
            return false;
        }
    }

    private static byte[] encode(List<Entry> entries) {
        long total = 0;
        for (Entry entry : entries) total += entry.bytes;
        byte[] out = new byte[(int) total];
        int pos = 0;
        for (Entry entry : entries) {
            byte[] text = entry.text.getBytes(StandardCharsets.UTF_8);
            System.arraycopy(text, 0, out, pos, text.length);
            pos += text.length;
            out[pos++] = '\n';
        }
        return out;
    }

    private static long lastBytes(List<Entry> entries) {
        return entries.isEmpty() ? 0 : entries.get(entries.size() - 1).bytes;
    }

    private static boolean rewriteBoth(File file, List<Entry> entries) {
        byte[] data = encode(entries);
        long last = lastBytes(entries);
        closeLocal();
        boolean saved = rewriteLocalFile(file, data);
        if (saved) {
            localBytes = data.length;
            localLastStart = entries.isEmpty() ? -1 : data.length - last;
        }
        rewriteMirror(data, last);
        return saved;
    }

    private static boolean appendBoth(File file, List<Entry> entries, boolean trim, boolean sync) {
        byte[] data = encode(entries);
        long last = lastBytes(entries);
        boolean saved = appendLocal(file, data, last, trim, sync);
        appendMirror(data, last, trim);
        return saved;
    }

    private static boolean appendLocal(File file, byte[] data, long last, boolean trim, boolean sync) {
        try {
            if (data.length > 0 || trim) {
                if (localOut == null) {
                    localOut = new FileOutputStream(file, true);
                    localBytes = Os.fstat(localOut.getFD()).st_size;
                }
                if (trim) {
                    Os.ftruncate(localOut.getFD(), localLastStart);
                    localBytes = localLastStart;
                }
                if (data.length > 0) {
                    localOut.write(data);
                    localLastStart = localBytes + data.length - last;
                    localBytes += data.length;
                }
                localUnsynced = true;
            }
            if (sync && localUnsynced) {
                localOut.getFD().sync();
                localUnsynced = false;
            }
            return true;
        } catch (Throwable t) {
            closeLocal();
            return false;
        }
    }

    private static void appendMirror(byte[] data, long last, boolean trim) {
        FileOutputStream out = mirrorOut;
        if (out == null || mirrorPfd == null || (data.length == 0 && !trim)) return;
        try {
            long size = mirrorBytes;
            if (trim) {
                size = Os.fstat(mirrorPfd.getFileDescriptor()).st_size;
                if (mirrorLastStart >= 0 && size == mirrorBytes) {
                    Os.ftruncate(mirrorPfd.getFileDescriptor(), mirrorLastStart);
                    size = mirrorLastStart;
                }
            }
            if (data.length > 0) {
                out.write(data);
                mirrorLastStart = size + data.length - last;
                size += data.length;
            }
            mirrorBytes = size;
        } catch (Throwable t) {
            closeMirror();
        }
    }

    private static void rewriteMirror(byte[] data, long last) {
        if (mirrorOut == null || mirrorPfd == null) return;
        try {
            Os.ftruncate(mirrorPfd.getFileDescriptor(), 0);
            if (data.length > 0) mirrorOut.write(data);
            mirrorBytes = data.length;
            mirrorLastStart = data.length == 0 ? -1 : data.length - last;
        } catch (Throwable t) {
            closeMirror();
        }
    }

    public static boolean flush() {
        Handler handler;
        synchronized (LOCK) {
            if (logFile == null || ioHandler == null) return false;
            handler = ioHandler;
        }
        if (Looper.myLooper() == handler.getLooper()) return flushOnIoThread();
        CountDownLatch done = new CountDownLatch(1);
        AtomicBoolean saved = new AtomicBoolean();
        if (!handler.post(() -> {
            try { saved.set(flushOnIoThread()); }
            catch (Throwable ignored) {}
            finally { done.countDown(); }
        })) return false;
        try { return done.await(10, TimeUnit.SECONDS) && saved.get(); }
        catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    private static boolean flushOnIoThread() {
        synchronized (LOCK) {
            ioHandler.removeCallbacks(FLUSH_RUNNABLE);
            flushScheduled = false;
        }
        return persistOnIoThread(true);
    }

    private static boolean rewriteLocalFile(File file, byte[] data) {
        if (file == null) return false;
        AtomicFile atomic = new AtomicFile(file);
        FileOutputStream output = null;
        try {
            output = atomic.startWrite();
            output.write(data);
            output.getFD().sync();
            atomic.finishWrite(output);
            output = null;
            return file.isFile() && file.length() == data.length;
        } catch (Throwable ignored) {
            if (output != null) atomic.failWrite(output);
            return false;
        }
    }

    public static void clear() {
        synchronized (LOCK) {
            LINES.clear();
            bufferedBytes = 0;
            lastBody = null;
            lastBodyRepeat = 1;
            lastBodyTime = null;
            dirtyFile = true;
            rewriteAll = true;
            revision++;
            if (!initialized) clearedBeforeInit = true;
            scheduleFlushLocked(0);
        }
    }

    public static String getSnapshot() {
        synchronized (LOCK) {
            return buildSnapshot(false);
        }
    }

    public static String getSnapshotForIpc() {
        synchronized (LOCK) {
            return buildSnapshot(true);
        }
    }

    private static String buildSnapshot(boolean forIpc) {
        if (LINES.isEmpty()) return "";
        StringBuilder sb = new StringBuilder(LINES.size() * 96);
        for (Entry e : LINES) sb.append(e.text).append('\n');
        String snap = sb.toString();
        if (!forIpc || snap.length() <= IPC_MAX_CHARS) return snap;
        return "(truncated — older lines omitted)\n\n" + snap.substring(snap.length() - IPC_MAX_CHARS + 40);
    }
}
