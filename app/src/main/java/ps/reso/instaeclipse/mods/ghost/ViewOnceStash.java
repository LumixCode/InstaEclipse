package ps.reso.instaeclipse.mods.ghost;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

final class ViewOnceStash {
    static final int CAPACITY = 500;

    private final Map<Long, String> byMedia;

    ViewOnceStash(int capacity) {
        byMedia = new LinkedHashMap<>(16, 0.75f, true) {
            @Override protected boolean removeEldestEntry(Map.Entry<Long, String> eldest) {
                return size() > capacity;
            }
        };
    }

    static String original(String viewMode) {
        if ("once".equals(viewMode)) return "once";
        if ("replayable".equals(viewMode) || "allow_replay".equals(viewMode)) return "replayable";
        return null;
    }

    static Long mediaKey(String id) {
        if (id == null) return null;
        int split = id.indexOf('_');
        String pk = split < 0 ? id : id.substring(0, split);
        if (split >= 0 && !digits(id.substring(split + 1), 1)) return null;
        if (pk.length() > 22 || !digits(pk, 8)) return null;
        try {
            return Long.parseLong(pk);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static boolean digits(String text, int minLength) {
        return text.length() >= minLength && text.chars().allMatch(Character::isDigit);
    }

    synchronized void remember(String mediaId, String original) {
        Long key = mediaKey(mediaId);
        if (key != null && original != null) byMedia.put(key, original);
    }

    synchronized String find(List<Long> candidates) {
        for (Long key : candidates) {
            String hit = byMedia.get(key);
            if (hit != null) return hit;
        }
        return null;
    }

    synchronized int size() {
        return byMedia.size();
    }
}
