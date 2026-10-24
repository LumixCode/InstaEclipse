package ps.reso.instaeclipse.mods.ghost;

final class SeenAffordance {

    enum Action { NONE, APPLY, REMOVE }

    private SeenAffordance() {}

    static Action onBind(boolean show, boolean applied) {
        return show && !applied ? Action.APPLY : Action.NONE;
    }

    static int composerIndex(int childCount) {
        return 3;
    }
}
