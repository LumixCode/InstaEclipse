package ps.reso.instaeclipse.mods.ui;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

final class FollowsYouCache {

    private static final int CAP = 200;

    private final Map<String, Boolean> results = new LinkedHashMap<>(16, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, Boolean> eldest) {
            return size() > CAP;
        }
    };

    synchronized void put(String userId, boolean followedBy) {
        results.put(userId, followedBy);
    }

    synchronized Boolean find(Set<String> userValues) {
        if (results.isEmpty()) return null;
        for (String value : userValues) {
            Boolean followedBy = results.get(value);
            if (followedBy != null) return followedBy;
        }
        return null;
    }

    synchronized int size() {
        return results.size();
    }
}
