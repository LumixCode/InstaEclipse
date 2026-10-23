package ps.reso.instaeclipse.utils.ui;

import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.function.Predicate;

public final class TopActivityTracker<T> {

    private static final class Entry<T> {
        final WeakReference<T> ref;
        boolean resumed = true;

        Entry(T activity) {
            ref = new WeakReference<>(activity);
        }
    }

    private final Predicate<T> gone;
    private final List<Entry<T>> entries = new ArrayList<>();

    public TopActivityTracker(Predicate<T> gone) {
        this.gone = gone;
    }

    public synchronized void onResumed(T activity) {
        if (activity == null) return;
        remove(activity);
        entries.add(new Entry<>(activity));
    }

    public synchronized void onPaused(T activity) {
        Entry<T> entry = find(activity);
        if (entry != null) entry.resumed = false;
    }

    public synchronized void onDestroyed(T activity) {
        remove(activity);
    }

    public synchronized T top() {
        T fallback = null;
        for (int i = entries.size() - 1; i >= 0; i--) {
            Entry<T> entry = entries.get(i);
            T activity = entry.ref.get();
            if (activity == null) {
                entries.remove(i);
                continue;
            }
            if (gone.test(activity)) continue;
            if (entry.resumed) return activity;
            if (fallback == null) fallback = activity;
        }
        return fallback;
    }

    private Entry<T> find(T activity) {
        for (Entry<T> entry : entries) if (entry.ref.get() == activity) return entry;
        return null;
    }

    private void remove(T activity) {
        for (Iterator<Entry<T>> it = entries.iterator(); it.hasNext(); ) {
            T held = it.next().ref.get();
            if (held == null || held == activity) it.remove();
        }
    }
}
