package ps.reso.instaeclipse.mods.ghost;

/**
 * The free stretch of a DM thread header between the end of the chat name and the call buttons,
 * in screen x pixels. Pure so the RTL and no-room cases are unit-tested.
 */
final class HeaderTapZone {
    private HeaderTapZone() {}

    /**
     * @param nameLeft  left edge of what the name (title, subtitle) draws
     * @param nameRight right edge of what the name draws
     * @param barLeft   left edge of the header space the end buttons leave free
     * @param barRight  right edge of that space
     * @param gap       kept clear after the name, so a tap just past it still opens the profile
     * @return {from, to} with {@code to} exclusive, or null when less than {@code minWidth} is free
     */
    static int[] span(int nameLeft, int nameRight, int barLeft, int barRight,
                      boolean rtl, int gap, int minWidth) {
        int from = rtl ? barLeft : nameRight + gap;
        int to = rtl ? nameLeft - gap : barRight;
        return to - from >= minWidth ? new int[]{from, to} : null;
    }

    /**
     * Whether a leaf view's bounds mark where the name is drawn. A plain View stretched across the
     * whole name box (446/451 header_subtitle and header_aigm_label) draws its text from one edge
     * through a background Drawable, so its bounds would claim the free space after the name.
     * Text and image views are measured as they are (a TextView by its text).
     */
    static boolean boundsShowName(boolean textOrImage, int width, int boxWidth) {
        return textOrImage || width < boxWidth;
    }
}
