package ps.reso.instaeclipse.utils.download;

import android.content.Context;

import java.net.ConnectException;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import java.util.concurrent.CancellationException;

import ps.reso.instaeclipse.R;
import ps.reso.instaeclipse.mods.media.HttpStatusException;
import ps.reso.instaeclipse.utils.i18n.I18n;
import ps.reso.instaeclipse.utils.ipc.CompanionBridge;

public final class DownloadMessages {
    private DownloadMessages() { }

    public static int reason(Throwable error) {
        Throwable t = error;
        for (int depth = 0; t != null && depth < 8; depth++, t = t.getCause()) {
            if (t instanceof DownloadEngine.Failure failure) return status(failure.status);
            if (t instanceof HttpStatusException http) return http(http.code);
            if (t instanceof UnknownHostException || t instanceof ConnectException
                    || t instanceof SocketTimeoutException) return R.string.ie_download_reason_offline;
            if (t instanceof CancellationException || t instanceof InterruptedException) return R.string.ie_download_cancelled;
            if (noSpace(t.getMessage())) return R.string.ie_download_reason_storage;
        }
        return R.string.ie_download_reason_generic;
    }

    static int status(String status) {
        if (status == null) return R.string.ie_download_reason_generic;
        return switch (status) {
            case "EXPIRED" -> R.string.ie_download_reason_expired;
            case "NETWORK_ERROR", "WRITE_ERROR" -> R.string.ie_download_reason_offline;
            case "HTTP_ERROR" -> R.string.ie_download_reason_server;
            case "STORAGE" -> R.string.ie_download_reason_storage;
            case "TOO_LARGE" -> R.string.ie_download_reason_too_large;
            case "NO_MEDIA" -> R.string.ie_download_reason_no_media;
            case "CEILING" -> R.string.ie_download_reason_ceiling;
            case "VERIFY", "JOIN", "UNSUPPORTED", "MANIFEST" -> R.string.ie_download_reason_unplayable;
            case "REFUSED" -> R.string.ie_download_reason_blocked;
            case "CANCELLED" -> R.string.ie_download_cancelled;
            default -> R.string.ie_download_reason_generic;
        };
    }

    static int http(int code) {
        if (code == 401 || code == 403 || code == 410) return R.string.ie_download_reason_expired;
        return code >= 400 ? R.string.ie_download_reason_server : R.string.ie_download_reason_generic;
    }

    private static boolean noSpace(String message) {
        return message != null && (message.contains("ENOSPC") || message.contains("No space left on device"));
    }

    public static String quality(DownloadEngine.Metadata metadata) {
        if (metadata == null) return "";
        int lines = Math.min(metadata.width, metadata.height);
        String codec = codec(metadata.videoCodec);
        String size = lines > 0 ? lines + "p" : "";
        if (size.isEmpty()) return codec;
        return codec.isEmpty() ? size : size + " · " + codec;
    }

    static String codec(String mime) {
        if (mime == null) return "";
        return switch (mime) {
            case "video/avc" -> "H.264";
            case "video/hevc" -> "HEVC";
            case "video/x-vnd.on2.vp9" -> "VP9";
            case "video/av01" -> "AV1";
            default -> "";
        };
    }

    public static int saved(boolean lowerFallback) {
        return lowerFallback ? R.string.ie_download_saved_lower : R.string.ie_download_saved_quality;
    }

    public static int frameReason(String code) {
        if (code == null) return 0;
        return switch (code) {
            case "MEMORY" -> R.string.ie_frame_reason_memory;
            case "WRITE", "DESTINATION" -> R.string.ie_frame_reason_write;
            default -> 0;
        };
    }

    public static String failure(Context ctx, Throwable error) {
        return failure(ctx, reason(error));
    }

    public static String failure(Context ctx, int reason) {
        if (reason == R.string.ie_download_cancelled) return I18n.t(ctx, reason);
        return I18n.t(ctx, R.string.ig_toast_download_failed, I18n.t(ctx, reason));
    }

    public static String companionFailure(Context ctx, String code) {
        String text = CompanionBridge.message(ctx, code);
        if (text == null || text.equals(code)) text = I18n.t(ctx, R.string.ie_download_reason_generic);
        return I18n.t(ctx, R.string.ig_toast_download_failed, text);
    }
}
