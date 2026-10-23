package ps.reso.instaeclipse.mods.ghost;

final class ViewOnceBadgeMode {

    private ViewOnceBadgeMode() {}

    static void rewrite(String stashed, Object[] args, int modeIdx) {
        if (stashed != null) args[modeIdx] = stashed;
    }
}
