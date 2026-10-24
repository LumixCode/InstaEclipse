package ps.reso.instaeclipse.mods.ghost;

final class InboxReplayGate {
    private long applied = -1;

    static long revision(long hiddenSet, boolean enabled, boolean hiddenOnly) {
        return hiddenSet << 2 | (enabled ? 1 : 0) | (hiddenOnly ? 2 : 0);
    }

    synchronized boolean stale(long revision) {
        return revision != applied;
    }

    synchronized void applied(long revision) {
        applied = revision;
    }
}
