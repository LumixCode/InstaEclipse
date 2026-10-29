/*
 * Forked from:
 * https://github.com/andrewliang25/morphe-patches/blob/5db2e57e133aede5297c48b419168cf30fd89953/extensions/extension/src/main/java/app/andrewliang/extension/Downloader.java
 * Copyright 2026 Andrew Liang (GPL-3.0).
 *
 * Modified for Hushfacebook (Facebook), 2026.
 * Modified for HushGram (Instagram), 2026.
 */
package ps.reso.instaeclipse.utils.download;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.MalformedURLException;
import java.net.URL;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

final class Downloader {

    private Downloader() {}

    enum Status {
        OK,
        EXPIRED,
        HTTP_ERROR,
        NETWORK_ERROR,
        WRITE_ERROR,
        STORAGE_ERROR,
        REFUSED,
        TOO_LARGE,
        CANCELLED
    }

    interface Progress {
        void transferred(long done, long total);

        void reading(Runnable close);

        boolean cancelled();

        default void joining() {
        }
    }

    static final Progress SILENT = new Progress() {
        @Override
        public void transferred(long done, long total) {
        }

        @Override
        public void reading(Runnable close) {
        }

        @Override
        public boolean cancelled() {
            return false;
        }
    };

    private static final class Cancelled extends IOException {
        Cancelled() {
            super("cancelled");
        }
    }

    private static final class StorageFailure extends IOException {
        StorageFailure(IOException cause) {
            super(cause);
        }
    }

    private static final class CacheFile extends OutputStream {
        private final OutputStream file;

        CacheFile(File into) throws StorageFailure {
            try {
                file = new FileOutputStream(into);
            } catch (IOException e) {
                throw new StorageFailure(e);
            }
        }

        @Override
        public void write(int value) throws IOException {
            write(new byte[] { (byte) value }, 0, 1);
        }

        @Override
        public void write(byte[] bytes, int offset, int length) throws IOException {
            try {
                file.write(bytes, offset, length);
            } catch (IOException e) {
                throw new StorageFailure(e);
            }
        }

        @Override
        public void close() throws IOException {
            try {
                file.close();
            } catch (IOException e) {
                throw new StorageFailure(e);
            }
        }
    }

    enum Kind { VIDEO, AUDIO, IMAGE }

    static final class Result {
        final Status status;
        final String reason;
        final String mime;

        private Result(Status status, String reason, String mime) {
            this.status = status;
            this.reason = reason;
            this.mime = mime;
        }

        static Result ok(String mime) {
            return new Result(Status.OK, null, mime);
        }

        static Result fail(Status status, String reason) {
            return new Result(status, reason, null);
        }

        boolean ok() {
            return status == Status.OK;
        }

        @Override
        public String toString() {
            return reason == null ? status.name() : status + " (" + reason + ")";
        }
    }

    private static final int CONNECT_TIMEOUT_MS = 15_000;
    private static final int READ_TIMEOUT_MS = 20_000;

    static volatile int readTimeoutMs = READ_TIMEOUT_MS;
    private static final int BUFFER = 64 * 1024;
    static final long MAX_BYTES = 2L * 1024L * 1024L * 1024L;
    static final long SNIFF_BOX_MAX = 512L * 1024L * 1024L;
    private static final int MAX_REDIRECTS = 5;

    private static final int DRAIN_BYTES = 64 * 1024;
    private static final long DRAIN_NANOS = 3_000_000_000L;

    private static final int HEAD = 64;

    private static final String AVIF = "image/avif";
    private static final String HEIC = "image/heic";
    private static final String HEIF = "image/heif";

