package ps.reso.instaeclipse.mods.misc;

import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.ColorFilter;
import android.graphics.drawable.Drawable;
import android.os.Handler;
import android.os.Looper;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.RelativeLayout;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;

import ps.reso.instaeclipse.R;
import ps.reso.instaeclipse.utils.dialog.IeDialog;
import ps.reso.instaeclipse.utils.feature.FeatureFlags;
import ps.reso.instaeclipse.utils.i18n.I18n;
import ps.reso.instaeclipse.utils.log.ModuleLog;
import ps.reso.instaeclipse.utils.toast.IeToast;
import ps.reso.instaeclipse.utils.ui.ResIds;
import ps.reso.instaeclipse.utils.users.WatchlistStore;

/**
 * The follow list screen's tools button, in place of the old floating pills: one magnifier at the
 * end of Instagram's action bar, vertically centred with the title (the profile's username).
 *
 * <p>Glyph: Instagram's own instagram_search_outline_24, the unselected icon of its Search /
 * Explore tab (tab_search_drawable), looked up by name. Tint: the bar's back arrow
 * (action_bar_button_back: its tint list or colour filter), else igds_color_primary_icon from the
 * theme, so it follows dark / light mode like Instagram's own action bar icons.
 *
 * <p>Host: the action bar above the list (id action_bar, the view of IG's ActionBarService X.00gD),
 * found from the list's own view upwards. Your own lists already have a right-side button row
 * (action_bar_right_side_buttons, Discover people): the icon goes first in it. Otherwise it goes
 * before the bar's last child, where Instagram puts its own right-side buttons (X.00gD.A0U), and
 * after layout is shifted to the bar's end edge and to the title's vertical centre. When a screen
 * configures the bar, Instagram drops the views before that last child (X.00gD.A18) and clears
 * the buttons' click listeners (X.00gD.A0G), so a layout listener on the bar puts the icon and its
 * listener back while a follow list screen is shown and removes the icon once none is. It is
 * looked up by tag first, so it is never added twice. A follow list screen is a FollowListFragment
 * whose parent fragment holds the tabs' ViewPager2 and tab list (UnifiedFollowFragment), so
 * follow lists in sheets don't get it; with only Followers history on, only your own lists do.
 *
 * <p>Tap: the enabled actions that apply to the tab on screen (FollowListPolicy.actions): Analyze
 * profile, Export CSV, and Followers history on your own Followers tab, plus Changelog after
 * Analyze once the analyzer saved a check of the list's profile (ProfileChangelog; the watchlist
 * index is read off the main thread first). One runs at once; several open a small menu. While
 * loading lists pauses for the account after Instagram blocked a load (rate limit, action block,
 * challenge: FollowListLoader.pause), Analyze, Export CSV and Followers history first show what
 * Instagram said and run only on "Load anyway"; nothing runs by itself when the pause ends.
 */
final class FollowListToolsIcon {

    private static final String TAG = "(IE|FollowTools) ";
    static final String ICON_TAG = "ie_follow_tools";
    /** bars with our layout listener, and bars with a sync already posted by it */
    private static final Set<View> watchedBars = Collections.newSetFromMap(new WeakHashMap<>());
    private static final Set<View> queuedBars = Collections.newSetFromMap(new WeakHashMap<>());
    /** list fragment → {SCREEN (it sits in the tabbed follow list screen) | KNOWN (whose list it is
     *  is known) | OWN (one of your own lists), when that was checked} */
    private static final Map<Object, long[]> screens = Collections.synchronizedMap(new WeakHashMap<>());
    private static final int SCREEN = 1, KNOWN = 2, OWN = 4;
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static boolean loggedNoBar;

    private FollowListToolsIcon() {}

