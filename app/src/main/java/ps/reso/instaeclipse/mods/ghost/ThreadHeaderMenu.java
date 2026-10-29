package ps.reso.instaeclipse.mods.ghost;

import android.annotation.SuppressLint;
import android.app.Activity;
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
import java.util.WeakHashMap;

import ps.reso.instaeclipse.xposedcompat.XC_MethodHook;
import ps.reso.instaeclipse.xposedcompat.XposedBridge;
import ps.reso.instaeclipse.R;
import ps.reso.instaeclipse.utils.core.ModuleResources;
import ps.reso.instaeclipse.utils.dialog.IeDialog;
import ps.reso.instaeclipse.utils.feature.FeatureFlags;
import ps.reso.instaeclipse.utils.feature.FeatureStatusTracker;
import ps.reso.instaeclipse.utils.ghost.CurrentThreadTracker;
import ps.reso.instaeclipse.utils.i18n.I18n;
import ps.reso.instaeclipse.utils.log.ModuleLog;
import ps.reso.instaeclipse.utils.ui.IgTint;
import ps.reso.instaeclipse.utils.ui.ResIds;

final class ThreadHeaderMenu {
    static final String TAG = "ie_thread_menu";
    static final String[] ORDER = {"unsent", "export", "jump", "read", "hide", "lock"};
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static final long REPLAY_DELAY_MS = 400;

    private record Entry(CharSequence label, Runnable action) {}

    @SuppressLint({"AppCompatCustomView", "ViewConstructor"})
    private static final class MenuButton extends android.widget.ImageView {
        final Map<String, Entry> entries = new LinkedHashMap<>();
        WeakReference<View> tintFrom;

        MenuButton(android.content.Context c) { super(c); }

        void tint() {
            IgTint.copy(tintFrom != null ? tintFrom.get() : null, this);
        }
    }

    private static final QuickTapSequence gapTaps = new QuickTapSequence();
    private static WeakReference<View> lastHeader;
    private static WeakReference<MenuButton> heldButton;
    private static MotionEvent heldDown;
    private static MotionEvent replayDown, replayUp;
    private static Runnable pendingReplay;
    private static boolean tracking, stillTap, replaying;
    private static int headerId, titleBoxId, endButtonsId;
    private static final Map<Activity, WeakReference<View>> shownHeaders = new WeakHashMap<>();
    private static final Map<Activity, WeakReference<MenuButton>> shownButtons = new WeakHashMap<>();
    private static int revision;

    private static Map<String, Entry> entriesOf(View btn) {
        return btn instanceof MenuButton b ? b.entries : null;
    }

    private ThreadHeaderMenu() {}

    private static MenuButton shownButton(Activity a) {
        MenuButton cached = attached(shownButtons.get(a));
        if (cached != null && cached.getParent() instanceof View parent && parent.isShown()) return cached;
        View decor = a.getWindow().getDecorView();
        MenuButton found = decor.isShown() ? shownButton(decor) : null;
        remember(shownButtons, a, found);
        return found;
    }

    static View shownHeader(Activity a) {
        View cached = attached(shownHeaders.get(a));
        if (cached != null && cached.isShown()) return cached;
        View found = findShown(a.getWindow().getDecorView(), ResIds.id(a, "direct_thread_header"));
        remember(shownHeaders, a, found);
        return found;
    }

    private static <T extends View> T attached(WeakReference<T> reference) {
        T view = reference == null ? null : reference.get();
        return view != null && view.isAttachedToWindow() ? view : null;
    }

