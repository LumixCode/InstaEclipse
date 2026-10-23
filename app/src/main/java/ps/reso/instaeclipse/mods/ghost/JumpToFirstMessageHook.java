package ps.reso.instaeclipse.mods.ghost;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.os.Handler;
import android.os.Looper;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.ViewGroup;
import android.widget.Toast;

import java.lang.ref.WeakReference;

import ps.reso.instaeclipse.R;
import ps.reso.instaeclipse.utils.feature.FeatureFlags;
import ps.reso.instaeclipse.utils.feature.FeatureStatusTracker;
import ps.reso.instaeclipse.utils.ghost.CurrentThreadTracker;
import ps.reso.instaeclipse.utils.i18n.I18n;
import ps.reso.instaeclipse.utils.log.ModuleLog;

/**
 * Jump to the first message of a DM thread (#244).
 *
 * A header button (next to IG's right-side action buttons) keeps scrolling the thread's message
 * list up. IG loads older pages itself when the list nears its oldest end, so we only stop once
 * the list can't scroll further up and has stopped growing for a while. UI-only: no IG internals,
 * just the RecyclerView (by class name) and View scrolling. Tap again, or scroll the list, to stop.
 */
public class JumpToFirstMessageHook {

    private static final String TAG = "ie_jump_first_btn";
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static int threadHeaderId, actionBarEndId;
    private static volatile Runnable running;
    // The user's own jump: its activity and message list, and where a touch on the list started.
    // Fed by ThreadHeaderMenu's startup dispatchTouchEvent hook (main thread); never a runtime hook.
    private static WeakReference<Activity> watchedActivity = new WeakReference<>(null);
    private static WeakReference<View> watchedList = new WeakReference<>(null);
    private static float downY = Float.NaN;
    private static int dragSlop;

    public void install() {
        CurrentThreadTracker.addListener((activity, threadId) -> {
            if (!FeatureFlags.jumpToFirstMessage) return;
            final int[] tries = {0};
            MAIN.postDelayed(new Runnable() {
                @Override
                public void run() {
                    if (inject(activity)) return;
                    if (++tries[0] < 15) MAIN.postDelayed(this, 300);
                    else ModuleLog.detail("(IE|JumpFirst) gave up adding the button: header=" + (threadHeaderId != 0 && activity.findViewById(threadHeaderId) != null)
                            + " unsentBtn=" + (activity.getWindow().getDecorView().findViewWithTag("ie_unsent_btn") != null)
                            + " endBtns=" + (actionBarEndId != 0 && activity.findViewById(actionBarEndId) != null));
                }
            }, 300);
        });
        ModuleLog.ok("(IE|JumpFirst) ready");
        FeatureStatusTracker.setHooked("JumpToFirstMessage");
    }

    @SuppressLint("DiscouragedApi")
    /** Returns true once the button is in the header (or can't be placed in this screen). */
    static boolean inject(Activity a) {
        try {
            if (threadHeaderId == 0) {
                threadHeaderId = a.getResources().getIdentifier("direct_thread_header", "id", a.getPackageName());
                actionBarEndId = a.getResources().getIdentifier("action_bar_end_action_buttons", "id", a.getPackageName());
            }
            View header = threadHeaderId != 0 ? a.findViewById(threadHeaderId) : null;
            if (header == null) return false;
            if (ThreadHeaderMenu.has(a, "jump")) return true;
            ThreadHeaderMenu.add(a, "jump", I18n.t(a, R.string.ig_jump_first_message), () -> toggle(a));
            ModuleLog.probe("(IE|JumpFirst) added to the thread menu");
            return true;
        } catch (Throwable t) {
            ModuleLog.warn("(IE|JumpFirst) Could not add the button to this chat", t);
            return true;
        }
    }

    private static void toggle(Activity a) {
        run(a, null);
    }

    /** Scrolls to the first message; {@code onDone} runs once it's reached (or the list is
     *  missing / the 5 min cap hits), not when the user stops it. Tapping again stops; scrolling
     *  the list stops only a jump without {@code onDone}. */
    static void run(Activity a, Runnable onDone) {
        if (running != null) {
            stop(a);
            return;
        }
        View list = findMessageList(a.getWindow().getDecorView());
        if (list == null) {
            ModuleLog.warn("(IE|JumpFirst) Could not jump to the first message: the message list was not found");
            if (onDone != null) onDone.run();
            return;
        }
        ps.reso.instaeclipse.utils.toast.IeToast.makeText(a, I18n.t(a, R.string.ig_jump_first_loading), Toast.LENGTH_SHORT).show();
        final long started = System.currentTimeMillis();
        final long[] lastChange = {System.currentTimeMillis()};
        final int[] lastCount = {itemCount(list)};
        final int[] lastOffset = {scrollOffset(list)};
        running = new Runnable() {
            @Override
            public void run() {
                if (running != this) return;
                if (!list.isAttachedToWindow() || System.currentTimeMillis() - started > 5 * 60_000) {
                    end();
                    if (onDone != null) onDone.run();
                    return;
                }
                list.scrollBy(0, list.canScrollVertically(-1) ? -list.getHeight() * 4 : -1);
                int count = itemCount(list), off = scrollOffset(list);
                if (count != lastCount[0] || off != lastOffset[0]) {
                    lastCount[0] = count;
                    lastOffset[0] = off;
                    lastChange[0] = System.currentTimeMillis();
                } else if (System.currentTimeMillis() - lastChange[0] > 3000) {
                    // nothing moved and nothing new loaded for 3 s: this is the first message
                    end();
                    ps.reso.instaeclipse.utils.toast.IeToast.makeText(a, I18n.t(a, R.string.ig_jump_first_done), Toast.LENGTH_SHORT).show();
                    if (onDone != null) onDone.run();
                    return;
                }
                MAIN.postDelayed(this, 250);
            }
        };
        // Only the user's own jump: an export's "load all" must not be dropped by a stray drag.
        if (onDone == null) watchDrags(a, list);
        MAIN.post(running);
    }

