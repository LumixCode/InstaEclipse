package ps.reso.instaeclipse.mods.ui;

final class ButtonStyleLatch {

    private ButtonStyleLatch() {}

    static boolean shouldLatch(int matches, int paints) {
        return matches == 1 || paints >= 2;
    }
}
