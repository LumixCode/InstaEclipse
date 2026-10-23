package ps.reso.instaeclipse.mods.media;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

final class DownloadNames {

    private DownloadNames() {}

    static String withSlide(String filename, int slide, int total) {
        if (total <= 1 || slide < 1) return filename;
        int width = String.valueOf(total).length();
        String tag = String.format(Locale.US, "_%0" + width + "dof%d", slide, total);
        int dot = filename.lastIndexOf('.');
        return dot > 0 ? filename.substring(0, dot) + tag + filename.substring(dot) : filename + tag;
    }

    static String buildFilename(String username, String type, String mediaId, boolean isVideo,
                                boolean addTimestamp, long nowMs) {
        String u  = (username != null && !username.isEmpty()) ? username : "unknown";
        String id = (mediaId  != null && !mediaId.isEmpty())  ? mediaId  : String.valueOf(nowMs);
        String ext = isVideo ? ".mp4" : ".jpg";
        StringBuilder sb = new StringBuilder(u).append('_').append(type).append('_').append(id);
        if (addTimestamp) {
            sb.append('_').append(new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(new Date(nowMs)));
        }
        return sb.append(ext).toString();
    }
}
