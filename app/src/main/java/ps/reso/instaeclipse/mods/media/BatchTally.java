package ps.reso.instaeclipse.mods.media;

final class BatchTally {
    private int total, saved, failed, delegated;
    private boolean announced;

    BatchTally(int total) {
        this.total = total;
    }

    synchronized void add(int more) {
        total += more;
    }

    synchronized boolean saved() {
        saved++;
        return finished();
    }

    synchronized boolean failed() {
        failed++;
        return finished();
    }

    synchronized boolean delegated() {
        delegated++;
        return finished();
    }

    synchronized int savedCount() {
        return saved;
    }

    synchronized int total() {
        return total;
    }

    private boolean finished() {
        if (announced || delegated > 0 || saved + failed + delegated < total) return false;
        announced = true;
        return true;
    }
}
