package ps.reso.instaeclipse.mods.ghost;

import android.app.Activity;
import android.view.View;
import android.view.ViewTreeObserver;
import android.view.animation.AnimationUtils;

import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.WeakHashMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.LongSupplier;

import ps.reso.instaeclipse.utils.core.LegacyPaths;
import ps.reso.instaeclipse.utils.ghost.CurrentThreadTracker;
import ps.reso.instaeclipse.utils.log.ModuleLog;

final class ThreadHeaderInjector {

    interface Entry {
        boolean inject(Activity activity, String threadId);
    }

    private record Slot(boolean everyPass, Entry entry) {}

    static volatile LongSupplier frames = AnimationUtils::currentAnimationTimeMillis;

    private static final Map<String, Slot> SLOTS = new ConcurrentHashMap<>();
    private static final Map<View, Decor> DECORS = new WeakHashMap<>();
    private static boolean installed;

    private ThreadHeaderInjector() {}

    static synchronized void install(ClassLoader loader) {
        CurrentThreadTracker.install(loader);
        if (installed) return;
        installed = true;
        CurrentThreadTracker.addListener(new CurrentThreadTracker.Listener() {
            @Override public void onThreadResumed(Activity activity, String threadId) {
                opened(activity, threadId);
            }

            @Override public void onThreadPaused(Activity activity, String threadId) {
                closed(activity);
            }
        });
    }

    static void register(String key, boolean everyPass, Entry entry) {
        SLOTS.put(key, new Slot(everyPass, entry));
    }

    static void opened(Activity activity, String threadId) {
        if (activity == null || LegacyPaths.has(LegacyPaths.HEADER)) return;
        View decor = activity.getWindow().getDecorView();
        Decor state = DECORS.get(decor);
        if (state == null) {
            state = new Decor(activity);
            try {
                decor.getViewTreeObserver().addOnGlobalLayoutListener(state.listener);
            } catch (Throwable t) {
                ModuleLog.detail("(IE|ThreadHeader) chat listener not added: " + t.getClass().getSimpleName());
                return;
            }
            DECORS.put(decor, state);
        }
        state.open(threadId);
    }

    static void closed(Activity activity) {
        if (activity == null) return;
        View decor = activity.getWindow().getDecorView();
        Decor state = DECORS.get(decor);
        if (state == null) return;
        String still = CurrentThreadTracker.currentThreadId(activity);
        if (still != null) {
            state.open(still);
            return;
        }
        DECORS.remove(decor);
        try {
            decor.getViewTreeObserver().removeOnGlobalLayoutListener(state.listener);
        } catch (Throwable ignored) {
        }
    }

    static boolean handled(View decor, String... keys) {
        Decor state = decor == null ? null : DECORS.get(decor);
        if (state == null || !state.pass()) return false;
        for (String key : keys) if (!state.covers(key)) return false;
        return true;
    }

    static int listenersForTest(View decor) {
        return DECORS.containsKey(decor) ? 1 : 0;
    }

    static void resetForTest() {
        for (Map.Entry<View, Decor> e : new ArrayList<>(DECORS.entrySet())) {
            try {
                e.getKey().getViewTreeObserver().removeOnGlobalLayoutListener(e.getValue().listener);
            } catch (Throwable ignored) {
            }
        }
        DECORS.clear();
        SLOTS.clear();
        frames = AnimationUtils::currentAnimationTimeMillis;
    }

    private static final class Decor {
        private static final long NONE = Long.MIN_VALUE;

        final WeakReference<Activity> activity;
        final ViewTreeObserver.OnGlobalLayoutListener listener = this::pass;
        final Set<String> done = new HashSet<>();
        final Set<String> ran = new HashSet<>();
        WeakReference<View> header = new WeakReference<>(null);
        String threadId, shownThread;
        long frame = NONE;
        boolean shown;

        Decor(Activity activity) {
            this.activity = new WeakReference<>(activity);
        }

        void open(String id) {
            threadId = id;
            done.clear();
            frame = NONE;
        }

        boolean pass() {
            long now = frames.getAsLong();
            if (now == frame) return shown;
            frame = now;
            ran.clear();
            Activity a = activity.get();
            View current = a == null ? null : ThreadHeaderMenu.shownHeader(a);
            String live = current == null ? null : CurrentThreadTracker.currentThreadId(a);
            String id = live != null ? live : threadId;
            if (current != header.get() || !Objects.equals(id, shownThread)) {
                header = new WeakReference<>(current);
                shownThread = id;
                done.clear();
            }
            shown = current != null;
            if (!shown) return false;
            for (String key : ThreadHeaderMenu.ORDER) {
                Slot slot = SLOTS.get(key);
                if (slot == null || (!slot.everyPass() && done.contains(key))) continue;
                boolean ok;
                try {
                    ok = slot.entry().inject(a, id);
                } catch (Throwable t) {
                    ok = false;
                    ModuleLog.detail("(IE|ThreadHeader) \"" + key + "\" entry failed: " + t.getClass().getSimpleName());
                }
                ran.add(key);
                if (ok) done.add(key);
            }
            return true;
        }

        boolean covers(String key) {
            Slot slot = SLOTS.get(key);
            if (slot == null) return false;
            return slot.everyPass() ? ran.contains(key) : done.contains(key);
        }
    }
}
