package ps.reso.instaeclipse.mods.misc;

import android.content.Context;
import android.graphics.drawable.Drawable;
import android.os.Handler;
import android.os.Looper;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewTreeObserver;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.RelativeLayout;
import android.widget.TextView;
import android.widget.Toast;

import java.lang.ref.WeakReference;
import java.text.NumberFormat;
import java.util.ArrayDeque;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;
import java.util.function.IntConsumer;

import ps.reso.instaeclipse.R;
import ps.reso.instaeclipse.utils.dialog.IeDialog;
import ps.reso.instaeclipse.utils.feature.FeatureFlags;
import ps.reso.instaeclipse.utils.i18n.I18n;
import ps.reso.instaeclipse.utils.log.ModuleLog;
import ps.reso.instaeclipse.utils.toast.IeToast;
import ps.reso.instaeclipse.utils.ui.IgTint;
import ps.reso.instaeclipse.utils.ui.ResIds;
import ps.reso.instaeclipse.utils.users.WatchlistStore;

final class FollowListToolsIcon {

    private static final String TAG = "(IE|FollowTools) ";
    static final String ICON_TAG = "ie_follow_tools";
    private static final Map<View, BarWatch> watchedBars = new WeakHashMap<>();
    private static final Set<View> queuedBars = Collections.newSetFromMap(new WeakHashMap<>());
    private static final Map<Object, long[]> screens = Collections.synchronizedMap(new WeakHashMap<>());
    private static final int SCREEN = 1, KNOWN = 2;
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static boolean loggedNoBar;

    private FollowListToolsIcon() {}

    static boolean enabled() {
        return FeatureFlags.profileAnalyzer || FeatureFlags.followListExport;
    }

    /** A follow list tab's view was created: give its screen's action bar the icon. */
    static void attach(View listRoot) {
        FollowListLoader.warmPause(listRoot.getContext()); // read on tap
        attach(listRoot, 0);
    }

    private static void attach(View listRoot, int tries) {
        try {
            ViewGroup bar = barAbove(listRoot);
            if (bar == null) {
                // the tab's view may not be in the window yet
                if (tries < 5) listRoot.postDelayed(() -> attach(listRoot, tries + 1), 300);
                else if (!loggedNoBar) {
                    loggedNoBar = true;
                    ModuleLog.warn(TAG + "Could not place the tools icon: no title bar above this follower list");
                }
                return;
            }
            if (!watchedBars.containsKey(bar)) {
                BarWatch watch = new BarWatch(bar);
                watchedBars.put(bar, watch);
                bar.getViewTreeObserver().addOnGlobalLayoutListener(watch);
                ModuleLog.detail(TAG + "layout listeners: " + watchedBars.size());
            }
            sync(bar);
        } catch (Throwable t) {
            ModuleLog.detail(TAG + "attach: " + t);
        }
    }

    private static final class BarWatch implements ViewTreeObserver.OnGlobalLayoutListener {
        final WeakReference<ViewGroup> bar;

        BarWatch(ViewGroup bar) {
            this.bar = new WeakReference<>(bar);
        }

        @Override public void onGlobalLayout() {
            ViewGroup b = bar.get();
            if (b != null && queuedBars.add(b)) b.post(() -> {
                queuedBars.remove(b);
                sync(b);
            });
        }
    }

    private static void unwatch(ViewGroup bar) {
        BarWatch watch = watchedBars.remove(bar);
        if (watch == null) return;
        bar.getViewTreeObserver().removeOnGlobalLayoutListener(watch);
        ModuleLog.detail(TAG + "layout listeners: " + watchedBars.size());
    }

    /** The shown action_bar nearest above {@code v} (each tab of Instagram's main screen has its own). */
    static ViewGroup barAbove(View v) {
        int id = ResIds.id(v.getContext(), "action_bar");
        if (id == 0) return null;
        for (View p = v; p != null; p = p.getParent() instanceof View pv ? pv : null) {
            if (!(p instanceof ViewGroup g)) continue;
            View bar = g.findViewById(id);
            if (bar instanceof ViewGroup b && b.isShown()) return b;
        }
        return null;
    }

