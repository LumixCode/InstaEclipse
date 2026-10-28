package ps.reso.instaeclipse.mods.ui;

import java.lang.ref.WeakReference;
import java.util.function.Predicate;

public final class PromptGuard<T> {

    private final Predicate<T> live;
    private WeakReference<T> promptActivity;

    public PromptGuard(Predicate<T> live) {
        this.live = live;
    }

    public synchronized boolean begin(T owner) {
        if (current() != null) return false;
        promptActivity = new WeakReference<>(owner);
        return true;
    }

    public synchronized void end(T owner) {
        T held = promptActivity != null ? promptActivity.get() : null;
        if (held == null || held == owner) promptActivity = null;
    }

    public synchronized boolean blocking() {
        return current() != null;
    }

    private T current() {
        T held = promptActivity != null ? promptActivity.get() : null;
        return held != null && live.test(held) ? held : null;
    }
}