    static Result fetch(String url, Kind kind, File into, MediaUrlPolicy policy, long maxBytes, Progress progress) {
        HttpURLConnection connection = null;
        boolean kept = false;

        try {
            Object opened = connect(url, policy, progress);
            if (opened instanceof Result) return (Result) opened;
            connection = (HttpURLConnection) opened;
            if (progress.cancelled()) return cancelled();

            int code = connection.getResponseCode();
            if (code == 401 || code == 403 || code == 410) {
                drain(connection, progress);
                if (progress.cancelled()) return cancelled();
                return Result.fail(Status.EXPIRED, "the server answered " + code);
            }
            if (code != 200) {
                drain(connection, progress);
                if (progress.cancelled()) return cancelled();
                return Result.fail(Status.HTTP_ERROR, "the server answered " + code);
            }

            String declared = mimeOf(connection.getContentType());
            if (!declaresKind(declared, kind)) {
                return Result.fail(Status.REFUSED, "the server sent " + (declared == null ? "no type" : declared)
                    + ", not " + kind.name().toLowerCase(Locale.US));
            }

            long expected = connection.getContentLengthLong();
            if (expected > maxBytes) {
                return Result.fail(Status.TOO_LARGE, "the server announced " + expected + " bytes, more than " + maxBytes);
            }

            InputStream in = connection.getInputStream();
            byte[] head = new byte[HEAD];
            int headLength = readHead(in, head);
            if (headLength == 0) return Result.fail(Status.WRITE_ERROR, "the body was empty");

            String sniffed = sniff(kind, head, headLength);
            if (sniffed == null) {
                return Result.fail(Status.REFUSED, "the body does not start like a " + kind.name().toLowerCase(Locale.US) + " file");
            }

            long total;
            try (OutputStream out = new CacheFile(into)) {
                out.write(head, 0, headLength);
                progress.transferred(headLength, expected);
                total = headLength + copy(in, out, maxBytes - headLength, headLength, expected, progress);
            }
            if (total > maxBytes) return Result.fail(Status.TOO_LARGE, "the body ran past " + maxBytes + " bytes");
            if (expected >= 0 && total != expected) {
                return Result.fail(Status.WRITE_ERROR, "the body was " + total + " of " + expected + " bytes");
            }

            kept = true;
            if (kind == Kind.AUDIO && "audio/mp4".equals(sniffed)
                    && ("audio/mp4".equals(declared) || "video/mp4".equals(declared))) return Result.ok(declared);
            if (HEIF.equals(sniffed) && isHeifType(declared)) return Result.ok(declared);
            return Result.ok(sniffed);
        } catch (IOException e) {
            if (progress.cancelled()) return cancelled();
            if (e instanceof StorageFailure) {
                return Result.fail(Status.STORAGE_ERROR, "the cache could not hold the file: "
                    + e.getCause().getClass().getSimpleName());
            }
            return Result.fail(Status.NETWORK_ERROR, "the fetch failed: " + e.getClass().getSimpleName());
        } catch (Throwable t) {
            if (progress.cancelled()) return cancelled();
            return Result.fail(Status.NETWORK_ERROR, "the fetch failed: " + t.getClass().getSimpleName());
        } finally {
            if (!kept) delete(into);
            if (connection != null) {
                try {
                    connection.disconnect();
                } catch (Throwable ignored) {}
            }
        }
    }

    private static Object connect(String url, MediaUrlPolicy policy, Progress progress) throws IOException {
        URL current;
        try {
            current = new URL(url);
        } catch (MalformedURLException e) {
            return Result.fail(Status.REFUSED, "the address is not well formed");
        }
        Set<String> visited = new HashSet<>();

        for (int hop = 0; hop <= MAX_REDIRECTS; hop++) {
            if (progress.cancelled()) return cancelled();
            MediaUrlPolicy.Refusal refusal = policy.refusal(current);
            if (refusal != null) {
                return Result.fail(refusal.network ? Status.NETWORK_ERROR : Status.REFUSED,
                    (hop == 0 ? "the address: " : "redirect " + hop + ": ") + refusal.reason);
            }
            if (!visited.add(current.toExternalForm())) {
                return Result.fail(Status.HTTP_ERROR, "redirect " + hop + " loops back to an address already fetched");
            }

            HttpURLConnection connection = (HttpURLConnection) current.openConnection();
            boolean handedBack = false;
            String location;
            try {
                connection.setRequestMethod("GET");
                connection.setInstanceFollowRedirects(false);
                connection.setConnectTimeout(CONNECT_TIMEOUT_MS);
                connection.setReadTimeout(readTimeoutMs);
                connection.setUseCaches(false);
                progress.reading(connection::disconnect);
                connection.connect();
                if (progress.cancelled()) return cancelled();

                int code = connection.getResponseCode();
                if (code != 301 && code != 302 && code != 303 && code != 307 && code != 308) {
                    handedBack = true;
                    return connection;
                }

                location = connection.getHeaderField("Location");
                drain(connection, progress);
            } finally {
                if (!handedBack) {
                    try {
                        connection.disconnect();
                    } catch (Throwable ignored) {}
                }
            }

            if (location == null || location.isEmpty()) {
                return Result.fail(Status.HTTP_ERROR, "a redirect named no address");
            }
            try {
                current = new URL(current, location);
            } catch (MalformedURLException e) {
                return Result.fail(Status.HTTP_ERROR, "redirect " + (hop + 1) + " is not well formed");
            }
        }

        return Result.fail(Status.HTTP_ERROR, "more than " + MAX_REDIRECTS + " redirects");
    }