    /** Adds the icon while a follow list screen is shown under {@code bar}, removes it otherwise,
     *  and keeps it at the bar's end, level with the title. */
    private static void sync(ViewGroup bar) {
        try {
            View icon = bar.findViewWithTag(ICON_TAG);
            if (!enabled() || !screenShown(bar)) {
                if (icon != null && icon.getParent() instanceof ViewGroup p) p.removeView(icon);
                if (!listLive(bar)) unwatch(bar);
                return;
            }
            if (icon == null) icon = add(bar);
            // Instagram also clears the click listeners of a bar's buttons when it resets the bar
            if (!icon.hasOnClickListeners()) icon.setOnClickListener(FollowListToolsIcon::onTap);
            place(bar, icon);
        } catch (Throwable t) {
            ModuleLog.detail(TAG + "sync: " + t);
        }
    }

    private static boolean listLive(ViewGroup bar) {
        for (Object[] e : FollowListExportHook.liveLists()) {
            View root = (View) e[1];
            if (root.isAttachedToWindow() && root.getRootView() == bar.getRootView()) return true;
        }
        return false;
    }

    private static boolean screenShown(ViewGroup bar) {
        if (!bar.isShown()) return false;
        int[] at = new int[2];
        bar.getLocationInWindow(at);
        int barBottom = at[1] + bar.getHeight();
        for (Object[] e : FollowListExportHook.liveLists()) {
            View root = (View) e[1];
            if (!root.isShown() || root.getRootView() != bar.getRootView()) continue;
            root.getLocationInWindow(at);
            if (at[1] + root.getHeight() <= barBottom) continue;
            long[] s = screens.get(e[0]);
            long now = android.os.SystemClock.uptimeMillis();
            // a list not (yet) in the tabbed screen is looked at again after 2 s: its parent may
            // still be setting up its tabs
            if (s == null || ((s[0] & SCREEN) == 0 && now - s[1] > 2000)) {
                FollowListExportHook.ListInfo info = FollowListExportHook.listInfo(e[0]);
                int f = (FollowListExportHook.tabsOf(e[0]) != null ? SCREEN : 0) | (info != null ? KNOWN : 0);
                screens.put(e[0], s = new long[]{f, now});
            }
            long f = s[0];
            if ((f & SCREEN) == 0) continue;
            if (FeatureFlags.followListExport || (FeatureFlags.profileAnalyzer && (f & KNOWN) != 0)) return true;
        }
        return false;
    }

    private static View add(ViewGroup bar) {
        Context ctx = bar.getContext();
        ImageView icon = newIcon(ctx, bar);
        int size = buttonSize(bar);
        ViewGroup row = rightButtons(bar);
        if (row != null) {
            ViewGroup.LayoutParams lp = null;
            if (row.getChildCount() > 0) {
                ViewGroup.LayoutParams first = row.getChildAt(0).getLayoutParams();
                try { lp = first.getClass().getConstructor(first.getClass()).newInstance(first); } catch (Throwable ignored) {}
            }
            if (lp == null) lp = new LinearLayout.LayoutParams(size, ViewGroup.LayoutParams.MATCH_PARENT);
            row.addView(icon, 0, lp);
        } else {
            // Where Instagram's own right-side buttons go: at least two after the title container
            // (X.00gD.A0U: indexOfChild(title) + 2) and before the bar's last child, the views
            // Instagram keeps when it clears a bar (X.00gD.A18 drops everything in between).
            int at = bar.getChildCount() - 1;
            int titleId = ResIds.id(ctx, "action_bar_textview_custom_title_container");
            View titleBox = titleId != 0 ? bar.findViewById(titleId) : null;
            int t = titleBox != null && titleBox.getParent() == bar ? bar.indexOfChild(titleBox) : -1;
            if (t >= 0) at = Math.max(at, t + 2);
            bar.addView(icon, Math.max(0, Math.min(at, bar.getChildCount())), params(bar, size));
        }
        ModuleLog.detail(TAG + "tools icon in " + (row != null ? "the right-side buttons" : bar.getClass().getName()));
        return icon;
    }

