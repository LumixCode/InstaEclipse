package ps.reso.instaeclipse.mods.reels;

final class ReelViewerSession {
    private final boolean direct;
    private String openedDmMediaId;
    String currentMediaId;

    ReelViewerSession(boolean direct) {
        this.direct = direct;
    }

    void select(String mediaId) {
        if (mediaId == null || mediaId.isEmpty()) return;
        currentMediaId = mediaId;
        if (direct && openedDmMediaId == null) openedDmMediaId = mediaId;
    }

    boolean isDmReel() {
        return direct && (openedDmMediaId == null || openedDmMediaId.equals(currentMediaId));
    }

    boolean shouldLockScrolling(boolean restrictionActive, boolean allowDM) {
        return restrictionActive && allowDM && isDmReel();
    }
}
