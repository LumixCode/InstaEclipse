package ps.reso.instaeclipse.mods.ghost;

final class HeaderTapZone {
    private HeaderTapZone() {}

    static int[] span(int nameLeft, int nameRight, int barLeft, int barRight,
                      boolean rtl, int gap, int minWidth) {
        int from = rtl ? barLeft : nameRight + gap;
        int to = rtl ? nameLeft - gap : barRight;
        return to - from >= minWidth ? new int[]{from, to} : null;
    }

    static boolean boundsShowName(boolean textOrImage, int width, int boxWidth) {
        return textOrImage || width < boxWidth;
    }
}
