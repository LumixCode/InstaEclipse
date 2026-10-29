package ps.reso.instaeclipse.mods.misc;

final class StoryLikeUndoPolicy {
    private StoryLikeUndoPolicy() {}

    enum Action {
        PASS,
        HOLD,
        TAKE_BACK,
        SUPERSEDE
    }

    static Action decide(boolean undoOn, boolean like, boolean superlike, boolean held) {
        if (!like) return held ? Action.TAKE_BACK : Action.PASS;
        if (undoOn && !superlike) return Action.HOLD;
        return held ? Action.SUPERSEDE : Action.PASS;
    }
}
