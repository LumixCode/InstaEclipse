package ps.reso.instaeclipse.utils.dialog;

final class SaveBatcher {

    private final Runnable save;
    private int depth;
    private boolean pending;

    SaveBatcher(Runnable save) {
        this.save = save;
    }

    void request() {
        if (depth > 0) {
            pending = true;
            return;
        }
        save.run();
    }

    void batch(Runnable changes) {
        depth++;
        try {
            changes.run();
        } finally {
            depth--;
            if (depth == 0 && pending) {
                pending = false;
                save.run();
            }
        }
    }
}
