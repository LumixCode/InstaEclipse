package ps.reso.instaeclipse.mods.ghost;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.graphics.Color;
import android.graphics.drawable.Drawable;
import android.os.Handler;
import android.os.Looper;
import android.text.Layout;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.lang.ref.WeakReference;
import java.lang.reflect.Method;
import java.util.LinkedHashMap;
import java.util.Map;

import ps.reso.instaeclipse.xposedcompat.XC_MethodHook;
import ps.reso.instaeclipse.xposedcompat.XposedBridge;
import ps.reso.instaeclipse.R;
import ps.reso.instaeclipse.utils.core.JitGuard;
import ps.reso.instaeclipse.utils.dialog.IeDialog;
import ps.reso.instaeclipse.utils.feature.FeatureFlags;
import ps.reso.instaeclipse.utils.feature.FeatureStatusTracker;
import ps.reso.instaeclipse.utils.ghost.CurrentThreadTracker;
import ps.reso.instaeclipse.utils.i18n.I18n;
import ps.reso.instaeclipse.utils.log.ModuleLog;

/**
 * One module button in the DM thread header (right of the back arrow) whose popup lists the
 * thread actions (unsent messages, export, jump to first, hide chat). Each feature registers its
 * entry with {@link #add}; a separate 40dp button per feature pushed the chat name off the header.
 * With {@code hideChatMenuButton} the button stays in the header but GONE, so the header looks
 * native and the entries still live on it; three quick taps in the free header space between the
 * chat name and the call buttons open the same popup.
 */
final class ThreadHeaderMenu {
    static final String TAG = "ie_thread_menu";
    private static final String[] ORDER = {"unsent", "export", "jump", "read", "hide"};
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    /**
     * How long a held header tap waits for the next one before it reaches Instagram. Half of
     * QuickTapSequence's 800 ms for all three taps, so a moderate triple tap is not cut short by
     * the first replay opening the chat details; a single tap on the name opens them this late.
     */
    private static final long REPLAY_DELAY_MS = 400;

    private record Entry(CharSequence label, Runnable action) {}

    /**
     * The entries live on the button itself: every action captures its chat's Activity, which
     * reaches the button, so a static (even weak-keyed) map would pin every chat ever opened.
     */
    @SuppressLint({"AppCompatCustomView", "ViewConstructor"}) // runs inside Instagram, built in code
    private static final class MenuButton extends android.widget.ImageView {
        final Map<String, Entry> entries = new LinkedHashMap<>();

        MenuButton(android.content.Context c) { super(c); }
    }

    // Header gesture state. Touch dispatch and the replay both run on the main thread.
    private static final QuickTapSequence gapTaps = new QuickTapSequence();
    private static WeakReference<View> lastHeader;
    private static WeakReference<MenuButton> heldButton;
    private static MotionEvent heldDown;
    /** The held tap's copies while its replay is pending, so dropReplay can recycle them. */
    private static MotionEvent replayDown, replayUp;
    private static Runnable pendingReplay;
    private static boolean tracking, stillTap, replaying;
    private static int headerId, titleBoxId, endButtonsId;

    private static Map<String, Entry> entriesOf(View btn) {
        return btn instanceof MenuButton b ? b.entries : null;
    }

    private ThreadHeaderMenu() {}

    /**
     * The open chat's button. A hidden cached chat keeps its own header and button, which
     * findViewWithTag can return first: only a button whose containers are all visible counts
     * (the button itself can be GONE, see hideChatMenuButton).
     */
    private static MenuButton shownButton(Activity a) {
        View decor = a.getWindow().getDecorView();
        return decor.isShown() ? shownButton(decor) : null;
    }

    private static MenuButton shownButton(View v) {
        if (v instanceof MenuButton b) return b;
        if (!(v instanceof ViewGroup g) || v.getVisibility() != View.VISIBLE) return null;
        for (int i = 0; i < g.getChildCount(); i++) {
            MenuButton b = shownButton(g.getChildAt(i));
            if (b != null) return b;
        }
        return null;
    }

