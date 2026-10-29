package ps.reso.instaeclipse.mods.media;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.OutputStream;
import ps.reso.instaeclipse.utils.download.DownloadEngine;

public final class DownloadFiles {
    private static final java.util.regex.Pattern UNSAFE = java.util.regex.Pattern.compile("[^\\p{L}\\p{M}\\p{N} ._-]");

    private DownloadFiles() {}

    public static String safeSegment(String name, String fallback) {
        return clean(name, fallback, 40);
    }

    static String segment(String value, int max) {
        return clean(value, "unknown", max);
    }

    private static String clean(String name, String fallback, int max) {
        String safe = trim(name == null ? "" : UNSAFE.matcher(name).replaceAll("_"));
        if (safe.length() > max) {
            int end = Character.isHighSurrogate(safe.charAt(max - 1)) ? max - 1 : max;
            safe = trim(safe.substring(0, end));
        }
        for (int i = 0; i < safe.length(); ) {
            int c = safe.codePointAt(i);
            if (Character.isLetterOrDigit(c)) return safe;
            i += Character.charCount(c);
        }
        return fallback == null || fallback.isEmpty() ? "unknown" : fallback;
    }

    private static String trim(String value) {
        int start = 0, end = value.length();
        while (start < end && (value.charAt(start) == '.' || value.charAt(start) == ' ')) start++;
        while (end > start && (value.charAt(end - 1) == '.' || value.charAt(end - 1) == ' ')) end--;
        return value.substring(start, end);
    }

    static void copy(File source, OutputStream out, DownloadEngine.Cancellation cancellation) throws IOException {
        long count = 0;
        try (FileInputStream in = new FileInputStream(source)) {
            byte[] buffer = new byte[32768];
            int read;
            while ((read = in.read(buffer)) != -1) {
                check(cancellation);
                out.write(buffer, 0, read);
                count += read;
            }
        }
        out.flush();
        check(cancellation);
        if (count != source.length() || count == 0) throw new IOException("Incomplete file copy");
    }

    static void check(DownloadEngine.Cancellation cancellation) throws IOException {
        if (cancellation != null && cancellation.isCancelled()) throw new IOException("Download cancelled");
    }
}
