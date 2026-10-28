package ps.reso.instaeclipse.mods.media;

import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

final class InstantSavePolicy {
    enum Decision { SAVE, IGNORE, ALREADY_SAVED }

    static final long DEBOUNCE_MS = 1000;
    static final int MAX_SAVED = 200;

    private static final String UNKNOWN = "";

    private final Map<String, Long> lastHold = new LinkedHashMap<String, Long>(16, 0.75f, true) {
        @Override protected boolean removeEldestEntry(Map.Entry<String, Long> eldest) {
            return size() > MAX_SAVED;
        }
    };
    private final Set<String> saved = new LinkedHashSet<>();

    synchronized Decision hold(String id, long now) {
        String key = id == null ? UNKNOWN : id;
        Long last = lastHold.get(key);
        if (last != null && now - last <= DEBOUNCE_MS) return Decision.IGNORE;
        lastHold.put(key, now);
        return id != null && saved.contains(id) ? Decision.ALREADY_SAVED : Decision.SAVE;
    }

    synchronized void saved(String id, long now) {
        lastHold.put(id == null ? UNKNOWN : id, now);
        if (id == null) return;
        saved.remove(id);
        saved.add(id);
        if (saved.size() > MAX_SAVED) {
            Iterator<String> oldest = saved.iterator();
            oldest.next();
            oldest.remove();
        }
    }
}
