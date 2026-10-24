package ps.reso.instaeclipse.mods.ui;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

final class FollowsYouCache {

    private final Map<String, Boolean> results = new ConcurrentHashMap<>();

    void put(String userId, boolean followedBy) {
        results.put(userId, followedBy);
    }

    Boolean find(Set<String> userValues) {
        for (Map.Entry<String, Boolean> e : results.entrySet())
            if (userValues.contains(e.getKey())) return e.getValue();
        return null;
    }

    int size() {
        return results.size();
    }
}
