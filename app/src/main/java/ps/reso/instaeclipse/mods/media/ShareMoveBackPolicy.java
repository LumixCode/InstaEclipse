package ps.reso.instaeclipse.mods.media;

final class ShareMoveBackPolicy {
    static final long[] CHECKS_MS = {1500, 3500, 6000};
    static final long BOUNCE_MS = 1000;

    private boolean moved;
    private long movedAt;
    private boolean bounced;
    private boolean userBack;

    synchronized boolean shouldMove(int check, long now) {
        if (check == 0) return true;
        return moved && bounced && !userBack;
    }

    synchronized void moved(long now) {
        moved = true;
        movedAt = now;
        bounced = false;
    }

    synchronized void resumed(long now) {
        if (!moved) return;
        if (now - movedAt < BOUNCE_MS) bounced = true;
        else userBack = true;
    }
}