    /** IG's row of right-side action buttons (created only when a screen has some). */
    private static ViewGroup rightButtons(ViewGroup bar) {
        int id = ResIds.id(bar.getContext(), "action_bar_right_side_buttons");
        View row = id != 0 ? bar.findViewById(id) : null;
        return row instanceof LinearLayout l && l.isShown() && l.getChildCount() > 0 ? l : null;
    }

    private static ViewGroup.LayoutParams params(ViewGroup bar, int size) {
        int h = ViewGroup.LayoutParams.MATCH_PARENT;
        if (bar instanceof LinearLayout) {
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(size, h);
            lp.gravity = Gravity.CENTER_VERTICAL;
            return lp;
        }
        if (bar instanceof FrameLayout) return new FrameLayout.LayoutParams(size, h, Gravity.END | Gravity.CENTER_VERTICAL);
        if (bar instanceof RelativeLayout) {
            RelativeLayout.LayoutParams lp = new RelativeLayout.LayoutParams(size, h);
            lp.addRule(RelativeLayout.ALIGN_PARENT_END);
            lp.addRule(RelativeLayout.CENTER_VERTICAL);
            return lp;
        }
        return new ViewGroup.MarginLayoutParams(size, h); // other layouts: placed by translation below
    }

    /** Moves the icon to the bar's end edge (only outwards, and never over a view shown after it)
     *  and level with the title's centre. Translation only: nothing is laid out again. */
    private static void place(ViewGroup bar, View icon) {
        if (icon.getWidth() == 0) return;
        if (icon.getParent() == bar && !(bar instanceof FrameLayout) && !(bar instanceof RelativeLayout)) {
            boolean rtl = bar.getLayoutDirection() == View.LAYOUT_DIRECTION_RTL;
            int limit = rtl ? bar.getPaddingLeft() : bar.getWidth() - bar.getPaddingRight();
            for (int i = bar.indexOfChild(icon) + 1; i < bar.getChildCount(); i++) {
                View c = bar.getChildAt(i);
                if (c.getVisibility() != View.VISIBLE || c.getWidth() == 0) continue;
                limit = rtl ? Math.max(limit, c.getRight()) : Math.min(limit, c.getLeft());
            }
            float dx = rtl ? Math.min(0, limit - icon.getLeft()) : Math.max(0, limit - icon.getRight());
            if (icon.getTranslationX() != dx) icon.setTranslationX(dx);
        }
        TextView title = title(bar);
        if (title == null) return;
        int[] t = new int[2], i = new int[2];
        title.getLocationInWindow(t);
        icon.getLocationInWindow(i);
        float iconCentre = i[1] - icon.getTranslationY() + icon.getHeight() / 2f;
        float dy = t[1] + title.getHeight() / 2f - iconCentre;
        if (Math.abs(dy) > bar.getHeight() / 2f) dy = 0; // not this bar's title row
        if (Math.abs(icon.getTranslationY() - dy) >= 1) icon.setTranslationY(dy);
    }

    /** The bar's title (the profile's username on follow list screens): a title id, else the shown
     *  text with the largest font in the bar. */
    static TextView title(ViewGroup bar) {
        for (String name : new String[]{"action_bar_title", "action_bar_textview_title", "action_bar_title_textview"}) {
            int id = ResIds.id(bar.getContext(), name);
            View v = id != 0 ? bar.findViewById(id) : null;
            if (v instanceof TextView tv && tv.isShown() && tv.length() > 0) return tv;
        }
        TextView best = null;
        ArrayDeque<View> st = new ArrayDeque<>();
        st.push(bar);
        while (!st.isEmpty()) {
            View v = st.pop();
            if (v instanceof TextView tv && tv.isShown() && tv.length() > 0 && !ICON_TAG.equals(tv.getTag())
                    && (best == null || tv.getTextSize() > best.getTextSize())) best = tv;
            if (v instanceof ViewGroup g) for (int i = 0; i < g.getChildCount(); i++) st.push(g.getChildAt(i));
        }
        return best;
    }

