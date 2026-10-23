package ps.reso.instaeclipse.mods.extras;

final class HighResPick {

    static final int MAX_SIDE = 65536;

    private HighResPick() {}

    static int choose(int[] w, int[] h, int igIndex, int target) {
        int best = -1;
        long area = 0;
        for (int i = 0; i < w.length; i++) {
            if (!valid(w[i], h[i])) continue;
            long a = (long) w[i] * h[i];
            if (a > area) {
                area = a;
                best = i;
            }
        }
        return best;
    }

    private static boolean valid(int w, int h) {
        return w > 0 && h > 0 && w <= MAX_SIDE && h <= MAX_SIDE;
    }
}
