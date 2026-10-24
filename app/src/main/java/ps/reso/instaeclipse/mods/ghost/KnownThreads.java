package ps.reso.instaeclipse.mods.ghost;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;

final class KnownThreads {

    static final int MAX = 500;

    private final Map<String, LinkedHashMap<String, String>> byAccount = new HashMap<>();
    private String lastAccount;

    synchronized void rows(String account, Map<String, String> rows, Predicate<String> keep) {
        Map<String, String> before = byAccount.getOrDefault(account, new LinkedHashMap<>());
        LinkedHashMap<String, String> threads = new LinkedHashMap<>();
        for (Map.Entry<String, String> row : rows.entrySet()) {
            String title = row.getValue();
            if (title == null || title.isEmpty()) title = before.get(row.getKey());
            threads.put(row.getKey(), title == null ? "" : title);
        }
        for (Map.Entry<String, String> old : before.entrySet()) threads.putIfAbsent(old.getKey(), old.getValue());
        if (threads.size() > MAX) {
            List<String> ids = new ArrayList<>(threads.keySet());
            Collections.reverse(ids);
            for (String id : ids) {
                if (threads.size() <= MAX) break;
                if (!keep.test(id)) threads.remove(id);
            }
        }
        byAccount.put(account, threads);
        lastAccount = account;
    }

    synchronized String opened(String account, String id, String title) {
        String clean = title == null ? "" : title.trim();
        Map<String, String> owner = ownerOf(id);
        if (owner == null) owner = byAccount.computeIfAbsent(account != null ? account : lastAccount,
                ignored -> new LinkedHashMap<>());
        String known = owner.get(id);
        if (known != null && !known.isEmpty()) return known;
        owner.put(id, clean);
        return clean;
    }

    synchronized boolean has(String id) {
        return ownerOf(id) != null;
    }

    synchronized String title(String id) {
        Map<String, String> owner = ownerOf(id);
        String title = owner == null ? null : owner.get(id);
        return title == null ? "" : title;
    }

    synchronized Map<String, String> list(String account) {
        Map<String, String> threads = byAccount.get(account != null ? account : lastAccount);
        return threads == null ? new LinkedHashMap<>() : new LinkedHashMap<>(threads);
    }

    private Map<String, String> ownerOf(String id) {
        for (Map<String, String> threads : byAccount.values()) if (threads.containsKey(id)) return threads;
        return null;
    }
}
