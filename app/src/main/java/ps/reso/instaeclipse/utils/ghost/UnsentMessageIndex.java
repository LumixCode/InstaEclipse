package ps.reso.instaeclipse.utils.ghost;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Only observed removal events enter this index; receiving a message is not an unsend. */
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
    private long revision;

    public UnsentMessageIndex(int capacity) {
        if (capacity < 1) throw new IllegalArgumentException("capacity");
        this.capacity = capacity;
    }

    public synchronized boolean record(String account, String thread, String message) {
        if (!valid(account) || !valid(thread) || !valid(message)) return false;
        if (contains(account, thread, message)) return false;
        return recordAliases(account, thread, Collections.singletonList(message)) != null;
    }

    /** Server and client IDs identify one removal, including rows that still have only a client ID. */
    public synchronized Key recordAliases(String account, String thread, List<String> ids) {
        if (!valid(account) || !valid(thread) || ids == null) return null;
        LinkedHashSet<String> aliases = new LinkedHashSet<>();
        for (String id : ids) if (valid(id)) aliases.add(id);
        if (aliases.isEmpty()) return null;

        Key match = null;
        for (Map.Entry<Key, LinkedHashSet<String>> entry : deleted.entrySet()) {
            Key key = entry.getKey();
            if (!account.equals(key.account) || !thread.equals(key.thread)
                    || Collections.disjoint(entry.getValue(), aliases)) continue;
            // A pair that would join different observed deletions is ambiguous; change neither.
            if (match != null) return null;
            match = key;
        }
        if (match != null) {
            if (deleted.get(match).addAll(aliases)) revision++;
            return match;
        }

        Key key = new Key(account, thread, aliases.iterator().next());
        deleted.put(key, aliases);
        if (deleted.size() > capacity) deleted.remove(deleted.keySet().iterator().next());
        revision++;
        return key;
    }

    public synchronized boolean contains(String account, String thread, String message) {
        return valid(account) && valid(thread) && valid(message)
                && find(account, thread, Collections.singletonList(message)) != null;
    }

    /** Some live items omit their thread key. Accept only an unambiguous event in this account. */
    public synchronized Key find(String account, String thread, Iterable<String> ids) {
        if (!valid(account) || ids == null) return null;
        Key match = null;
        for (String id : ids) {
            if (!valid(id)) continue;
            for (Map.Entry<Key, LinkedHashSet<String>> entry : deleted.entrySet()) {
                Key key = entry.getKey();
                if (!account.equals(key.account) || !entry.getValue().contains(id)
                        || (valid(thread) && !thread.equals(key.thread))) continue;
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
    public synchronized long revision() { return revision; }
    public synchronized boolean remove(Key key) {
        if (key == null || !valid(key.account) || !valid(key.thread) || !valid(key.message)
                || deleted.remove(key) == null) return false;
        revision++;
        return true;
    }
    public synchronized void clear(String account, String thread) {
        if (!valid(account)) return;
        if (deleted.keySet().removeIf(key -> account.equals(key.account)
                && (!valid(thread) || thread.equals(key.thread)))) revision++;
    }
    private static boolean valid(String value) { return value != null && !value.isEmpty(); }
}
