package ps.reso.instaeclipse.fragments;

import java.io.File;
import java.io.FileOutputStream;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Locale;

import ps.reso.instaeclipse.fragments.LogEntries.Entry;

final class LogExport {

    private LogExport() {}

    static String tail(List<String> full, int maxChars) {
        StringBuilder sb = new StringBuilder();
        for (int i = full.size() - 1; i >= 0; i--) {
            String s = full.get(i);
            if (sb.length() + s.length() + 1 > maxChars) break;
            sb.insert(0, s + "\n");
        }
        return sb.toString();
    }

    static String stamp(long millis) {
        return new SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US).format(new Date(millis));
    }

    static List<Entry> sinceClear(List<Entry> entries, String clearedAt) {
        return entries;
    }

    static void truncate(File dir) {
        File[] files = dir.listFiles();
        if (files == null) return;
        for (File f : files) {
            try (FileOutputStream fos = new FileOutputStream(f, false)) {
                fos.getFD();
            } catch (Throwable ignored) {}
        }
    }
}
