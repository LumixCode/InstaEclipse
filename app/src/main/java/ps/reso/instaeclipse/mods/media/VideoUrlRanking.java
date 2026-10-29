package ps.reso.instaeclipse.mods.media;

import java.util.List;
import java.util.Map;

final class VideoUrlRanking {

    static final class Pick {
        final String url;
        final int height;

        Pick(String url, int height) {
            this.url = url;
            this.height = height;
        }
    }

    private VideoUrlRanking() {}

    static boolean isCombinedStream(String url) {
        return url.contains("/m86/") || url.contains("%2Fm86%2F");
    }

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
            return new Pick(urls.get(0), bestH);
        }
        return new Pick(best, bestH);
    }
}
