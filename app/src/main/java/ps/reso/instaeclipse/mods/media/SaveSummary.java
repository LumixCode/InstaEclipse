package ps.reso.instaeclipse.mods.media;

final class SaveSummary {
    interface Texts {
        String saved(int count);

        String savedAndFailed(int saved, int failed);

        String cancelled();
    }

    private int saved, failed, cancelled;
    private String single;

    synchronized void saved(String text) {
        saved++;
        single = text;
    }

    synchronized void failed(String text) {
        failed++;
        single = text;
    }

    synchronized void cancelled() {
        cancelled++;
    }

    synchronized String text(Texts texts) {
        int total = saved + failed;
        if (total == 0) return cancelled > 0 ? texts.cancelled() : null;
        if (total == 1) return single;
        return failed == 0 ? texts.saved(saved) : texts.savedAndFailed(saved, failed);
    }

    synchronized String drain(Texts texts) {
        String text = text(texts);
        saved = 0;
        failed = 0;
        cancelled = 0;
        single = null;
        return text;
    }
}