    private static int buttonSize(ViewGroup bar) {
        View back = back(bar);
        if (back != null && back.getWidth() > 0) return back.getWidth();
        return (int) (48 * bar.getResources().getDisplayMetrics().density);
    }

    private static View back(ViewGroup bar) {
        int id = ResIds.id(bar.getContext(), "action_bar_button_back");
        return id != 0 ? bar.findViewById(id) : null;
    }

    @android.annotation.SuppressLint("UseCompatLoadingForDrawables")
    private static ImageView newIcon(Context ctx, ViewGroup bar) {
        ImageView icon = new ImageView(ctx);
        icon.setTag(ICON_TAG);
        Drawable glyph = null;
        for (String name : new String[]{"instagram_insights_outline_24", "instagram_more_vertical_outline_24",
                "instagram_search_outline_24", "instagram_search_pano_outline_24", "tab_search_drawable"}) {
            int id = ResIds.drawable(ctx, name);
            if (id == 0) continue;
            try { glyph = ctx.getDrawable(id); } catch (Throwable ignored) {}
            if (glyph != null) break;
        }
        if (glyph == null) glyph = ctx.getDrawable(android.R.drawable.ic_menu_search);
        if (glyph != null) glyph = glyph.mutate();
        icon.setImageDrawable(glyph);
        icon.setScaleType(ImageView.ScaleType.CENTER); // the 24dp glyph in the middle of the touch target
        IgTint.copy(back(bar), icon);
        TypedValue ripple = new TypedValue();
        if (ctx.getTheme().resolveAttribute(android.R.attr.selectableItemBackgroundBorderless, ripple, true) && ripple.resourceId != 0) {
            try { icon.setBackground(ctx.getDrawable(ripple.resourceId)); } catch (Throwable ignored) {}
        }
        String label = I18n.t(ctx, R.string.ig_follow_tools);
        icon.setContentDescription(label);
        icon.setTooltipText(label);
        icon.setOnClickListener(FollowListToolsIcon::onTap);
        return icon;
    }

    // ---- tap ----

    private static void onTap(View v) {
        try {
            Context ctx = v.getContext();
            if (FollowListLoader.isLoading()) { FollowListLoader.stop(); return; }
            Object[] vis = FollowListExportHook.visibleList();
            if (vis == null) {
                IeToast.makeText(ctx, I18n.t(ctx, R.string.ig_follow_export_none), Toast.LENGTH_SHORT).show();
                return;
            }
            Object frag = vis[0];
            View root = (View) vis[1];
            FollowListExportHook.ListInfo info = FollowListExportHook.listInfo(frag);
            boolean own = FollowListExportHook.ownFollowersOf(frag) != null;
            List<FollowListPolicy.Action> actions = FollowListPolicy.actions(FeatureFlags.profileAnalyzer,
                    FeatureFlags.followListExport, FeatureFlags.profileAnalyzer, info != null, own);
            ModuleLog.detail(TAG + "tap: " + actions + (info != null ? " on " + info.type + " of " + info.owner : ""));
            if (actions.isEmpty()) {
                int msg = FeatureFlags.profileAnalyzer ? R.string.ig_pa_wrong_tab : R.string.ig_follow_export_none;
                IeToast.makeText(ctx, I18n.t(ctx, msg), Toast.LENGTH_SHORT).show();
                return;
            }
            String name = info != null && info.username != null ? info.username : ProfileAnalyzer.titleOf(v);
            String title = title(ctx, name, info, FollowListLoader.expectedCount(frag, info));
            if (actions.contains(FollowListPolicy.Action.ANALYZE)) {
                // Changelog only for a profile with a saved check: look in the watchlist index (a file) first
                Context app = ctx.getApplicationContext();
                new Thread(() -> {
                    String self = null;
                    long last = 0;
                    try {
                        self = info.self != null ? info.self : WatchlistStore.self(app);
                        if (self != null) last = ProfileChangelog.lastCheck(app, self, info.owner);
                    } catch (Throwable t) {
                        ModuleLog.detail(TAG + "watchlist: " + t);
                    }
                    String changelogSelf = ProfileChangelogPolicy.inMenu(FeatureFlags.profileAnalyzer, true, last) ? self : null;
                    MAIN.post(() -> menu(v, frag, root, info, actions, changelogSelf, name, title));
                }, "ie-follow-tools").start();
                return;
            }
            menu(v, frag, root, info, actions, null, name, title);
        } catch (Throwable t) {
            ModuleLog.error(TAG + "Could not open the list tools", t);
        }
    }

