package ps.reso.instaeclipse.mods.misc;

import java.lang.ref.WeakReference;

final class ConfirmWindow {

    static final long WINDOW_MS = 3000;

    private final WeakReference<Object> media;
    private final long deadline;

    ConfirmWindow(WeakReference<Object> media, long deadline) {
        this.media = media;
        this.deadline = deadline;
    }

    static ConfirmWindow forMedia(Object media, long now) {
        return new ConfirmWindow(new WeakReference<>(media), now + WINDOW_MS);
    }

    static ConfirmWindow forNextLike(long now) {
        return new ConfirmWindow(null, now + WINDOW_MS);
    }

    boolean covers(Object candidate, long now) {
        if (candidate == null || now > deadline) return false;
        return media == null || media.get() == candidate;
    }

    ConfirmWindow boundTo(Object candidate) {
        return media != null ? this : new ConfirmWindow(new WeakReference<>(candidate), deadline);
    }
}
