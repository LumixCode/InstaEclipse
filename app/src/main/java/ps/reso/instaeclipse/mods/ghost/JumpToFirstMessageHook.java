package ps.reso.instaeclipse.mods.ghost;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Handler;
import android.os.Looper;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import java.lang.ref.WeakReference;
import java.lang.reflect.Method;
import java.util.function.Consumer;

import ps.reso.instaeclipse.R;
import ps.reso.instaeclipse.utils.feature.FeatureStatusTracker;
import ps.reso.instaeclipse.utils.i18n.I18n;
import ps.reso.instaeclipse.utils.log.ModuleLog;
import ps.reso.instaeclipse.utils.ui.IePalette;
import ps.reso.instaeclipse.utils.ui.ResIds;

/**
 * Jump to the first message of a DM thread (#244).
 *
 * A header button (next to IG's right-side action buttons) keeps scrolling the thread's message
 * list up. IG loads older pages itself when the list nears its oldest end, so we only stop once
 * the list can't scroll further up and has stopped growing for a while. UI-only: no IG internals,
 * just the RecyclerView (by class name) and View scrolling. Tap again, or scroll the list, to stop.
 */
public class JumpToFirstMessageHook {

    public static final String CHIP_TAG = "ie_jump_first_chip";
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static int threadHeaderId;
    private static volatile Run running;
    // The user's own jump: its activity and message list, and where a touch on the list started.
    // Fed by ThreadHeaderMenu's startup dispatchTouchEvent hook (main thread); never a runtime hook.
    private static WeakReference<Activity> watchedActivity = new WeakReference<>(null);
    private static WeakReference<View> watchedList = new WeakReference<>(null);
    private static float downY = Float.NaN;
    private static int dragSlop;

    public void install() {
        ModuleLog.ok("(IE|JumpFirst) ready");
        FeatureStatusTracker.setHooked("JumpToFirstMessage");
    }

    @SuppressLint("DiscouragedApi")
    /** Returns true once the button is in the header (or can't be placed in this screen). */
    static boolean inject(Activity a) {
        try {
            if (threadHeaderId == 0) {
                threadHeaderId = a.getResources().getIdentifier("direct_thread_header", "id", a.getPackageName());
            }
            View header = threadHeaderId != 0 ? a.findViewById(threadHeaderId) : null;
            if (header == null) return false;
            if (ThreadHeaderMenu.has(a, "jump")) return true;
            ThreadHeaderMenu.add(a, "jump", menuLabel(a), () -> toggle(a));
            ModuleLog.probe("(IE|JumpFirst) added to the thread menu");
            return true;
        } catch (Throwable t) {
            ModuleLog.warn("(IE|JumpFirst) Could not add the button to this chat", t);
            return true;
        }
    }

    private static CharSequence menuLabel(Activity a) {
        return I18n.t(a, running != null ? R.string.ig_jump_first_stop_menu : R.string.ig_jump_first_message);
    }

    private static void relabel(Activity a) {
        if (ThreadHeaderMenu.has(a, "jump")) ThreadHeaderMenu.add(a, "jump", menuLabel(a), () -> toggle(a));
    }

    private static void toggle(Activity a) {
        run(a, UnsentThreadButtonHook.openChat(a), null);
    }

    /** Scrolls to the first message; {@code onDone} runs once it's reached (or the list is
     *  missing / the 5 min cap hits), not when the user stops it. Tapping again stops; scrolling
     *  the list stops only a jump without {@code onDone}. */
    static void run(Activity a, String thread, Consumer<String> onDone) {
        JumpRunPolicy.Request request = JumpRunPolicy.request(running != null, onDone != null);
        if (request == JumpRunPolicy.Request.STOP) {
            stop(a);
            return;
        }
        if (request == JumpRunPolicy.Request.REPLACE) cancel();
        View list = messageList(a);
        if (list == null) {
            ModuleLog.warn("(IE|JumpFirst) Could not jump to the first message: the message list was not found");
            if (onDone != null) onDone.accept(thread);
            return;
        }
        Run run = new Run(a, list, thread, onDone);
        running = run;
        run.chip = Chip.show(a, list, () -> stop(a));
        if (run.chip != null) run.chip.loaded(run.lastCount);
        if (onDone == null) watchDrags(a, list);
        relabel(a);
        MAIN.post(run);
    }