    /** True if the open thread's menu already lists {@code key}. */
    static boolean has(Activity a, String key) {
        Map<String, Entry> m = entriesOf(shownButton(a));
        return m != null && m.containsKey(key);
    }

    /** Adds (or replaces) an entry, creating the menu button if needed. False if the header isn't there. */
    static boolean add(Activity a, String key, CharSequence label, Runnable action) {
        try {
            View btn = shownButton(a);
            if (btn == null) btn = createButton(a);
            if (btn == null) return false;
            Map<String, Entry> m = entriesOf(btn);
            if (m == null) return false;
            m.put(key, new Entry(label, action));
            btn.setVisibility(buttonVisibility()); // the option may have changed since the chat opened
            return true;
        } catch (Throwable t) {
            ModuleLog.warn("(IE|ThreadMenu) Could not add \"" + key + "\" to the chat menu", t);
            return false;
        }
    }

    /** Drops {@code key}; the button goes once no entry is left (a feature switched off). */
    static void remove(Activity a, String key) {
        View btn = shownButton(a);
        Map<String, Entry> m = entriesOf(btn);
        if (m == null || m.remove(key) == null || !m.isEmpty()) return;
        if (btn.getParent() instanceof ViewGroup g) g.removeView(btn);
    }

    /** Opens the open chat's menu again (Back from a list opened from it). */
    static void reopen(Activity a) {
        try {
            View btn = shownButton(a);
            if (btn != null) show(btn);
        } catch (Throwable ignored) { }
    }

    private static int buttonVisibility() {
        return FeatureFlags.hideChatMenuButton ? View.GONE : View.VISIBLE;
    }

    @SuppressLint("DiscouragedApi")
    private static View createButton(Activity a) {
        String pkg = a.getPackageName();
        android.content.res.Resources res = a.getResources();
        View decor = a.getWindow().getDecorView();
        View header = findShown(decor, res.getIdentifier("direct_thread_header", "id", pkg));
        if (header == null) return null;
        ViewGroup target;
        int insertAt;
        // The chat header's own back arrow is header_left_button (446, 451); action_bar_button_back
        // is the generic action bar's and is not in the chat header, which put the button in front
        // of the arrow. The main activity can hold other action bars (and hidden cached chats), so
        // only the arrow on screen next to this header counts.
        View back = findShown(header, res.getIdentifier("header_left_button", "id", pkg));
        int backId = res.getIdentifier("action_bar_button_back", "id", pkg);
        for (View scope = header; scope != null && scope != decor && back == null;
             scope = scope.getParent() instanceof View p ? p : null) back = findShown(scope, backId);
        if (back != null && back.getParent() instanceof ViewGroup g) {
            target = g;
            int bi = g.indexOfChild(back);
            // visually to the right of the back arrow: after it in LTR, before it in RTL
            insertAt = g.getLayoutDirection() == View.LAYOUT_DIRECTION_RTL ? bi : bi + 1;
        } else if (header instanceof ViewGroup g) {
            target = g;
            insertAt = 0;
        } else return null;

        MenuButton btn = new MenuButton(a);
        btn.setTag(TAG);
        Drawable icon = ps.reso.instaeclipse.utils.dialog.DialogUtils.moduleIcon(R.drawable.ic_tune, Color.WHITE);
        if (icon != null) btn.setImageDrawable(icon);
        btn.setScaleType(android.widget.ImageView.ScaleType.CENTER_INSIDE);
        float d = a.getResources().getDisplayMetrics().density;
        int pad = (int) (9 * d);
        btn.setPadding(pad, pad, pad, pad);
        btn.setContentDescription(I18n.t(a, R.string.ig_thread_menu));
        int sz = (int) (40 * d);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(sz, sz);
        lp.gravity = Gravity.CENTER_VERTICAL;
        btn.setLayoutParams(lp);
        btn.setVisibility(buttonVisibility());
        btn.setOnClickListener(v -> show(v));
        try { target.addView(btn, Math.min(insertAt, target.getChildCount())); }
        catch (Throwable t) { target.addView(btn); }
        lastHeader = new WeakReference<>(header);
        ModuleLog.probe("(IE|ThreadMenu) button injected in " + target.getClass().getSimpleName()
                + (FeatureFlags.hideChatMenuButton ? " (hidden)" : ""));
        return btn;
    }

