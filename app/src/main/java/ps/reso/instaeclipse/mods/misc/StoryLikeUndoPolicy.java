package ps.reso.instaeclipse.mods.misc;

/** What {@link StoryLikeUndoHook} does with a call to Instagram's story like (pure, for tests). */
final class StoryLikeUndoPolicy {
    private StoryLikeUndoPolicy() {}

    enum Action {
        /** Let Instagram like or unlike now. */
        PASS,
        /** Hold the like; it is sent when its wait ends. */
        HOLD,
        /** An unlike while the story's like is held: neither is sent. */
        TAKE_BACK,
        /** A superlike, or a like with the window turned off, while one is held: it goes out instead. */
        SUPERSEDE
    }

    /**
     * @param undoOn    the undo window is on
     * @param like      the call likes the story (else it unlikes it)
     * @param superlike the like is a superlike (a count of hearts): never held
     * @param held      a like of this story is held right now
     */
    static Action decide(boolean undoOn, boolean like, boolean superlike, boolean held) {
        if (!like) return held ? Action.TAKE_BACK : Action.PASS;
        if (undoOn && !superlike) return Action.HOLD;
        return held ? Action.SUPERSEDE : Action.PASS;
    }
}
