package ps.reso.instaeclipse.fragments;

import java.io.File;
import java.io.FileOutputStream;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

import ps.reso.instaeclipse.fragments.LogEntries.Entry;

final class LogExport {

    private LogExport() {}

    static String tail(List<String> full, int maxChars) {
        int start = full.size();
        long length = 0;
        while (start > 0) {
            int next = full.get(start - 1).length() + 1;
            if (length + next > maxChars) break;
            length += next;
            start--;
        }
        StringBuilder sb = new StringBuilder((int) length);
        for (int i = start; i < full.size(); i++) sb.append(full.get(i)).append('\n');
        return sb.toString();
    }

    static String stamp(long millis) {
        return new SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US).format(new Date(millis));
    }

    static List<Entry> sinceClear(List<Entry> entries, String clearedAt) {
        if (clearedAt == null || clearedAt.isEmpty()) return entries;
        List<Entry> out = new ArrayList<>(entries.size());
        for (Entry e : entries) if (e.ts.compareTo(clearedAt) > 0) out.add(e);
        return out;
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
