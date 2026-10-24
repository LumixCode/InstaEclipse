package ps.reso.instaeclipse.mods.ghost;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Predicate;

final class KnownThreads {

    static final int MAX = 500;

    private final LinkedHashMap<String, String> threads = new LinkedHashMap<>();

    synchronized void rows(String account, Map<String, String> rows, Predicate<String> keep) {
        for (Map.Entry<String, String> row : rows.entrySet()) {
            String title = row.getValue();
            if (title == null || title.isEmpty()) title = threads.get(row.getKey());
            threads.put(row.getKey(), title == null ? "" : title);
        }
        while (threads.size() > MAX) {
            String oldest = threads.keySet().iterator().next();
            threads.remove(oldest);
        }
    }

    synchronized String opened(String account, String id, String title) {
        String clean = title == null ? "" : title.trim();
        String known = threads.get(id);
        if (known != null && !known.isEmpty()) return known;
        threads.put(id, clean);
        return clean;
    }

    synchronized boolean has(String id) {
        return threads.containsKey(id);
    }

    synchronized String title(String id) {
        String title = threads.get(id);
        return title == null ? "" : title;
    }

    synchronized Map<String, String> list(String account) {
        return new LinkedHashMap<>(threads);
    }
}