    private static final class Run implements Runnable {
        final Activity activity;
        final View list;
        final String thread;
        final Consumer<String> onDone;
        final ListProbe probe;
        final long started = System.currentTimeMillis();
        long lastChange = started;
        int lastCount;
        int lastOffset;
        Chip chip;

        Run(Activity activity, View list, String thread, Consumer<String> onDone) {
            this.activity = activity;
            this.list = list;
            this.thread = thread;
            this.onDone = onDone;
            probe = new ListProbe(list);
            lastCount = probe.itemCount();
            lastOffset = probe.scrollOffset();
        }

        @Override
        public void run() {
            if (running != this) return;
            long now = System.currentTimeMillis();
            JumpRunPolicy.Step step = JumpRunPolicy.step(list.isShown(), thread,
                    UnsentThreadButtonHook.openChat(activity), now - started, now - lastChange);
            if (step != JumpRunPolicy.Step.SCROLL) {
                finish(step);
                return;
            }
            list.scrollBy(0, list.canScrollVertically(-1) ? -list.getHeight() * 4 : -1);
            int count = probe.itemCount(), off = probe.scrollOffset();
            if (count != lastCount || off != lastOffset) {
                lastCount = count;
                lastOffset = off;
                lastChange = System.currentTimeMillis();
            }
            if (chip != null) chip.loaded(count);
            MAIN.postDelayed(this, 250);
        }

        private void finish(JumpRunPolicy.Step step) {
            end(this);
            ModuleLog.detail("(IE|JumpFirst) ended: " + step + " items=" + lastCount + " export=" + (onDone != null));
            if (step == JumpRunPolicy.Step.REACHED) toast(activity, R.string.ig_jump_first_reached);
            else if (step == JumpRunPolicy.Step.CANCELLED) toast(activity, R.string.ig_jump_first_stopped_loading);
            String target = JumpRunPolicy.exportThread(step, thread);
            if (onDone != null && target != null) onDone.accept(target);
        }
    }

    /** Stops the jump in progress (tap again, or the user scrolled); its onDone does not run. */
    private static void stop(Activity a) {
        if (!cancel()) return;
        toast(a, R.string.ig_jump_first_stopped_loading);
    }

    private static boolean cancel() {
        Run r = running;
        if (r == null) return false;
        MAIN.removeCallbacks(r);
        end(r);
        return true;
    }

    private static void end(Run r) {
        running = null;
        watchedActivity = new WeakReference<>(null);
        watchedList = new WeakReference<>(null);
        downY = Float.NaN;
        if (r.chip != null) r.chip.remove();
        relabel(r.activity);
    }

