package ps.reso.instaeclipse.mods.media;

import java.util.List;
import java.util.Map;

/**
 * Picks the progressive file to download out of one media item's own video_versions URLs. The
 * tallest version wins (Instagram's list order and its m86 combined stream aren't always the best
 * quality); m86 wins a tie, then the first found. When no version has a known height it falls back
 * to the first m86 URL, then the first URL. Framework-free so it is unit-tested; the DASH upgrade
 * and its log line stay in FeedVideoDownloadHook.pickVideoUrl.
 */
final class VideoUrlRanking {

    /** The chosen URL and its height; the height is 0 or below when no version had one. */
    static final class Pick {
        final String url;
        final int height;

        Pick(String url, int height) {
            this.url = url;
            this.height = height;
        }
    }

    private VideoUrlRanking() {}

    /** Instagram's m86 rendition: one file with the video and the audio. */
    static boolean isCombinedStream(String url) {
        return url.contains("/m86/") || url.contains("%2Fm86%2F");
    }

    /**
     * @param urls    the item's version URLs in the order Instagram lists them
     * @param heights URL → height in pixels; a missing entry means "unknown"
     * @return null when {@code urls} is null or empty
     */
    static Pick pick(List<String> urls, Map<String, Integer> heights) {
        if (urls == null || urls.isEmpty()) return null;
        String best = null;
        int bestH = -1;
        for (String u : urls) {
            Integer h = heights != null ? heights.get(u) : null;
            int hh = h != null ? h : -1;
            if (best == null || hh > bestH
                    || (hh == bestH && isCombinedStream(u) && !isCombinedStream(best))) {
                best = u;
                bestH = hh;
            }
        }
        if (bestH <= 0) {
            for (String u : urls) { if (isCombinedStream(u)) return new Pick(u, bestH); }
            return new Pick(urls.get(0), bestH); // fallback: first found
        }
        return new Pick(best, bestH);
    }
}
