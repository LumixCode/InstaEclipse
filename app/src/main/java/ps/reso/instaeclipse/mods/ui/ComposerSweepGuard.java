package ps.reso.instaeclipse.mods.ui;

final class ComposerSweepGuard {

    private ComposerSweepGuard() {}

    static boolean sweeps(int composerId, int sheetId) {
        return composerId != 0;
    }
}
