package ps.reso.instaeclipse.mods.ghost;

import java.util.Map;
import java.util.WeakHashMap;

final class RowListeners<V, L> {

    interface Registrar<V, L> {
        void register(V row, L listener);

        void unregister(V row, L listener);
    }

    private static final class Entry<L> {
        final L listener;
        boolean registered;

        Entry(L listener) { this.listener = listener; }
    }

    private final Registrar<V, L> registrar;
    private final Map<V, Entry<L>> rows = new WeakHashMap<>();

    RowListeners(Registrar<V, L> registrar) {
        this.registrar = registrar;
    }

    boolean tracks(V row) {
        return rows.containsKey(row);
    }

    boolean track(V row, L listener, boolean attached) {
        if (rows.containsKey(row)) return false;
        rows.put(row, new Entry<>(listener));
        if (attached) attached(row);
        return true;
    }

    void attached(V row) {
        Entry<L> entry = rows.get(row);
        if (entry == null || entry.registered) return;
        registrar.register(row, entry.listener);
        entry.registered = true;
    }

    void detached(V row) {
        Entry<L> entry = rows.get(row);
        if (entry == null || !entry.registered) return;
        registrar.unregister(row, entry.listener);
        entry.registered = false;
    }

    boolean registered(V row) {
        Entry<L> entry = rows.get(row);
        return entry != null && entry.registered;
    }
}
