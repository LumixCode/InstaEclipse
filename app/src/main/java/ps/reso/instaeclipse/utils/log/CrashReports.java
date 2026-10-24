package ps.reso.instaeclipse.utils.log;

import android.content.Context;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.net.Uri;

import androidx.core.content.FileProvider;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import ps.reso.instaeclipse.BuildConfig;
import ps.reso.instaeclipse.utils.core.CommonUtils;

public final class CrashReports {
    static final String DIR = "crash-report";
    static final String SUMMARY = "summary.txt";
    static final String AUTHORITY_SUFFIX = ".crashreports";
    static final long MAX_EXCERPT_BYTES = 16L * 1024 * 1024;
    static final int MAX_SUMMARY_LINES = 1000;
    static final long SAME_CRASH_MS = 60_000;
    private static final String TAG = "(IE|Crash) ";
    private static final String STAMP = "yyyyMMdd-HHmmss";
    private static final String HOOKS = "_hooks.txt";
    private static final String DUMP = ".dmp";
    private static final String TOMBSTONE = "_tombstone.pb";

    private CrashReports() {}

    public static int count(Context ctx) {
        return crashes(newestFirst(LogMirrorProvider.crashDir(ctx))).size();
    }

    public static Uri share(Context ctx, boolean full) throws IOException {
        String text = summary(versions(ctx), crashLines(LogMirrorProvider.mirrorDir(ctx)));
        File zip = write(new File(ctx.getCacheDir(), DIR), LogMirrorProvider.crashDir(ctx), text, full,
                System.currentTimeMillis());
        return FileProvider.getUriForFile(ctx, ctx.getPackageName() + AUTHORITY_SUFFIX, zip);
    }

    public static int delete(Context ctx) {
        clear(new File(ctx.getCacheDir(), DIR));
        return clear(LogMirrorProvider.crashDir(ctx));
    }

    private static List<String> versions(Context ctx) {
        List<String> out = new ArrayList<>();
        out.add("InstaEclipse " + BuildConfig.VERSION_NAME + " (" + BuildConfig.VERSION_CODE + ")");
        PackageManager pm = ctx.getPackageManager();
        for (String pkg : CommonUtils.SUPPORTED_PACKAGES) {
            try {
                PackageInfo info = pm.getPackageInfo(pkg, 0);
                out.add(pkg + " " + info.versionName + " (" + info.getLongVersionCode() + ")");
            } catch (PackageManager.NameNotFoundException ignored) {
            }
        }
        if (out.size() == 1) out.add("Instagram not installed");
        return out;
    }

    static String summary(List<String> versions, List<String> crashLines) {
        StringBuilder sb = new StringBuilder();
        for (String v : versions) sb.append(v).append('\n');
        sb.append('\n');
        if (crashLines.isEmpty()) sb.append("No crash lines in the saved log\n");
        for (String l : crashLines) sb.append(l).append('\n');
        return sb.toString();
    }

    static List<String> crashLines(File mirrorDir) {
        List<String> out = new ArrayList<>();
        File[] logs = mirrorDir.listFiles((d, n) -> n.endsWith(".log"));
        if (logs == null) return out;
        Arrays.sort(logs);
        for (File log : logs) {
            ArrayDeque<String> lines = new ArrayDeque<>();
            try (BufferedReader in = new BufferedReader(new InputStreamReader(new FileInputStream(log), StandardCharsets.UTF_8))) {
                String line;
                while ((line = in.readLine()) != null) {
                    if (!line.contains(TAG)) continue;
                    lines.addLast(line);
                    if (lines.size() > MAX_SUMMARY_LINES) lines.pollFirst();
                }
            } catch (IOException ignored) {
            }
            if (lines.isEmpty()) continue;
            out.add("# " + log.getName());
            out.addAll(lines);
        }
        return out;
    }