    private static <T extends View> void remember(Map<Activity, WeakReference<T>> cache, Activity a, T view) {
        if (view == null) cache.remove(a);
        else cache.put(a, new WeakReference<>(view));
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

    static boolean has(Activity a, String key) {
        Map<String, Entry> m = entriesOf(shownButton(a));
        return m != null && m.containsKey(key);
    }

    static int revision() {
        return revision;
    }

    static boolean hasOthers(Activity a, String key) {
        Map<String, Entry> m = entriesOf(shownButton(a));
        return m != null && m.size() > (m.containsKey(key) ? 1 : 0);
    }

    static boolean add(Activity a, String key, CharSequence label, Runnable action) {
        try {
            View btn = shownButton(a);
            if (btn == null) btn = createButton(a);
            if (btn == null) return false;
            Map<String, Entry> m = entriesOf(btn);
            if (m == null) return false;
            m.put(key, new Entry(label, action));
            revision++;
            if (btn instanceof MenuButton b) b.tint();
            btn.setVisibility(buttonVisibility());
            return true;
        } catch (Throwable t) {
            ModuleLog.warn("(IE|ThreadMenu) Could not add \"" + key + "\" to the chat menu", t);
            return false;
        }
    }

    static void remove(Activity a, String key) {
        View btn = shownButton(a);
        Map<String, Entry> m = entriesOf(btn);
        if (m == null || m.remove(key) == null) return;
        revision++;
        if (!m.isEmpty()) return;
        if (btn.getParent() instanceof ViewGroup g) g.removeView(btn);
    }

    static void reopen(Activity a) {
        try {
            View btn = shownButton(a);
            if (btn != null) show(btn);
        } catch (Throwable ignored) { }
    }

    private static int buttonVisibility() {
        return FeatureFlags.hideChatMenuButton ? View.GONE : View.VISIBLE;
    }

    private static View createButton(Activity a) {
        View decor = a.getWindow().getDecorView();
        View header = shownHeader(a);
        if (header == null) return null;
        ViewGroup target;
        int insertAt;
        View back = findShown(header, ResIds.id(a, "header_left_button"));
        int backId = ResIds.id(a, "action_bar_button_back");
        for (View scope = header; scope != null && scope != decor && back == null;
             scope = scope.getParent() instanceof View p ? p : null) back = findShown(scope, backId);
        if (back != null && back.getParent() instanceof ViewGroup g) {
            target = g;
            int bi = g.indexOfChild(back);
            insertAt = g.getLayoutDirection() == View.LAYOUT_DIRECTION_RTL ? bi : bi + 1;
        } else if (header instanceof ViewGroup g) {
            target = g;
            insertAt = 0;
        } else return null;

        View btn = button(a, back != null ? back : header);
        try { target.addView(btn, Math.min(insertAt, target.getChildCount())); }
        catch (Throwable t) { target.addView(btn); }
        lastHeader = new WeakReference<>(header);
        ModuleLog.probe("(IE|ThreadMenu) button injected in " + target.getClass().getSimpleName()
                + (FeatureFlags.hideChatMenuButton ? " (hidden)" : ""));
        return btn;
    }

    static android.widget.ImageView button(Activity a, View tintFrom) {
        MenuButton btn = new MenuButton(a);
        btn.setTag(TAG);
        Drawable icon = ModuleResources.icon(a, R.drawable.ic_tune);
        if (icon != null) btn.setImageDrawable(icon);
        btn.tintFrom = new WeakReference<>(tintFrom);
        btn.tint();
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
        return btn;
    }

    static View findShown(View root, int id) {
        if (root == null || id == 0 || !root.isShown()) return null;
        return findVisible(root, id);
    }

    private static View findVisible(View view, int id) {
        if (view.getId() == id) return view;
        if (view instanceof ViewGroup group) {
            for (int i = 0; i < group.getChildCount(); i++) {
                View child = group.getChildAt(i);
                if (child.getVisibility() != View.VISIBLE) continue;
                View match = findVisible(child, id);
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

    static void installTapGesture(ClassLoader loader) {
        java.util.Set<Method> hooked = new java.util.HashSet<>();
        for (String name : new String[]{"com.instagram.mainactivity.InstagramMainActivity",
                "com.instagram.modal.ModalActivity"}) {
            try {
                Method dispatch = Class.forName(name, false, loader).getMethod("dispatchTouchEvent", MotionEvent.class);
                if (hooked.contains(dispatch)) continue;
                XposedBridge.hookMethod(dispatch, new XC_MethodHook() {
                    @Override protected void beforeHookedMethod(MethodHookParam param) {
                        if (!(param.thisObject instanceof Activity a)) return;
                        MotionEvent event = (MotionEvent) param.args[0];
                        JumpToFirstMessageHook.onTouch(a, event);
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
                    gapTaps.reset();
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

    private static void dropReplay() {
        if (pendingReplay == null) return;
        MAIN.removeCallbacks(pendingReplay);
        pendingReplay = null;
        recycle(replayDown);
        recycle(replayUp);
        replayDown = replayUp = null;
    }

    private static void recycle(MotionEvent e) {
        if (e != null) e.recycle();
    }

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
            if (!FeatureFlags.hideChatMenuButton || CurrentThreadTracker.currentThreadId() == null) return null;
            header = findShown(a.getWindow().getDecorView(), headerId);
            if (header == null) return null;
            lastHeader = new WeakReference<>(header);
        }
        if (!(header.findViewWithTag(TAG) instanceof MenuButton btn) || btn.entries.isEmpty()) return null;
        int visibility = buttonVisibility();
        if (btn.getVisibility() != visibility) {
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
        if (name[0] > name[1]) return null;
        float d = a.getResources().getDisplayMetrics().density;
        int[] span = HeaderTapZone.span(name[0], name[1], barLeft, barRight, rtl, (int) (8 * d), (int) (24 * d));
        return span != null && x >= span[0] && x < span[1] ? btn : null;
    }

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
