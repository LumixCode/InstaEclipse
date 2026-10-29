package ps.reso.instaeclipse.mods.misc;

import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.Map;

final class PendingLikes<T> {
    private final Map<Object, T> held;

    PendingLikes() {
        this(new IdentityHashMap<>());
    }

    private PendingLikes(Map<Object, T> held) {
        this.held = held;
    }

    static <T> PendingLikes<T> byId() {
        return new PendingLikes<>(new HashMap<>());
    }

    synchronized void hold(Object media, T like) {
        held.put(media, like);
    }

    synchronized boolean isHeld(Object media, T like) {
        return held.get(media) == like;
    }

    synchronized boolean contains(Object media) {
        return held.containsKey(media);
    }

    synchronized T cancel(Object media) {
        return held.remove(media);
    }

    synchronized boolean release(Object media, T like) {
        if (held.get(media) != like) return false;
        held.remove(media);
        return true;
    }

    synchronized boolean isEmpty() {
        return held.isEmpty();
    }
}
