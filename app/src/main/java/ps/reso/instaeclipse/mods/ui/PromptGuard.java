package ps.reso.instaeclipse.mods.ui;

import java.util.function.Predicate;

public final class PromptGuard<T> {

    private final Predicate<T> live;
    private boolean prompting;

    public PromptGuard(Predicate<T> live) {
        this.live = live;
    }

    public synchronized boolean begin(T owner) {
        if (prompting) return false;
        prompting = true;
        return true;
    }

    public synchronized void end(T owner) {
        prompting = false;
    }

    public synchronized boolean blocking() {
        return prompting;
    }
}