    private static void toast(Activity a, int text) {
        ps.reso.instaeclipse.utils.toast.IeToast.makeText(a, I18n.t(a, text), Toast.LENGTH_SHORT).show();
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

    private static final class ListProbe {
        final View list;
        final Method adapter;
        final Method offset;
        Class<?> adapterClass;
        Method count;

        ListProbe(View list) {
            this.list = list;
            adapter = publicNoArg(list.getClass(), "getAdapter");
            Method m;
            try {
                m = View.class.getDeclaredMethod("computeVerticalScrollOffset");
                m.setAccessible(true);
            } catch (Throwable t) {
                m = null;
            }
            offset = m;
        }

        int itemCount() {
            try {
                Object ad = adapter == null ? null : adapter.invoke(list);
                if (ad == null) return -1;
                if (ad.getClass() != adapterClass) {
                    adapterClass = ad.getClass();
                    count = publicNoArg(adapterClass, "getItemCount");
                }
                return count == null ? -1 : (int) count.invoke(ad);
            } catch (Throwable ignored) {
                return -1;
            }
        }

        int scrollOffset() {
            try {
                return offset == null ? list.getScrollY() : (int) offset.invoke(list);
            } catch (Throwable t) {
                return list.getScrollY();
            }
        }
    }

    private static Method publicNoArg(Class<?> type, String name) {
        for (Method m : ps.reso.instaeclipse.utils.core.SafeReflect.publicMethods(type))
            if (m.getName().equals(name) && m.getParameterCount() == 0) return m;
        return null;
    }

    private static View messageList(Activity a) {
        View root = a.getWindow().getDecorView();
        View shown = ThreadHeaderMenu.findShown(root, ResIds.id(a, "message_list"));
        if (shown != null && isRecyclerView(shown.getClass())) return shown;
        return findMessageList(root);
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

    public static final class Chip {
        private final Activity activity;
        private final View list;
        private final LinearLayout view;
        private final TextView label;
        private final int gap;
        private int top = Integer.MIN_VALUE;
        private int shown = Integer.MIN_VALUE;

        private Chip(Activity activity, View list, Runnable stop) {
            this.activity = activity;
            this.list = list;
            IePalette p = IePalette.of(activity);
            float d = activity.getResources().getDisplayMetrics().density;
            gap = Math.round(8 * d);
            view = new LinearLayout(activity);
            view.setTag(CHIP_TAG);
            view.setOrientation(LinearLayout.HORIZONTAL);
            view.setGravity(Gravity.CENTER_VERTICAL);
            view.setPaddingRelative(Math.round(16 * d), 0, Math.round(4 * d), 0);
            GradientDrawable bg = new GradientDrawable();
            bg.setColor(p.dialog);
            bg.setCornerRadius(24 * d);
            bg.setStroke(Math.max(1, Math.round(d)), p.line);
            view.setBackground(bg);
            view.setElevation(6 * d);
            label = new TextView(activity);
            label.setTextColor(p.text);
            label.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
            view.addView(label, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT));
            TextView button = new TextView(activity);
            button.setText(I18n.t(activity, R.string.ig_jump_first_stop));
            button.setContentDescription(I18n.t(activity, R.string.ig_jump_first_stop_menu));
            button.setTextColor(IePalette.readable(p.accent, p.dialog, IePalette.TEXT));
            button.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
            button.setTypeface(Typeface.DEFAULT_BOLD);
            button.setGravity(Gravity.CENTER);
            int target = Math.round(48 * d);
            button.setMinWidth(target);
            button.setMinHeight(target);
            button.setPaddingRelative(Math.round(12 * d), 0, Math.round(12 * d), 0);
            button.setOnClickListener(v -> stop.run());
            view.addView(button, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT));
        }

        public static Chip show(Activity activity, View list, Runnable stop) {
            if (activity == null || list == null || !(activity.findViewById(android.R.id.content) instanceof FrameLayout content)) return null;
            for (View old; (old = content.findViewWithTag(CHIP_TAG)) != null; ) content.removeView(old);
            Chip chip = new Chip(activity, list, stop);
            FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP | Gravity.CENTER_HORIZONTAL);
            lp.leftMargin = 2 * chip.gap;
            lp.rightMargin = 2 * chip.gap;
            content.addView(chip.view, lp);
            chip.place();
            return chip;
        }

        public View view() {
            return view;
        }

        public void loaded(int count) {
            if (count != shown) {
                shown = count;
                label.setText(count >= 0 ? progress(activity, count) : I18n.t(activity, R.string.ig_jump_first_progress_counting));
            }
            place();
        }

        public void remove() {
            if (view.getParent() instanceof ViewGroup g) g.removeView(view);
        }

        private void place() {
            if (!(view.getParent() instanceof View parent) || !(view.getLayoutParams() instanceof ViewGroup.MarginLayoutParams lp)) return;
            int[] at = new int[2];
            int[] origin = new int[2];
            parent.getLocationInWindow(origin);
            list.getLocationInWindow(at);
            int y = at[1] - origin[1];
            View header = ThreadHeaderMenu.shownHeader(activity);
            if (header != null) {
                header.getLocationInWindow(at);
                y = Math.max(y, at[1] - origin[1] + header.getHeight());
            }
            y = Math.max(0, y) + gap;
            if (y == top) return;
            top = y;
            lp.topMargin = y;
            view.setLayoutParams(lp);
        }

        private static String progress(Activity activity, int count) {
            try {
                return I18n.moduleResources(activity).getQuantityString(R.plurals.ig_jump_first_progress, count, count);
            } catch (Throwable t) {
                return I18n.t(activity, R.string.ig_jump_first_progress_counting);
            }
        }
    }
}
