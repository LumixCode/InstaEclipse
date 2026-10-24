package ps.reso.instaeclipse.mods.media;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.Comparator;
import java.util.List;

final class HtmlInliner {
    static final long EACH_MAX = 16L * 1024 * 1024;
    static final long TOTAL_MAX = 48L * 1024 * 1024;
    static final int CHUNK = 48 * 1024;

    interface Source {
        InputStream open() throws IOException;
    }

    private static final class Inline {
        final int start, end;
        final String mime;
        final Source source;

        Inline(int start, int end, String mime, Source source) {
            this.start = start;
            this.end = end;
            this.mime = mime;
            this.source = source;
        }
    }

    private final String html;
    private final List<Inline> inlines = new ArrayList<>();
    private long inlined;

    HtmlInliner(String html) {
        this.html = html;
    }

    boolean offer(String name, String mime, long length, Source source) {
        if (name == null || length > EACH_MAX || inlined + length > TOTAL_MAX) return false;
        String token = "src=\"" + name + "\"";
        int at = html.indexOf(token);
        while (at >= 0 && claimed(at, token.length())) at = html.indexOf(token, at + 1);
        if (at < 0) return false;
        inlines.add(new Inline(at, at + token.length(), mime, source));
        inlined += length;
        return true;
    }

    private boolean claimed(int at, int length) {
        for (Inline inline : inlines) if (at < inline.end && at + length > inline.start) return true;
        return false;
    }

    void write(OutputStream out) throws IOException {
        List<Inline> ordered = new ArrayList<>(inlines);
        ordered.sort(Comparator.comparingInt(i -> i.start));
        Base64.Encoder encoder = Base64.getEncoder();
        byte[] chunk = new byte[CHUNK];
        int from = 0;
        for (Inline inline : ordered) {
            out.write(html.substring(from, inline.start).getBytes(StandardCharsets.UTF_8));
            out.write(("src=\"data:" + inline.mime + ";base64,").getBytes(StandardCharsets.UTF_8));
            try (InputStream in = inline.source.open()) {
                int n;
                while ((n = fill(in, chunk)) > 0) {
                    out.write(encoder.encode(n == chunk.length ? chunk : Arrays.copyOf(chunk, n)));
                }
            }
            out.write('"');
            from = inline.end;
        }
        out.write(html.substring(from).getBytes(StandardCharsets.UTF_8));
    }

    private static int fill(InputStream in, byte[] buffer) throws IOException {
        int filled = 0;
        while (filled < buffer.length) {
            int n = in.read(buffer, filled, buffer.length - filled);
            if (n < 0) break;
            filled += n;
        }
        return filled;
    }
}