    static boolean enabled() {
        return FeatureFlags.profileAnalyzer || FeatureFlags.followListExport || FeatureFlags.followersHistory;
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
            if (watchedBars.add(bar))
                bar.getViewTreeObserver().addOnGlobalLayoutListener(() -> {
                    // fires on every layout of the window: one queued sync per bar
                    if (queuedBars.add(bar)) bar.post(() -> {
                        queuedBars.remove(bar);
                        sync(bar);
                    });
                });
            sync(bar);
        } catch (Throwable t) {
            ModuleLog.detail(TAG + "attach: " + t);
        }
    }

    /** The shown action_bar nearest above {@code v} (each tab of Instagram's main screen has its own). */
    private static ViewGroup barAbove(View v) {
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

    /** A follow list tab of the tabbed follow list screen is shown in {@code bar}'s window, below it,
     *  and one of the enabled actions can apply there (Followers history only on your own lists). */
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
                int f = (FollowListExportHook.tabsOf(e[0]) != null ? SCREEN : 0) | (info != null ? KNOWN : 0)
                        | (info != null && info.owner.equals(info.self) ? OWN : 0);
                screens.put(e[0], s = new long[]{f, now});
            }
            long f = s[0];
            if ((f & SCREEN) == 0) continue;
            if (FeatureFlags.followListExport || (FeatureFlags.profileAnalyzer && (f & KNOWN) != 0)
                    || (FeatureFlags.followersHistory && (f & OWN) != 0)) return true;
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
    private static TextView title(ViewGroup bar) {
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

    private static ImageView firstImage(ViewGroup g) {
        for (int i = 0; i < g.getChildCount(); i++) {
            View c = g.getChildAt(i);
            if (c instanceof ImageView iv) return iv;
            ImageView inner = c instanceof ViewGroup cg ? firstImage(cg) : null;
            if (inner != null) return inner;
        }
        return null;
    }

    @android.annotation.SuppressLint("UseCompatLoadingForDrawables")
    private static ImageView newIcon(Context ctx, ViewGroup bar) {
        ImageView icon = new ImageView(ctx);
        icon.setTag(ICON_TAG);
        Drawable glyph = null;
        for (String name : new String[]{"instagram_search_outline_24", "instagram_search_pano_outline_24", "tab_search_drawable"}) {
            int id = ResIds.drawable(ctx, name);
            if (id == 0) continue;
            try { glyph = ctx.getDrawable(id); } catch (Throwable ignored) {}
            if (glyph != null) break;
        }
        if (glyph == null) glyph = ctx.getDrawable(android.R.drawable.ic_menu_search);
        if (glyph != null) glyph = glyph.mutate();
        icon.setImageDrawable(glyph);
        icon.setScaleType(ImageView.ScaleType.CENTER); // the 24dp glyph in the middle of the touch target
        tint(icon, bar);
        TypedValue ripple = new TypedValue();
        if (ctx.getTheme().resolveAttribute(android.R.attr.selectableItemBackgroundBorderless, ripple, true) && ripple.resourceId != 0) {
            try { icon.setBackground(ctx.getDrawable(ripple.resourceId)); } catch (Throwable ignored) {}
        }
        List<String> labels = new ArrayList<>();
        if (FeatureFlags.profileAnalyzer) {
            labels.add(I18n.t(ctx, R.string.ig_pa_analyze));
            labels.add(I18n.t(ctx, R.string.ig_pa_changelog));
        }
        if (FeatureFlags.followListExport) labels.add(I18n.t(ctx, R.string.ig_follow_export));
        if (FeatureFlags.followersHistory) labels.add(I18n.t(ctx, R.string.ig_followers_history));
        String label = String.join(" · ", labels);
        icon.setContentDescription(label);
        icon.setTooltipText(label);
        icon.setOnClickListener(FollowListToolsIcon::onTap);
        return icon;
    }

    /** Same colour as the bar's back arrow (Instagram colours it through its tint, the view's colour
     *  filter or the drawable's), else the theme's primary icon colour. */
    private static void tint(ImageView icon, ViewGroup bar) {
        View b = back(bar);
        ImageView back = b instanceof ImageView iv ? iv : b instanceof ViewGroup g ? firstImage(g) : null;
        if (back != null) {
            ColorStateList list = back.getImageTintList();
            if (list != null) { icon.setImageTintList(list); return; }
            ColorFilter filter = back.getColorFilter();
            if (filter == null && back.getDrawable() != null) filter = back.getDrawable().getColorFilter();
            if (filter != null) { icon.setColorFilter(filter); return; }
        }
        Context c = bar.getContext();
        int color;
        try {
            @android.annotation.SuppressLint("DiscouragedApi")
            int attr = c.getResources().getIdentifier("igds_color_primary_icon", "attr", c.getPackageName());
            TypedValue v = new TypedValue();
            if (attr != 0 && c.getTheme().resolveAttribute(attr, v, true)) {
                color = v.resourceId != 0 ? c.getColor(v.resourceId) : v.data;
                icon.setImageTintList(ColorStateList.valueOf(color));
                return;
            }
        } catch (Throwable ignored) {}
        boolean night = (c.getResources().getConfiguration().uiMode
                & android.content.res.Configuration.UI_MODE_NIGHT_MASK) == android.content.res.Configuration.UI_MODE_NIGHT_YES;
        icon.setImageTintList(ColorStateList.valueOf(night ? 0xFFF5F5F5 : 0xFF000000));
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
                    FeatureFlags.followListExport, FeatureFlags.followersHistory, info != null, own);
            ModuleLog.detail(TAG + "tap: " + actions + (info != null ? " on " + info.type + " of " + info.owner : ""));
            if (actions.isEmpty()) {
                int msg = FeatureFlags.followersHistory ? R.string.ig_followers_history_wrong_tab
                        : FeatureFlags.profileAnalyzer ? R.string.ig_pa_wrong_tab : R.string.ig_follow_export_none;
                IeToast.makeText(ctx, I18n.t(ctx, msg), Toast.LENGTH_SHORT).show();
                return;
            }
            String name = info != null && info.username != null ? info.username : ProfileAnalyzer.titleOf(v);
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
                    MAIN.post(() -> menu(v, frag, root, info, actions, changelogSelf, name));
                }, "ie-follow-tools").start();
                return;
            }
            menu(v, frag, root, info, actions, null, name);
        } catch (Throwable t) {
            ModuleLog.error(TAG + "Could not open the list tools", t);
        }
    }

    /** Runs the only entry, or offers them all in a small menu. {@code changelogSelf}: the account
     *  whose watchlist has a saved check of the list's profile (Changelog comes after Analyze), or null. */
    private static void menu(View v, Object frag, View root, FollowListExportHook.ListInfo info,
                             List<FollowListPolicy.Action> actions, String changelogSelf, String name) {
        try {
            Context ctx = v.getContext();
            List<CharSequence> labels = new ArrayList<>();
            List<Runnable> runs = new ArrayList<>();
            for (FollowListPolicy.Action a : actions) {
                labels.add(I18n.t(ctx, switch (a) {
                    case ANALYZE -> R.string.ig_pa_analyze;
                    case EXPORT -> R.string.ig_follow_export;
                    case HISTORY -> R.string.ig_followers_history;
                }));
                runs.add(() -> run(a, v, frag, root, info));
                if (a == FollowListPolicy.Action.ANALYZE && changelogSelf != null) {
                    labels.add(I18n.t(ctx, R.string.ig_pa_changelog));
                    runs.add(() -> ProfileChangelog.show(ctx, changelogSelf, info.owner, name));
                }
            }
            if (changelogSelf != null) ModuleLog.detail(TAG + "changelog offered for " + info.owner);
            if (runs.size() == 1) {
                runs.get(0).run();
                return;
            }
            new IeDialog(ctx).title(name != null ? "@" + name : "")
                    .choices(labels.toArray(new CharSequence[0]), -1, i -> runs.get(i).run())
                    .show();
        } catch (Throwable t) {
            ModuleLog.error(TAG + "Could not show the list tools menu", t);
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
            case HISTORY -> FollowListExportHook.checkHistory(root, frag);
        }
    }
}
