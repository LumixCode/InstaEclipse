package ps.reso.instaeclipse.mods.misc;

import java.util.IdentityHashMap;
import java.util.Map;

/**
 * The likes {@link LikeUndoHook} holds for their grace period, by the Media they like. Instagram
 * keeps one Media object per post, so identity is the key. Called from the hook's thread and the
 * main thread.
 */
final class PendingLikes<T> {
    private final Map<Object, T> held = new IdentityHashMap<>();

    /** Holds {@code like} for {@code media}; it replaces a like still held for it. */
    synchronized void hold(Object media, T like) {
        held.put(media, like);
    }

    /** Whether {@code like} is still the one held for {@code media}. */
    synchronized boolean isHeld(Object media, T like) {
        return held.get(media) == like;
    }

    /** Drops the like held for {@code media} and returns it; null when none is (it went out or never waited). */
    synchronized T cancel(Object media) {
        return held.remove(media);
    }

    /** Ends {@code like}'s wait so it can be sent; false when it was cancelled or replaced. */
    synchronized boolean release(Object media, T like) {
        if (held.get(media) != like) return false;
        held.remove(media);
        return true;
    }

    synchronized boolean isEmpty() {
        return held.isEmpty();
    }
}
