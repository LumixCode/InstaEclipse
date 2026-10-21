package ps.reso.instaeclipse.mods.misc;

/** Which heart a held like turns blue ({@link LikeHeartTint}) and how; Android-free for JVM tests. */
final class LikeHeartPolicy {
    /** Instagram's like heart. */
    static final int LIKE_RED = 0xFFFF3040;
    /** Instagram's own blue, for a like that is not sent yet. */
    static final int LIKE_BLUE = 0xFF0095F6;

    private LikeHeartPolicy() {}

    /**
     * The heart a like goes with, out of {@code hearts} (screen rects: left, top, right, bottom),
     * or -1. A tap at (x, y): the heart under it, {@code slop} included, else none (the caller
     * tints what was tapped). A double tap on {@code media}: the first heart below the tap, at most
     * {@code band} under the media, the nearer one sideways breaking a tie; one above the tap only
     * when nothing is below (the reels heart sits on the video). Without a touch: the only heart.
     */
    static int pick(int[][] hearts, boolean touched, int x, int y, boolean doubleTap, int[] media,
                    int slop, int band) {
        if (!touched) return hearts.length == 1 ? 0 : -1;
        int best = -1;
        long bestScore = Long.MAX_VALUE;
        for (int i = 0; i < hearts.length; i++) {
            int[] r = hearts[i];
            long cx = (r[0] + r[2]) / 2, cy = (r[1] + r[3]) / 2;
            long score;
            if (!doubleTap) {
                if (x < r[0] - slop || x > r[2] + slop || y < r[1] - slop || y > r[3] + slop) continue;
                score = (cx - x) * (cx - x) + (cy - y) * (cy - y);
            } else {
                if (media != null && (cy < media[1] || cy > media[3] + band)) continue;
                long dy = cy - y;
                score = (dy >= 0 ? dy : -dy * 4) * 10 + Math.abs(cx - x);
            }
            if (score < bestScore) {
                bestScore = score;
                best = i;
            }
        }
        return best;
    }

    /** GestureDetector's shortest gap between the first lift and the second press of a double tap. */
    static final long DOUBLE_TAP_MIN_MS = 40;

    /**
     * Whether two presses make one double tap, measured as GestureDetector and Compose do: from the
     * first lift to the second press (uptime), and between the two presses' raw positions.
     */
    static boolean isDoubleTap(long firstUpTime, float firstX, float firstY, long secondDownTime, float secondX,
                               float secondY, long maxGap, float maxDistance) {
        long gap = secondDownTime - firstUpTime;
        return firstUpTime != 0 && gap >= DOUBLE_TAP_MIN_MS && gap <= maxGap
                && Math.hypot(secondX - firstX, secondY - firstY) <= maxDistance;
    }

    /**
     * A 4x5 color matrix that turns {@code red} into {@code blue} and leaves every grey (white and
     * black included) as it is, for a heart's own view where a shader is unavailable.
     */
    static float[] redToBlueMatrix(int red, int blue) {
        float[] from = channels(red), to = channels(blue);
        float[] m = new float[20];
        for (int c = 0; c < 3; c++) {
            // out = a*r + b*(g + b'): a + 2b = 1 keeps greys, a*R + b*(G + B) = target maps the red.
            float b = (to[c] - from[0]) / (from[1] + from[2] - 2 * from[0]);
            m[c * 5] = 1 - 2 * b;
            m[c * 5 + 1] = b;
            m[c * 5 + 2] = b;
        }
        m[18] = 1;
        return m;
    }

    /** {@code color}'s red, green and blue in 0..1. */
    static float[] channels(int color) {
        return new float[]{((color >> 16) & 0xFF) / 255f, ((color >> 8) & 0xFF) / 255f, (color & 0xFF) / 255f};
    }
}