    private static boolean declaresKind(String mime, Kind kind) {
        if (mime == null) return false;
        if (!isSpecific(mime)) return true;
        switch (kind) {
            case VIDEO:
                return mime.startsWith("video/");
            case AUDIO:
                return mime.startsWith("audio/") || mime.equals("video/mp4") || mime.equals("video/webm");
            default:
                return mime.startsWith("image/");
        }
    }

    private static boolean isSpecific(String mime) {
        return mime != null && !mime.equals("application/octet-stream") && !mime.equals("binary/octet-stream");
    }

    private static boolean isHeifType(String mime) {
        return AVIF.equals(mime) || HEIC.equals(mime) || HEIF.equals(mime);
    }

    static String sniff(Kind kind, byte[] head, int length) {
        String box = length >= 8 && boxSizeFits(head) ? ascii(head, 4, 4) : null;
        boolean isoMedia = box != null && (box.equals("ftyp") || box.equals("styp") || box.equals("moov")
            || box.equals("mdat") || box.equals("free") || box.equals("skip") || box.equals("wide")
            || box.equals("sidx") || box.equals("moof"));
        String brand = isoMedia && box.equals("ftyp") && length >= 12 ? ascii(head, 8, 4) : "";
        boolean imageBrand = isAvifBrand(brand) || isHeicBrand(brand)
            || brand.equals("mif1") || brand.equals("msf1");

        switch (kind) {
            case VIDEO:
                if (isoMedia && !imageBrand) {
                    if (brand.equals("qt  ")) return "video/quicktime";
                    if (brand.startsWith("3gp")) return "video/3gpp";
                    return "video/mp4";
                }
                if (starts(head, length, 0x1A, 0x45, 0xDF, 0xA3)) return "video/webm";
                return null;
            case AUDIO:
                if (isoMedia && !imageBrand) return "audio/mp4";
                if (starts(head, length, 0x1A, 0x45, 0xDF, 0xA3)) return "audio/webm";
                return mp3Header(head, length) ? "audio/mpeg" : null;
            default:
                if (starts(head, length, 0xFF, 0xD8, 0xFF)) return "image/jpeg";
                if (starts(head, length, 0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)) return "image/png";
                if (length >= 6 && (ascii(head, 0, 6).equals("GIF87a") || ascii(head, 0, 6).equals("GIF89a"))) return "image/gif";
                if (length >= 12 && ascii(head, 0, 4).equals("RIFF") && ascii(head, 8, 4).equals("WEBP")) return "image/webp";
                if (imageBrand) return imageType(brand, head, length);
                return null;
        }
    }

    private static boolean mp3Header(byte[] head, int length) {
        if (length >= 10 && starts(head, length, 'I', 'D', '3')) {
            int version = head[3] & 255;
            if (version < 2 || version > 4 || (head[4] & 255) == 255) return false;
            int allowedFlags = version == 2 ? 0xC0 : version == 3 ? 0xE0 : 0xF0;
            if (((head[5] & 255) & ~allowedFlags) != 0) return false;
            long size = 0;
            for (int i = 6; i < 10; i++) {
                if ((head[i] & 0x80) != 0) return false;
                size = (size << 7) | head[i];
            }
            return size <= 16L * 1024 * 1024;
        }
        if (length < 4 || (head[0] & 255) != 255 || (head[1] & 0xE0) != 0xE0) return false;
        int version = (head[1] >>> 3) & 3, layer = (head[1] >>> 1) & 3;
        int bitrate = (head[2] >>> 4) & 15, rate = (head[2] >>> 2) & 3;
        return version != 1 && layer == 1 && bitrate > 0 && bitrate < 15 && rate != 3
                && (head[3] & 3) != 2;
    }

