package ps.reso.instaeclipse.mods.ghost;

import java.util.ArrayDeque;
import java.util.IdentityHashMap;
import java.util.Map;

final class ViewOnceStash {
    static final int CAPACITY = 500;

    private final int capacity;
    private final Map<String, String> originals = new IdentityHashMap<>();
    private final ArrayDeque<String> order = new ArrayDeque<>();

    ViewOnceStash(int capacity) {
        this.capacity = capacity;
    }

    static String original(String viewMode) {
        if ("once".equals(viewMode)) return "once";
        if ("replayable".equals(viewMode) || "allow_replay".equals(viewMode)) return "replayable";
        return null;
    }

    synchronized String mark(String viewMode, String original) {
        String marker = new String(viewMode);
        originals.put(marker, original);
        order.addLast(marker);
        if (order.size() > capacity) originals.remove(order.pollFirst());
        return marker;
    }

    synchronized String originalOf(Object viewMode) {
        return originals.get(viewMode);
    }

    synchronized int size() {
        return originals.size();
    }
}
