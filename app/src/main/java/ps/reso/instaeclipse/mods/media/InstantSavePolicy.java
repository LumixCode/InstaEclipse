package ps.reso.instaeclipse.mods.media;

final class InstantSavePolicy {
    enum Decision { SAVE, IGNORE, ALREADY_SAVED }

    static final long DEBOUNCE_MS = 1000;

    private long lastSave;

    synchronized Decision hold(String id, long now) {
        return now - lastSave > DEBOUNCE_MS ? Decision.SAVE : Decision.IGNORE;
    }

    synchronized void saved(String id, long now) {
        lastSave = now;
    }
}