    private static String imageType(String major, byte[] head, int length) {
        if (isAvifBrand(major)) return AVIF;
        if (isHeicBrand(major)) return HEIC;
        long size = boxSize(head);
        long end = size == 0 ? length : Math.min(size, length);
        for (int at = 16; at + 4 <= end; at += 4) {
            String compatible = ascii(head, at, 4);
            if (isAvifBrand(compatible)) return AVIF;
            if (isHeicBrand(compatible)) return HEIC;
        }
        return HEIF;
    }

    private static boolean isAvifBrand(String brand) {
        return brand.equals("avif") || brand.equals("avis");
    }

    private static boolean isHeicBrand(String brand) {
        return brand.equals("heic") || brand.equals("heix") || brand.equals("hevc") || brand.equals("hevx");
    }

    private static boolean boxSizeFits(byte[] head) {
        long size = boxSize(head);
        return size == 0 || size == 1 || (size >= 8 && size <= SNIFF_BOX_MAX);
    }

    private static long boxSize(byte[] head) {
        return ((head[0] & 0xFFL) << 24) | ((head[1] & 0xFFL) << 16) | ((head[2] & 0xFFL) << 8)
            | (head[3] & 0xFFL);
    }

    private static boolean starts(byte[] head, int length, int... signature) {
        if (length < signature.length) return false;
        for (int i = 0; i < signature.length; i++) {
            if ((head[i] & 0xFF) != signature[i]) return false;
        }
        return true;
    }

    private static String ascii(byte[] bytes, int from, int count) {
        char[] chars = new char[count];
        for (int i = 0; i < count; i++) chars[i] = (char) (bytes[from + i] & 0xFF);
        return new String(chars);
    }

    private static int readHead(InputStream in, byte[] head) throws IOException {
        int filled = 0;
        while (filled < head.length) {
            int read = in.read(head, filled, head.length - filled);
            if (read < 0) break;
            filled += read;
        }
        return filled;
    }

    private static long copy(InputStream in, OutputStream out, long allowed, long already, long expected,
            Progress progress) throws IOException {
        byte[] buffer = new byte[BUFFER];
        long total = 0;

        int read;
        while ((read = in.read(buffer)) > 0) {
            out.write(buffer, 0, read);
            total += read;
            if (total > allowed) break;
            progress.transferred(already + total, expected);
            if (progress.cancelled()) throw new Cancelled();
        }

        out.flush();
        return total;
    }

    private static Result cancelled() {
        return Result.fail(Status.CANCELLED, "the person saving cancelled it");
    }

    private static String mimeOf(String contentType) {
        if (contentType == null) return null;

        String mime = contentType;
        int cut = mime.indexOf(';');
        if (cut >= 0) mime = mime.substring(0, cut);

        mime = mime.trim().toLowerCase(Locale.US);
        return mime.length() <= 100 && mime.matches("[a-z0-9.+-]+/[a-z0-9.+-]+") ? mime : null;
    }

    private static void drain(HttpURLConnection connection, Progress progress) {
        InputStream stream = null;
        try {
            stream = connection.getErrorStream();
            if (stream == null) return;

            byte[] buffer = new byte[4096];
            long until = System.nanoTime() + DRAIN_NANOS;
            int left = DRAIN_BYTES;
            int read;
            while (left > 0 && System.nanoTime() - until < 0 && !progress.cancelled()
                    && (read = stream.read(buffer, 0, Math.min(buffer.length, left))) > 0) {
                left -= read;
            }
        } catch (Throwable ignored) {} finally {
            if (stream != null) {
                try {
                    stream.close();
                } catch (Throwable ignored) {}
            }
        }
    }

    static void delete(File file) {
        if (file == null) return;
        try {
            //noinspection ResultOfMethodCallIgnored
            file.delete();
        } catch (Throwable ignored) {}
    }
}