    /** Stops the jump in progress (tap again, or the user scrolled); its onDone does not run. */
    private static void stop(Activity a) {
        Runnable r = running;
        if (r == null) return;
        MAIN.removeCallbacks(r);
        end();
        ps.reso.instaeclipse.utils.toast.IeToast.makeText(a, I18n.t(a, R.string.ig_jump_first_stopped), Toast.LENGTH_SHORT).show();
    }

    private static void end() {
        running = null;
        watchedActivity = new WeakReference<>(null);
        watchedList = new WeakReference<>(null);
        downY = Float.NaN;
    }

    /** A drag on {@code list} in {@code a} stops this jump (see {@link #onTouch}). */
    private static void watchDrags(Activity a, View list) {
        dragSlop = ViewConfiguration.get(a).getScaledTouchSlop() * 2;
        downY = Float.NaN;
        watchedActivity = new WeakReference<>(a);
        watchedList = new WeakReference<>(list);
    }

    /**
     * Every touch of Instagram's activities, from ThreadHeaderMenu's dispatchTouchEvent hook
     * (installed once at startup: hooking it per jump is the runtime hooking that corrupted the
     * heap on LSPatch 1.2). A drag on the message list stops the jump: the user took over the
     * scrolling. Either way counts, as "scroll down" can mean the finger or the content. The
     * jump's own scrollBy never goes through touch dispatch, so it can't trip this.
     */
    static void onTouch(Activity a, MotionEvent e) {
        if (running == null || watchedActivity.get() != a) return;
        View list = watchedList.get();
        if (list == null) return;
        if (e.getActionMasked() == MotionEvent.ACTION_DOWN) {
            downY = contains(list, e.getRawX(), e.getRawY()) ? e.getRawY() : Float.NaN;
        } else if (e.getActionMasked() == MotionEvent.ACTION_MOVE && !Float.isNaN(downY)
                && Math.abs(e.getRawY() - downY) > dragSlop) {
            downY = Float.NaN;
            ModuleLog.detail("(IE|JumpFirst) stopped by a scroll");
            stop(a);
        }
    }

    private static boolean contains(View v, float x, float y) {
        int[] loc = new int[2];
        v.getLocationOnScreen(loc);
        return x >= loc[0] && x < loc[0] + v.getWidth() && y >= loc[1] && y < loc[1] + v.getHeight();
    }

    private static int itemCount(View list) {
        try {
            Object ad = list.getClass().getMethod("getAdapter").invoke(list);
            if (ad == null) return -1;
            for (java.lang.reflect.Method m : ps.reso.instaeclipse.utils.core.SafeReflect.publicMethods(ad.getClass()))
                if (m.getName().equals("getItemCount") && m.getParameterCount() == 0) return (int) m.invoke(ad);
        } catch (Throwable ignored) {}
        return -1;
    }

    private static int scrollOffset(View list) {
        try {
            java.lang.reflect.Method m = View.class.getDeclaredMethod("computeVerticalScrollOffset");
            m.setAccessible(true);
            return (int) m.invoke(list);
        } catch (Throwable t) {
            return list.getScrollY();
        }
    }

    /** The tallest visible RecyclerView below the thread header = the message list. */
    private static View findMessageList(View root) {
        View best = null;
        java.util.ArrayDeque<View> st = new java.util.ArrayDeque<>();
        st.push(root);
        while (!st.isEmpty()) {
            View v = st.pop();
            if (v.getVisibility() == View.VISIBLE && isRecyclerView(v.getClass()) && v.isShown()
                    && (best == null || v.getHeight() > best.getHeight())) best = v;
            if (v instanceof ViewGroup g) for (int i = 0; i < g.getChildCount(); i++) st.push(g.getChildAt(i));
        }
        return best;
    }

    private static boolean isRecyclerView(Class<?> c) {
        for (; c != null; c = c.getSuperclass()) if (c.getName().equals("androidx.recyclerview.widget.RecyclerView")) return true;
        return false;
    }
}