    private static void menu(View v, Object frag, View root, FollowListExportHook.ListInfo info,
                             List<FollowListPolicy.Action> actions, String changelogSelf, String name, String title) {
        try {
            Context ctx = v.getContext();
            List<FollowListPolicy.Action> rows = FollowListPolicy.menu(actions, changelogSelf != null);
            if (changelogSelf != null) ModuleLog.detail(TAG + "changelog offered for " + info.owner);
            actionMenu(ctx, title, rows, i -> {
                FollowListPolicy.Action a = rows.get(i);
                if (a == FollowListPolicy.Action.CHANGELOG) ProfileChangelog.show(ctx, changelogSelf, info.owner, name);
                else run(a, v, frag, root, info);
            }).show();
        } catch (Throwable t) {
            ModuleLog.error(TAG + "Could not show the list tools menu", t);
        }
    }

    static IeDialog actionMenu(Context ctx, String title, List<FollowListPolicy.Action> rows, IntConsumer pick) {
        CharSequence[] labels = new CharSequence[rows.size()];
        for (int i = 0; i < labels.length; i++) labels[i] = I18n.t(ctx, label(rows.get(i)));
        return new IeDialog(ctx).title(title).choices(labels, -1, pick);
    }

    private static int label(FollowListPolicy.Action a) {
        return switch (a) {
            case ANALYZE -> R.string.ig_pa_analyze;
            case CHANGELOG -> R.string.ig_pa_changelog;
            case EXPORT -> R.string.ig_follow_export;
            case HISTORY -> R.string.ig_pa_check_mine;
        };
    }

    static String title(Context ctx, String name, FollowListExportHook.ListInfo info, int count) {
        if (name == null) return I18n.t(ctx, R.string.ig_follow_tools);
        if (count < 0 || info == null || info.type == null) return "@" + name;
        return I18n.t(ctx, R.string.ig_follow_tools_title, name, ProfileAnalyzer.label(ctx, info.type),
                NumberFormat.getIntegerInstance(locale(ctx)).format(count));
    }

    private static Locale locale(Context ctx) {
        try {
            return ctx.getResources().getConfiguration().getLocales().get(0);
        } catch (Throwable t) {
            return Locale.getDefault();
        }
    }

    /** Runs {@code a}, after a warning when loading lists pauses for this account after Instagram
     *  blocked a load (FollowListLoader.pause): it only runs when the user picks "Load anyway". */
    private static void run(FollowListPolicy.Action a, View v, Object frag, View root, FollowListExportHook.ListInfo info) {
        FollowListPolicy.Block paused = FollowListLoader.pause(v.getContext(), info != null ? info.self : null);
        if (paused != null) {
            ModuleLog.detail(TAG + a + " during the pause after " + paused.kind + ": warned first");
            FollowListLoader.warnPaused(v.getContext(), paused, () -> start(a, v, frag, root, info));
            return;
        }
        start(a, v, frag, root, info);
    }

    private static void start(FollowListPolicy.Action a, View v, Object frag, View root, FollowListExportHook.ListInfo info) {
        switch (a) {
            case ANALYZE -> ProfileAnalyzer.startFromList(v, info);
            case EXPORT -> FollowListExportHook.exportList(root, frag);
            case HISTORY -> ProfileAnalyzer.checkMine(v, info);
            default -> { }
        }
    }
}