    static List<File> newestFirst(File crashDir) {
        File[] files = crashDir.listFiles(File::isFile);
        if (files == null) return new ArrayList<>();
        Map<File, Long> times = new HashMap<>();
        for (File f : files) times.put(f, time(f));
        List<File> out = new ArrayList<>(Arrays.asList(files));
        out.sort((a, b) -> {
            int c = Long.compare(times.get(b), times.get(a));
            return c != 0 ? c : b.getName().compareTo(a.getName());
        });
        return out;
    }

    static long time(File f) {
        String name = f.getName();
        if (name.length() > STAMP.length() && name.charAt(STAMP.length()) == '_') {
            SimpleDateFormat fmt = new SimpleDateFormat(STAMP, Locale.ROOT);
            fmt.setLenient(false);
            try {
                Date d = fmt.parse(name.substring(0, STAMP.length()));
                if (d != null) return d.getTime();
            } catch (ParseException ignored) {
            }
        }
        return f.lastModified();
    }

    static List<List<File>> crashes(List<File> newestFirst) {
        List<List<File>> out = new ArrayList<>();
        long start = 0;
        for (File f : newestFirst) {
            long t = time(f);
            if (out.isEmpty() || start - t > SAME_CRASH_MS) {
                out.add(new ArrayList<>());
                start = t;
            }
            out.get(out.size() - 1).add(f);
        }
        return out;
    }

    static List<File> summaryFiles(List<File> newestFirst) {
        List<File> out = new ArrayList<>();
        for (File f : newestFirst) if (f.getName().endsWith(HOOKS)) out.add(f);
        return out;
    }

    static List<File> fullFiles(List<File> newestFirst) {
        for (List<File> crash : crashes(newestFirst)) {
            List<File> out = new ArrayList<>();
            File dump = first(crash, DUMP);
            File tombstone = first(crash, TOMBSTONE);
            if (dump != null) out.add(dump);
            if (tombstone != null) out.add(tombstone);
            if (!out.isEmpty()) return out;
        }
        return new ArrayList<>();
    }

    private static File first(List<File> files, String suffix) {
        for (File f : files) if (f.getName().endsWith(suffix)) return f;
        return null;
    }

    static File write(File reportDir, File crashDir, String summary, boolean full, long now) throws IOException {
        clear(reportDir);
        if (!reportDir.isDirectory() && !reportDir.mkdirs()) throw new IOException("cannot create " + reportDir);
        File zip = new File(reportDir, new SimpleDateFormat(STAMP, Locale.ROOT).format(new Date(now)) + ".zip");
        List<File> files = newestFirst(crashDir);
        try (ZipOutputStream out = new ZipOutputStream(new FileOutputStream(zip))) {
            out.putNextEntry(new ZipEntry(SUMMARY));
            out.write(summary.getBytes(StandardCharsets.UTF_8));
            out.closeEntry();
            for (File f : summaryFiles(files)) add(out, f, CrashForensics.MAX_COPY_BYTES);
            if (full) {
                for (File f : fullFiles(files)) {
                    add(out, f, f.getName().endsWith(TOMBSTONE) ? MAX_EXCERPT_BYTES : CrashForensics.MAX_COPY_BYTES);
                }
            }
        } catch (IOException | RuntimeException e) {
            zip.delete();
            throw e;
        }
        return zip;
    }

    private static void add(ZipOutputStream out, File f, long max) throws IOException {
        out.putNextEntry(new ZipEntry(f.getName()));
        try (InputStream in = new FileInputStream(f)) {
            byte[] buf = new byte[64 * 1024];
            long left = max;
            int n;
            while (left > 0 && (n = in.read(buf, 0, (int) Math.min(buf.length, left))) > 0) {
                out.write(buf, 0, n);
                left -= n;
            }
        }
        out.closeEntry();
    }

    static int clear(File dir) {
        File[] files = dir.listFiles(File::isFile);
        if (files == null) return 0;
        int n = 0;
        for (File f : files) if (f.delete()) n++;
        return n;
    }
}
