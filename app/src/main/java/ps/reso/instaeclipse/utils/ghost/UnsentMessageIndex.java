package ps.reso.instaeclipse.utils.ghost;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;

public final class UnsentMessageIndex {
    public static final class Key {
        public final String account, thread, message;

        public Key(String account, String thread, String message) {
            this.account = account;
            this.thread = thread;
            this.message = message;
        }

        @Override public boolean equals(Object other) {
            if (!(other instanceof Key key)) return false;
            return account.equals(key.account) && thread.equals(key.thread) && message.equals(key.message);
        }

        @Override public int hashCode() { return Objects.hash(account, thread, message); }
    }

    private final int capacity;
    private final Map<Key, LinkedHashSet<String>> deleted = new LinkedHashMap<>();
    private final Map<String, List<Key>> byAlias = new HashMap<>();
    private volatile long revision;

    public UnsentMessageIndex(int capacity) {
        if (capacity < 1) throw new IllegalArgumentException("capacity");
        this.capacity = capacity;
    }

    public synchronized boolean record(String account, String thread, String message) {
        if (!valid(account) || !valid(thread) || !valid(message)) return false;
        if (contains(account, thread, message)) return false;
        return recordAliases(account, thread, Collections.singletonList(message)) != null;
    }

    public synchronized Key recordAliases(String account, String thread, List<String> ids) {
        if (!valid(account) || !valid(thread) || ids == null) return null;
        LinkedHashSet<String> aliases = new LinkedHashSet<>();
        for (String id : ids) if (valid(id)) aliases.add(id);
        if (aliases.isEmpty()) return null;

        Key match = null;
        for (String alias : aliases) {
            List<Key> keys = byAlias.get(alias);
            if (keys == null) continue;
            for (Key key : keys) {
                if (!account.equals(key.account) || !thread.equals(key.thread) || key.equals(match)) continue;
                if (match != null) return null;
                match = key;
            }
        }
        if (match != null) {
            LinkedHashSet<String> known = deleted.get(match);
            boolean added = false;
            for (String alias : aliases) {
                if (known.add(alias)) {
                    index(alias, match);
                    added = true;
                }
            }
            if (added) revision++;
            return match;
        }

        Key key = new Key(account, thread, aliases.iterator().next());
        deleted.put(key, aliases);
        for (String alias : aliases) index(alias, key);
        if (deleted.size() > capacity) drop(deleted.keySet().iterator().next());
        revision++;
        return key;
    }

    public synchronized boolean contains(String account, String thread, String message) {
        return valid(account) && valid(thread) && valid(message)
                && find(account, thread, Collections.singletonList(message)) != null;
    }

    public synchronized Key find(String account, String thread, Iterable<String> ids) {
        if (!valid(account) || ids == null) return null;
        Key match = null;
        for (String id : ids) {
            if (!valid(id)) continue;
            List<Key> keys = byAlias.get(id);
            if (keys == null) continue;
            for (Key key : keys) {
                if (!account.equals(key.account) || (valid(thread) && !thread.equals(key.thread))) continue;
                if (match != null && !match.equals(key)) return null;
                match = key;
            }
        }
        return match;
    }

    public synchronized List<String> aliases(Key key) {
        if (key == null || !valid(key.account) || !valid(key.thread) || !valid(key.message)) {
            return Collections.emptyList();
        }
        LinkedHashSet<String> aliases = deleted.get(key);
        return aliases == null ? Collections.emptyList() : new ArrayList<>(aliases);
    }

    public synchronized boolean isEmpty() { return deleted.isEmpty(); }
    public long revision() { return revision; }
    public synchronized boolean remove(Key key) {
        if (key == null || !valid(key.account) || !valid(key.thread) || !valid(key.message)
                || !deleted.containsKey(key)) return false;
        drop(key);
        revision++;
        return true;
    }
    public synchronized void clear(String account, String thread) {
        if (!valid(account)) return;
        boolean removed = false;
        for (Iterator<Map.Entry<Key, LinkedHashSet<String>>> it = deleted.entrySet().iterator(); it.hasNext(); ) {
            Map.Entry<Key, LinkedHashSet<String>> entry = it.next();
            Key key = entry.getKey();
            if (!account.equals(key.account) || (valid(thread) && !thread.equals(key.thread))) continue;
            it.remove();
            unindex(key, entry.getValue());
            removed = true;
        }
        if (removed) revision++;
    }

    private void index(String alias, Key key) {
        List<Key> keys = byAlias.get(alias);
        if (keys == null) {
            keys = new ArrayList<>(1);
            byAlias.put(alias, keys);
        }
        keys.add(key);
    }

    private void drop(Key key) {
        LinkedHashSet<String> aliases = deleted.remove(key);
        if (aliases != null) unindex(key, aliases);
    }

    private void unindex(Key key, Iterable<String> aliases) {
        for (String alias : aliases) {
            List<Key> keys = byAlias.get(alias);
            if (keys == null) continue;
            keys.remove(key);
            if (keys.isEmpty()) byAlias.remove(alias);
        }
    }

    private static boolean valid(String value) { return value != null && !value.isEmpty(); }
}
