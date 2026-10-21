package ps.reso.instaeclipse.utils.log;

import android.app.ActivityManager;
import android.app.ApplicationExitInfo;
import android.content.Context;
import android.net.Uri;
import android.os.Build;
import android.os.ParcelFileDescriptor;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Field;
import java.lang.reflect.Member;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Saves what explains Instagram's last crash into the companion app ({@code files/ig_crash/},
 * through {@link LogMirrorProvider}) and logs one line per earlier process exit.
 *
 * <p>Three sources, read once per start on a background thread:
 * <ul>
 *   <li>Android's exit records ({@link ApplicationExitInfo}): reason, signal, description, and for
 *   a native crash the full tombstone (protobuf, every thread) or an ANR's traces.</li>
 *   <li>Instagram's Breakpad minidumps ({@code app_minidumps}). Breakpad catches native signals
 *   before debuggerd, so its dump keeps the crashing thread's registers and stack (the system
 *   tombstone stops at Breakpad's handler in the unreadable libstartup.so) plus the abort message.
 *   Instagram uploads and deletes them on a later start, so they are copied first thing.</li>
 *   <li>The list of methods this module hooked in the crashed process, with their ArtMethod
 *   addresses ({@link #noteHook}). When ART aborts on "GC tried to mark invalid reference", the
 *   bad value is checked against that list, because on IG 451 / LSPatch 1.2 it was the low half
 *   of an ArtMethod-like pointer written into a DexCache root slot.</li>
 * </ul>
 * The keys already saved are kept in {@code files/instaeclipse_crash_seen} so each item is saved once.
 */
public final class CrashForensics {
    private static final String TAG = "(IE|Crash) ";
    private static final Uri CRASH_URI = Uri.parse("content://ps.reso.instaeclipse.logmirror/crash/");
    private static final String SEEN_FILE = "instaeclipse_crash_seen";
    private static final String HOOKS_DIR = "instaeclipse_hooks";
    /** exit records and dumps older than this are history, not news */
    private static final long MAX_AGE_MS = 7L * 24 * 60 * 60 * 1000;
    private static final long MAX_COPY_BYTES = 64L * 1024 * 1024;
    private static final int MAX_PARSE_BYTES = 16 * 1024 * 1024;
    private static final int MAX_EXITS = 16;
    private static final int MAX_SEEN = 200;
    private static final int KEEP_HOOK_FILES = 6;
    private static final Pattern GC_BAD_REF = Pattern.compile("GC tried to mark invalid reference 0x([0-9a-f]{1,16})");

    /** "artmethod-hex member" per hook installed in this process */
    private static final List<String> hookLines = new ArrayList<>();
    private static volatile File hooksDir;
    private static volatile boolean hookWritePending;
    private static boolean hookListLogged;
    private static Field artMethodField;
    private static volatile String artMethodError;

    private CrashForensics() {}

    /** Records a hooked member and its ArtMethod address; called by XposedBridge.hookMethod. */
    public static void noteHook(Member member) {
        try {
            String line = Long.toHexString(artMethod(member)) + " " + member;
            synchronized (hookLines) {
                hookLines.add(line);
            }
            scheduleHookWrite();
        } catch (Throwable ignored) {
            // diagnostics only
        }
    }

    private static long artMethod(Member member) {
        if (artMethodError != null) return 0;
        try {
            if (artMethodField == null) {
                Field f = Class.forName("java.lang.reflect.Executable").getDeclaredField("artMethod");
                f.setAccessible(true);
                artMethodField = f;
            }
            return artMethodField.getLong(member);
        } catch (Throwable e) {
            artMethodError = String.valueOf(e);
            return 0;
        }
    }

    public static void collectAsync(Context context) {
        Context app = context.getApplicationContext() != null ? context.getApplicationContext() : context;
        Thread t = new Thread(() -> {
            try {
                collect(app);
            } catch (Throwable e) {
                ModuleLog.line(TAG + "collect failed: " + e);
            }
        }, "IE-CrashForensics");
        t.setPriority(Thread.MIN_PRIORITY);
        t.start();
    }

    private static void collect(Context ctx) {
        File seenFile = new File(ctx.getFilesDir(), SEEN_FILE);
        List<String> seenOrder = readSeen(seenFile);
        Set<String> seen = new HashSet<>(seenOrder);
        int before = seenOrder.size();
        long now = System.currentTimeMillis();
        File hooks = new File(ctx.getFilesDir(), HOOKS_DIR);
        //noinspection ResultOfMethodCallIgnored
        hooks.mkdirs();

        // Dumps first: Instagram's uploader may delete them soon after start.
        File dataDir = new File(ctx.getApplicationInfo().dataDir);
        File[] dirs = dataDir.listFiles(f -> f.isDirectory()
                && f.getName().toLowerCase(Locale.ROOT).matches(".*(minidump|breakpad|crash).*"));
        if (dirs != null) {
            for (File dir : dirs) copyDumps(ctx, dir, dir.getName(), 0, now, seen, seenOrder);
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) exits(ctx, hooks, now, seen, seenOrder);

        if (seenOrder.size() != before) writeSeen(seenFile, seenOrder);

        // Only now: the crashed process's hook list had to be read first.
        pruneHookFiles(hooks);
        hooksDir = hooks;
        scheduleHookWrite();
        if (artMethodError != null) ModuleLog.line(TAG + "ArtMethod addresses unavailable: " + artMethodError);
    }

    private static void copyDumps(Context ctx, File dir, String rel, int depth, long now,
                                  Set<String> seen, List<String> seenOrder) {
        File[] files = dir.listFiles();
        if (files == null) return;
        for (File f : files) {
            String path = rel + "/" + f.getName();
            if (f.isDirectory()) {
                if (depth < 2) copyDumps(ctx, f, path, depth + 1, now, seen, seenOrder);
                continue;
            }
            long len = f.length();
            if (len == 0 || now - f.lastModified() > MAX_AGE_MS) continue;
            String key = "file:" + path + ":" + len + ":" + f.lastModified();
            if (seen.contains(key)) continue;
            if (len > MAX_COPY_BYTES) {
                remember(key, seen, seenOrder);
                ModuleLog.line(TAG + "skipped " + path + " (" + mb(len) + ", over the copy limit)");
                continue;
            }
            String name = stamp(f.lastModified()) + "_" + path.replace('/', '~');
            try (InputStream in = new FileInputStream(f)) {
                long n = save(ctx, name, in, null);
                remember(key, seen, seenOrder); // a failed copy is retried on the next start
                ModuleLog.line(TAG + "saved " + path + " from " + stamp(f.lastModified()) + " → ig_crash/" + name + " (" + mb(n) + ")");
            } catch (Throwable e) {
                ModuleLog.line(TAG + "could not save " + path + ": " + e);
                continue;
            }
            if (f.getName().endsWith(".dmp") && len <= MAX_PARSE_BYTES) describeMinidump(f);
        }
    }

    /** Logs the crashing thread's name and the abort message Breakpad stored in the dump. */
    private static void describeMinidump(File f) {
        try {
            ByteBuffer b = ByteBuffer.wrap(Files.readAllBytes(f.toPath())).order(ByteOrder.LITTLE_ENDIAN);
            if (b.getInt(0) != 0x504d444d) return; // "MDMP"
            int n = b.getInt(8), dir = b.getInt(12);
            String thread = null, abort = null;
            for (int i = 0; i < n; i++) {
                int type = b.getInt(dir + 12 * i), size = b.getInt(dir + 12 * i + 4), rva = b.getInt(dir + 12 * i + 8);
                if (size <= 0 || rva < 0 || rva + size > b.capacity()) continue;
                if (type == 0xfacee000) thread = text(b, rva, size);
                else if (type == 0xfacedead) abort = text(b, rva, size);
            }
            if (thread == null && abort == null) return;
            String first = abort == null ? "(none)" : abort.split("\n", 2)[0];
            ModuleLog.line(TAG + "minidump " + f.getName() + ": thread " + thread + ", abort '" + first + "'");
        } catch (Throwable e) {
            ModuleLog.line(TAG + "could not read minidump " + f.getName() + ": " + e);
        }
    }

    private static String text(ByteBuffer b, int off, int len) {
        byte[] out = new byte[len];
        for (int i = 0; i < len; i++) out[i] = b.get(off + i);
        return new String(out, StandardCharsets.UTF_8).trim();
    }

    @android.annotation.TargetApi(Build.VERSION_CODES.R)
    private static void exits(Context ctx, File hooks, long now, Set<String> seen, List<String> seenOrder) {
        ActivityManager am = ctx.getSystemService(ActivityManager.class);
        if (am == null) return;
        List<ApplicationExitInfo> list = am.getHistoricalProcessExitReasons(null, 0, MAX_EXITS);
        int mine = android.os.Process.myPid();
        // Oldest first, so the log reads in order.
        for (int i = list.size() - 1; i >= 0; i--) {
            ApplicationExitInfo e = list.get(i);
            if (e.getPid() == mine || now - e.getTimestamp() > MAX_AGE_MS) continue;
            String key = "exit:" + e.getPid() + ":" + e.getTimestamp();
            if (seen.contains(key)) continue;
            remember(key, seen, seenOrder); // the trace is tried once: the record itself is logged

            String desc = e.getDescription() == null ? "" : e.getDescription().replace('\n', ' ');
            if (desc.length() > 300) desc = desc.substring(0, 300) + "…";
            ModuleLog.line(TAG + "previous exit " + stamp(e.getTimestamp()) + " pid " + e.getPid()
                    + " (" + e.getProcessName() + "): " + reason(e.getReason()) + " status=" + e.getStatus()
                    + " importance=" + e.getImportance() + " pss=" + (e.getPss() / 1024) + "MB"
                    + (desc.isEmpty() ? "" : " '" + desc + "'"));

            int r = e.getReason();
            if (r != ApplicationExitInfo.REASON_CRASH_NATIVE && r != ApplicationExitInfo.REASON_ANR) continue;
            String base = stamp(e.getTimestamp()) + "_exit_" + e.getPid();
            String name = base + (r == ApplicationExitInfo.REASON_ANR ? "_anr.txt" : "_tombstone.pb");
            ByteArrayOutputStream copy = r == ApplicationExitInfo.REASON_CRASH_NATIVE ? new ByteArrayOutputStream() : null;
            try (InputStream in = e.getTraceInputStream()) {
                if (in == null) {
                    ModuleLog.line(TAG + "no trace kept for pid " + e.getPid());
                } else {
                    long n = save(ctx, name, in, copy);
                    ModuleLog.line(TAG + "saved " + reason(r) + " trace of pid " + e.getPid() + " → ig_crash/" + name + " (" + mb(n) + ")");
                }
            } catch (Throwable ex) {
                ModuleLog.line(TAG + "could not save the trace of pid " + e.getPid() + ": " + ex);
            }
            if (r == ApplicationExitInfo.REASON_CRASH_NATIVE) diagnose(ctx, hooks, e.getPid(), base, copy);
        }
    }

    /** Saves the crashed process's hook list and checks a GC "invalid reference" against it. */
    private static void diagnose(Context ctx, File hooks, int pid, String base, ByteArrayOutputStream tombstone) {
        File list = new File(hooks, pid + ".txt");
        List<String> lines = new ArrayList<>();
        if (list.isFile()) {
            try (InputStream in = new FileInputStream(list)) {
                save(ctx, base + "_hooks.txt", in, null);
            } catch (Throwable e) {
                ModuleLog.line(TAG + "could not save the hook list of pid " + pid + ": " + e);
            }
            lines = readSeen(list);
        }
        String raw = tombstone == null ? "" : new String(tombstone.toByteArray(), StandardCharsets.ISO_8859_1);
        Matcher m = GC_BAD_REF.matcher(raw);
        if (!m.find()) return;
        long bad = Long.parseLong(m.group(1), 16) & 0xffffffffL;
        String verdict;
        if (lines.isEmpty()) {
            verdict = "no hook list was kept for that process";
        } else {
            String exact = null, near = null;
            long nearDist = Long.MAX_VALUE;
            int counted = 0;
            for (String l : lines) {
                int sp = l.indexOf(' ');
                if (l.startsWith("#") || sp <= 0) continue;
                long art;
                try {
                    art = Long.parseUnsignedLong(l.substring(0, sp), 16);
                } catch (NumberFormatException ex) {
                    continue;
                }
                if (art == 0) continue;
                counted++;
                long candidate = (art & ~0xffffffffL) | bad; // the bad value with this pointer's high half
                long d = Math.abs(art - candidate);
                if (d == 0) exact = l;
                else if (d < nearDist) {
                    nearDist = d;
                    near = l;
                }
            }
            if (exact != null) verdict = "it is the low half of hooked method " + exact;
            else if (counted == 0) verdict = "the hook list has no ArtMethod addresses";
            else verdict = "no hooked ArtMethod ends in it (" + counted + " hooked; nearest " + near
                    + ", " + nearDist + " bytes away)";
        }
        ModuleLog.line(TAG + "pid " + pid + ": GC found invalid reference 0x" + Long.toHexString(bad)
                + " in a runtime root; " + verdict);
    }

    private static void scheduleHookWrite() {
        if (hooksDir == null || hookWritePending) return;
        hookWritePending = true;
        Thread t = new Thread(() -> {
            try {
                Thread.sleep(3000); // hooks are installed in bursts: write once per burst
            } catch (InterruptedException ignored) {
            }
            hookWritePending = false;
            writeHooks();
        }, "IE-HookList");
        t.setDaemon(true);
        t.start();
    }

    private static void writeHooks() {
        File dir = hooksDir;
        if (dir == null) return;
        List<String> out = new ArrayList<>();
        out.add("# pid " + android.os.Process.myPid() + " " + stamp(System.currentTimeMillis())
                + (artMethodError != null ? " artMethod unavailable: " + artMethodError : ""));
        synchronized (hookLines) {
            out.addAll(hookLines);
        }
        File f = new File(dir, android.os.Process.myPid() + ".txt");
        File tmp = new File(dir, f.getName() + ".tmp");
        try {
            Files.write(tmp.toPath(), out, StandardCharsets.UTF_8);
            if (!tmp.renameTo(f)) throw new IOException("rename failed");
            if (!hookListLogged) {
                hookListLogged = true;
                ModuleLog.line(TAG + "keeping this process's hook list: " + (out.size() - 1) + " methods, ArtMethod addresses "
                        + (artMethodError == null ? "ok" : "unavailable"));
            }
        } catch (Throwable e) {
            ModuleLog.line(TAG + "could not write the hook list: " + e);
        }
    }

    /** Keeps the newest hook lists; the crashed process's was already read. */
    private static void pruneHookFiles(File dir) {
        File[] files = dir.listFiles(File::isFile);
        if (files == null || files.length <= KEEP_HOOK_FILES) return;
        Arrays.sort(files, (a, b) -> Long.compare(b.lastModified(), a.lastModified()));
        for (int i = KEEP_HOOK_FILES; i < files.length; i++) //noinspection ResultOfMethodCallIgnored
            files[i].delete();
    }

    /**
     * Streams {@code in} into {@code ig_crash/<name>} in the companion, keeping a copy in
     * {@code tee} (up to {@link #MAX_PARSE_BYTES}) when given; returns the bytes copied. A source
     * over {@link #MAX_COPY_BYTES} is cut there, and the log says so.
     */
    private static long save(Context ctx, String name, InputStream in, ByteArrayOutputStream tee) throws IOException {
        Uri uri = CRASH_URI.buildUpon().appendPath(name).build();
        long total = 0;
        try (ParcelFileDescriptor pfd = ctx.getContentResolver().openFileDescriptor(uri, "w")) {
            if (pfd == null) throw new IOException("companion refused " + name);
            try (FileOutputStream out = new FileOutputStream(pfd.getFileDescriptor())) {
                byte[] buf = new byte[64 * 1024];
                int n;
                while (total < MAX_COPY_BYTES
                        && (n = in.read(buf, 0, (int) Math.min(buf.length, MAX_COPY_BYTES - total))) > 0) {
                    out.write(buf, 0, n);
                    if (tee != null && tee.size() < MAX_PARSE_BYTES) tee.write(buf, 0, n);
                    total += n;
                }
                out.getFD().sync();
                if (total >= MAX_COPY_BYTES && in.read() >= 0) {
                    ModuleLog.line(TAG + "ig_crash/" + name + " is cut at " + mb(total) + ": the source is larger");
                }
            }
        }
        return total;
    }

    private static void remember(String key, Set<String> seen, List<String> seenOrder) {
        seen.add(key);
        seenOrder.add(key);
    }

    private static List<String> readSeen(File f) {
        try {
            return f.isFile() ? new ArrayList<>(Files.readAllLines(f.toPath(), StandardCharsets.UTF_8)) : new ArrayList<>();
        } catch (Throwable e) {
            return new ArrayList<>();
        }
    }

    private static void writeSeen(File f, List<String> keys) {
        List<String> keep = keys.size() > MAX_SEEN ? keys.subList(keys.size() - MAX_SEEN, keys.size()) : keys;
        try {
            Files.write(f.toPath(), keep, StandardCharsets.UTF_8);
        } catch (Throwable e) {
            ModuleLog.line(TAG + "could not write " + f.getName() + ": " + e);
        }
    }

    private static String reason(int r) {
        switch (r) {
            case 1: return "EXIT_SELF";
            case 2: return "SIGNALED";
            case 3: return "LOW_MEMORY";
            case 4: return "CRASH";
            case 5: return "CRASH_NATIVE";
            case 6: return "ANR";
            case 7: return "INITIALIZATION_FAILURE";
            case 8: return "PERMISSION_CHANGE";
            case 9: return "EXCESSIVE_RESOURCE_USAGE";
            case 10: return "USER_REQUESTED";
            case 11: return "USER_STOPPED";
            case 12: return "DEPENDENCY_DIED";
            case 13: return "OTHER";
            case 14: return "FREEZER";
            case 15: return "PACKAGE_STATE_CHANGE";
            case 16: return "PACKAGE_UPDATED";
            default: return "REASON_" + r;
        }
    }

    private static String stamp(long ms) {
        return new SimpleDateFormat("yyyyMMdd-HHmmss", Locale.ROOT).format(new Date(ms));
    }

    private static String mb(long bytes) {
        return String.format(Locale.ROOT, "%.1f MB", bytes / 1048576.0);
    }
}
