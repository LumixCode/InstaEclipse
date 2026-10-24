package ps.reso.instaeclipse.mods.media;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

final class PostMenuRowsPolicy {
    enum Row { DOWNLOAD, COPY_MEDIA_LINK, COPY_IMAGE, MEDIA_INFO, SPEED, COUNTS }

    static final String DOWNLOAD = "DOWNLOAD";
    static final String COPY_MEDIA_LINK_CARRIER = "DEBUG_MEDIA_ON_DEVICE_CBR";
    static final String COPY_IMAGE_CARRIER = "EXPLORE_INTERNAL_DEBUG_LOGS";
    static final String MEDIA_INFO_CARRIER = "DEBUG_SHOPS_AD";
    static final String SPEED_CARRIER = "PROMOTE_DEBUG";
    static final String COUNTS_CARRIER = "DEBUG_STICKER_TRANSLATION";

    private final Set<Row> injected = EnumSet.noneOf(Row.class);
    private final Set<Row> instagrams = EnumSet.noneOf(Row.class);
    private boolean processed;

    static Row row(String option) {
        if (option == null) return null;
        return switch (option) {
            case DOWNLOAD -> Row.DOWNLOAD;
            case COPY_MEDIA_LINK_CARRIER -> Row.COPY_MEDIA_LINK;
            case COPY_IMAGE_CARRIER -> Row.COPY_IMAGE;
            case MEDIA_INFO_CARRIER -> Row.MEDIA_INFO;
            case SPEED_CARRIER -> Row.SPEED;
            case COUNTS_CARRIER -> Row.COUNTS;
            default -> null;
        };
    }

    static String option(Row row) {
        return switch (row) {
            case DOWNLOAD -> DOWNLOAD;
            case COPY_MEDIA_LINK -> COPY_MEDIA_LINK_CARRIER;
            case COPY_IMAGE -> COPY_IMAGE_CARRIER;
            case MEDIA_INFO -> MEDIA_INFO_CARRIER;
            case SPEED -> SPEED_CARRIER;
            case COUNTS -> COUNTS_CARRIER;
        };
    }

    boolean suppressNative(Row row, Set<Row> enabled) {
        if (row == null) return false;
        if (enabled.contains(row) && injected.contains(row)) return true;
        instagrams.add(row);
        return false;
    }

    List<Row> rowsToInject(Row added, Set<Row> wanted) {
        if (wanted.isEmpty() || added != null || processed) return List.of();
        processed = true;
        List<Row> rows = new ArrayList<>();
        for (Row row : Row.values()) if (wanted.contains(row) && !instagrams.contains(row)) rows.add(row);
        return rows;
    }

    void injected(Row row, boolean ok) {
        if (ok) injected.add(row);
    }

    static boolean handlesTaps(Set<Row> enabled, boolean reelDownload) {
        return reelDownload || !enabled.isEmpty();
    }

    static String downloadType(boolean reelMenu) {
        return reelMenu ? "reel" : "post";
    }
}
