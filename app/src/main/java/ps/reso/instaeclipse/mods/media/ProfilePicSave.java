package ps.reso.instaeclipse.mods.media;

final class ProfilePicSave {
    enum Action { COMPANION, PUBLISH, NOTHING }

    private ProfilePicSave() { }

    static Action decide(boolean companionFolderSet, boolean tempFileReady) {
        if (companionFolderSet) return Action.COMPANION;
        return tempFileReady ? Action.PUBLISH : Action.NOTHING;
    }
}
