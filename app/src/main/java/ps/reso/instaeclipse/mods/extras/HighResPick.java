package ps.reso.instaeclipse.mods.extras;

final class HighResPick {

    static final int MAX_SIDE = 65536;

    private HighResPick() {}

    static int choose(int[] w, int[] h, int igIndex, int target) {
        if (igIndex < 0 || igIndex >= w.length || !valid(w[igIndex], h[igIndex])) return -1;
        boolean square = w[igIndex] == h[igIndex];
        long sharp = 2L * target;
        int smallest = -1, largest = -1;
        long smallestArea = Long.MAX_VALUE, largestArea = 0;
        for (int i = 0; i < w.length; i++) {
            if (!valid(w[i], h[i]) || (w[i] == h[i]) != square) continue;
            long a = (long) w[i] * h[i];
            if (a > largestArea) {
                largestArea = a;
                largest = i;
            }
            if (w[i] >= sharp && a < smallestArea) {
                smallestArea = a;
                smallest = i;
            }
        }
        return smallest >= 0 ? smallest : largest;
    }

    private static boolean valid(int w, int h) {
        return w > 0 && h > 0 && w <= MAX_SIDE && h <= MAX_SIDE;
    }
}
