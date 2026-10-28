package ps.reso.instaeclipse.mods.media;

final class ShareMoveBackPolicy {
    static final long[] CHECKS_MS = {1500, 3500, 6000};

    synchronized boolean shouldMove(int check, long now) {
        return true;
    }

    synchronized void moved(long now) {
    }

    synchronized void resumed(long now) {
    }
}
