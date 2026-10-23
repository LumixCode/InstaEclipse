package ps.reso.instaeclipse.mods.ghost;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

final class ViewOnceStash {
    static final int CAPACITY = 500;

    private final Map<Long, String> byKey = new ConcurrentHashMap<>();

    ViewOnceStash(int capacity) {
    }

    static String original(String viewMode) {
        if ("once".equals(viewMode)) return "once";
        if ("replayable".equals(viewMode) || "allow_replay".equals(viewMode)) return "replayable";
        return null;
    }

    static Long mediaKey(String id) {
        if (id == null || id.length() < 8 || id.length() > 22 || !id.chars().allMatch(Character::isDigit)) return null;
        try {
            return Long.parseLong(id);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    void remember(String mediaId, String original) {
        Long key = mediaKey(mediaId);
        if (key != null && original != null) byKey.put(key, original);
    }

    void rememberAll(List<Long> keys, String original) {
        if (original == null) return;
        for (Long key : keys) byKey.put(key, original);
    }

    String find(List<Long> candidates) {
        for (Long key : candidates) {
            String hit = byKey.get(key);
            if (hit != null) return hit;
        }
        return null;
    }

    int size() {
        return byKey.size();
    }
}