    /** The view with {@code id} that is actually on screen; a hidden cached chat can hold another. */
    static View findShown(View root, int id) {
        if (root == null || id == 0 || !root.isShown()) return null;
        if (root.getId() == id) return root;
        if (root instanceof ViewGroup group) {
            for (int i = 0; i < group.getChildCount(); i++) {
                View match = findShown(group.getChildAt(i), id);
                if (match != null) return match;
            }
        }
        return null;
    }

    private static void show(View btn) {
        Map<String, Entry> m = entriesOf(btn);
        if (m == null || m.isEmpty()) return;
        java.util.List<Entry> list = new java.util.ArrayList<>();
        for (String k : ORDER) if (m.containsKey(k)) list.add(m.get(k));
        for (Map.Entry<String, Entry> e : m.entrySet())
            if (!java.util.Arrays.asList(ORDER).contains(e.getKey())) list.add(e.getValue());
        CharSequence[] labels = new CharSequence[list.size()];
        for (int i = 0; i < list.size(); i++) labels[i] = list.get(i).label();
        new IeDialog(btn.getContext())
                .title(I18n.t(btn.getContext(), R.string.ig_thread_menu))
                .choices(labels, -1, which -> {
                    try { list.get(which).action().run(); }
                    catch (Throwable t) { ModuleLog.error("(IE|ThreadMenu) A chat menu action failed", t); }
                })
                .show();
    }

    /** Hooks Instagram's touch dispatch for the hidden-button gesture (the option is checked per
     *  touch, so it applies without a restart) and Jump to first's scroll-to-stop. Both activities
     *  share IgFragmentActivity's method. */
    static void installTapGesture(ClassLoader loader) {
        java.util.Set<Method> hooked = new java.util.HashSet<>();
        for (String name : new String[]{"com.instagram.mainactivity.InstagramMainActivity",
                "com.instagram.modal.ModalActivity"}) {
            try {
                Method dispatch = Class.forName(name, false, loader).getMethod("dispatchTouchEvent", MotionEvent.class);
                if (hooked.contains(dispatch)) continue;
                XposedBridge.hookMethod(dispatch, new XC_MethodHook() {
                    @Override protected void beforeHookedMethod(MethodHookParam param) {
                        JitGuard.tick(param.method);
                        if (!(param.thisObject instanceof Activity a)) return;
                        MotionEvent event = (MotionEvent) param.args[0];
                        // Sees every touch, swallowed below or not; returns at once without a jump.
                        JumpToFirstMessageHook.onTouch(a, event);
                        // Not gated on the option: switched off while a chat stays open, its
                        // hidden button must still come back (see gapButton).
                        if (!replaying && onTouch(a, event)) param.setResult(true);
                    }
                });
                hooked.add(dispatch);
            } catch (Throwable t) {
                ModuleLog.detail("(IE|ThreadMenu) tap gesture " + name + " failed: " + t);
            }
        }
        if (!hooked.isEmpty()) FeatureStatusTracker.setHooked("ChatMenuTripleTap");
        ModuleLog.line("(IE|ThreadMenu) " + (hooked.isEmpty()
                ? "❌ Triple tap on the chat header is not available, keep the chat menu button visible" : "✅ ready"));
        ModuleLog.detail("(IE|ThreadMenu) tap gesture hooked " + hooked.size() + " method(s)");
    }

