package ps.reso.instaeclipse.mods.reels;

/** A DM entry exempts one media ID, never the feed reached by swiping onward. */
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
        // Allow the DM viewer to initialize before it supplies its first media ID.
        return direct && (openedDmMediaId == null || openedDmMediaId.equals(currentMediaId));
    }

    boolean shouldLockScrolling(boolean restrictionActive, boolean allowDM) {
        // Normal feeds must reach the explanatory page block instead of silently freezing.
        return restrictionActive && allowDM && isDmReel();
    }
}