    /**
     * True when the event is part of a touch in the header gap, which Instagram must not see yet.
     * The gap belongs to the clickable title container (it fills the header up to the call
     * buttons), so a tap there opens the chat details: each held tap is replayed unchanged once no
     * further tap follows within {@link #REPLAY_DELAY_MS}, and only the third quick tap opens the menu.
     */
    private static boolean onTouch(Activity a, MotionEvent e) {
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN -> {
                dropReplay();
                MenuButton btn = gapButton(a, e.getRawX(), e.getRawY());
                tracking = btn != null;
                if (!tracking) {
                    gapTaps.reset();
                    return false;
                }
                heldButton = new WeakReference<>(btn);
                recycle(heldDown);
                heldDown = MotionEvent.obtain(e);
                stillTap = true;
                return true;
            }
            case MotionEvent.ACTION_MOVE -> {
                if (tracking && heldDown != null && Math.hypot(e.getRawX() - heldDown.getRawX(),
                        e.getRawY() - heldDown.getRawY()) > ViewConfiguration.get(a).getScaledTouchSlop()) {
                    stillTap = false;
                }
            }
            case MotionEvent.ACTION_POINTER_DOWN -> stillTap = false;
            case MotionEvent.ACTION_CANCEL -> {
                boolean wasTracking = tracking;
                tracking = false;
                recycle(heldDown);
                heldDown = null;
                gapTaps.reset();
                return wasTracking;
            }
            case MotionEvent.ACTION_UP -> {
                if (!tracking) return false;
                tracking = false;
                MotionEvent down = heldDown;
                heldDown = null;
                MenuButton btn = heldButton != null ? heldButton.get() : null;
                if (down == null || btn == null || !stillTap
                        || e.getEventTime() - e.getDownTime() > ViewConfiguration.getLongPressTimeout()) {
                    recycle(down);
                    gapTaps.reset(); // a drag or long press in the gap is dropped, never replayed
                    return true;
                }
                if (gapTaps.tap(e.getEventTime())) {
                    recycle(down);
                    ModuleLog.detail("(IE|ThreadMenu) header triple tap opened the menu");
                    show(btn);
                    return true;
                }
                MotionEvent up = MotionEvent.obtain(e);
                replayDown = down;
                replayUp = up;
                WeakReference<View> header = lastHeader;
                pendingReplay = () -> {
                    pendingReplay = null;
                    replayDown = replayUp = null;
                    gapTaps.reset();
                    // Only into the same chat: after leaving it the spot holds something else.
                    View h = header != null ? header.get() : null;
                    try {
                        if (h == null || !h.isShown()) return;
                        replaying = true;
                        try {
                            a.dispatchTouchEvent(down);
                            a.dispatchTouchEvent(up);
                        } finally {
                            replaying = false;
                        }
                    } finally {
                        recycle(down);
                        recycle(up);
                    }
                };
                MAIN.postDelayed(pendingReplay, REPLAY_DELAY_MS);
                return true;
            }
            default -> {}
        }
        return tracking;
    }

    /** A new touch either continues the tap sequence or goes elsewhere: the held tap is not sent. */
    private static void dropReplay() {
        if (pendingReplay == null) return;
        MAIN.removeCallbacks(pendingReplay);
        pendingReplay = null;
        recycle(replayDown);
        recycle(replayUp);
        replayDown = replayUp = null;
    }

    /** Our MotionEvent.obtain copies go back to the pool once they are dropped or replayed. */
    private static void recycle(MotionEvent e) {
        if (e != null) e.recycle();
    }

    /**
     * The open chat's menu button when (x, y) lies in its header's free space after the name, and
     * the button is hidden. Also brings that button in line with the option, which can change
     * while the chat stays open (companion app, menu): switched off, the button comes back.
     */
    @SuppressLint("DiscouragedApi")
    private static MenuButton gapButton(Activity a, float x, float y) {
        if (headerId == 0) {
            String pkg = a.getPackageName();
            android.content.res.Resources res = a.getResources();
            headerId = res.getIdentifier("direct_thread_header", "id", pkg);
            titleBoxId = res.getIdentifier("header_title_subtitle_container", "id", pkg);
            endButtonsId = res.getIdentifier("header_right_buttons", "id", pkg);
        }
        View header = lastHeader != null ? lastHeader.get() : null;
        if (header == null || !header.isShown()) {
            // Every touch in Instagram lands here: walk the views only for the gesture, while a
            // chat is open. A button the option hid is in lastHeader (set where it was found).
            if (!FeatureFlags.hideChatMenuButton || CurrentThreadTracker.currentThreadId() == null) return null;
            header = findShown(a.getWindow().getDecorView(), headerId);
            if (header == null) return null;
            lastHeader = new WeakReference<>(header);
        }
        if (!(header.findViewWithTag(TAG) instanceof MenuButton btn) || btn.entries.isEmpty()) return null;
        int visibility = buttonVisibility();
        if (btn.getVisibility() != visibility) {
            // The header re-lays out around the button, so this touch goes to Instagram as is.
            btn.setVisibility(visibility);
            return null;
        }
        if (visibility != View.GONE) return null;
        int[] loc = new int[2];
        header.getLocationOnScreen(loc);
        if (y < loc[1] || y >= loc[1] + header.getHeight()) return null;
        View titleBox = findShown(header, titleBoxId);
        if (titleBox == null) return null;
        boolean rtl = header.getLayoutDirection() == View.LAYOUT_DIRECTION_RTL;
        int barLeft = loc[0], barRight = loc[0] + header.getWidth();
        View end = findShown(header, endButtonsId);
        if (end != null && end.getWidth() > 0) {
            end.getLocationOnScreen(loc);
            if (rtl) barLeft = loc[0] + end.getWidth();
            else barRight = loc[0];
        }
        int[] name = {Integer.MAX_VALUE, Integer.MIN_VALUE};
        int boxWidth = titleBox.getWidth() - titleBox.getPaddingLeft() - titleBox.getPaddingRight();
        drawnExtent(titleBox, boxWidth, name, loc);
        if (name[0] > name[1]) return null; // nothing drawn yet: no name to measure from
        float d = a.getResources().getDisplayMetrics().density;
        int[] span = HeaderTapZone.span(name[0], name[1], barLeft, barRight, rtl, (int) (8 * d), (int) (24 * d));
        return span != null && x >= span[0] && x < span[1] ? btn : null;
    }

    /**
     * Widens ext to the screen x-range v draws: its visible leaf views, a TextView only its text.
     * The username / "Active now" line is a plain View as wide as the name box (boxWidth) with its
     * text in a background Drawable, so it is left out (HeaderTapZone.boundsShowName).
     */
    private static void drawnExtent(View v, int boxWidth, int[] ext, int[] loc) {
        if (v.getVisibility() != View.VISIBLE || v.getWidth() == 0) return;
        if (v instanceof ViewGroup g) {
            for (int i = 0; i < g.getChildCount(); i++) drawnExtent(g.getChildAt(i), boxWidth, ext, loc);
            return;
        }
        boolean textOrImage = v instanceof TextView || v instanceof android.widget.ImageView;
        if (!HeaderTapZone.boundsShowName(textOrImage, v.getWidth(), boxWidth)) return;
        v.getLocationOnScreen(loc);
        int left = loc[0], right = loc[0] + v.getWidth();
        // A title stretched across the header draws its text at one end; the rest is free space.
        // Side icons (a verified badge) sit at the view's edges, so then the whole view counts.
        Layout text = v instanceof TextView t ? t.getLayout() : null;
        if (text != null && text.getLineCount() > 0) {
            TextView t = (TextView) v;
            Drawable[] icons = t.getCompoundDrawables();
            if (icons[0] == null && icons[2] == null) {
                float lo = Float.MAX_VALUE, hi = -Float.MAX_VALUE;
                for (int i = 0; i < text.getLineCount(); i++) {
                    lo = Math.min(lo, text.getLineLeft(i));
                    hi = Math.max(hi, text.getLineRight(i));
                }
                int base = left + t.getCompoundPaddingLeft() - t.getScrollX();
                left = Math.max(left, base + (int) Math.floor(lo));
                right = Math.min(right, base + (int) Math.ceil(hi));
            }
        }
        ext[0] = Math.min(ext[0], left);
        ext[1] = Math.max(ext[1], right);
    }
}
