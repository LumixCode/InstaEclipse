package ps.reso.instaeclipse.mods.ui;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.Context;
import android.content.res.Resources;
import android.util.SparseBooleanArray;
import android.view.View;
import android.view.ViewGroup;

import ps.reso.instaeclipse.utils.feature.FeatureFlags;
import ps.reso.instaeclipse.utils.log.ModuleLog;
import ps.reso.instaeclipse.utils.ui.ResIds;

public class DistractionFreeUIHook {

    private static volatile boolean idsResolved = false;
    private static int trayId = 0;
    private static int clipsTabId = 0;
    private static int tabPagerId = 0;

    private static final String[] NAV_TAB_NAMES =
            {"feed_tab", "clips_tab", "creation_tab", "direct_tab", "news_tab", "profile_tab"};
    private static final int[] navTabIds = new int[NAV_TAB_NAMES.length];
    private static final String[] PLUS_UPSELL_NAMES =
            {"aura_upsell_button", "aura_list_badge", "aura_list_badge_stub", "aura_badge_lock_icon", "aura_badge_chevron"};
    private static final int[] plusUpsellIds = new int[PLUS_UPSELL_NAMES.length];
    private static int discoverPeopleId = 0;
    private static int instantsId = 0;

    private static boolean navTabHidden(int index) {
        switch (index) {
            case 0: return FeatureFlags.hideTabHome;
            case 1: return FeatureFlags.hideTabReels;
            case 2: return FeatureFlags.hideTabCreate;
            case 3: return FeatureFlags.hideTabMessages;
            case 4: return FeatureFlags.hideTabActivity;
            case 5: return FeatureFlags.hideTabProfile;
            default: return false;
        }
    }

    private static boolean anyNavTabHidden() {
        for (int i = 0; i < NAV_TAB_NAMES.length; i++) if (navTabHidden(i)) return true;
        return false;
    }

    private static final java.util.Set<View> watchedDecors =
            java.util.Collections.newSetFromMap(new java.util.WeakHashMap<>());
    private static final java.util.Map<View, int[]> collapsedViews = new java.util.WeakHashMap<>();

    private static final long SWEEP_DEBOUNCE_MS = 400;
    private static final android.os.Handler MAIN = new android.os.Handler(android.os.Looper.getMainLooper());
    private static final java.util.Set<View> pendingSweeps =
            java.util.Collections.newSetFromMap(new java.util.WeakHashMap<>());

    private static final TabOrderPolicy tabOrder = new TabOrderPolicy();

    private static boolean anyFlag() {
        return FeatureFlags.disableStories || FeatureFlags.hideStoriesTray || FeatureFlags.disableReels || anyNavTabHidden() || FeatureFlags.hidePlusUpsells
                || tabOrder.sweeps(FeatureFlags.navTabOrder) || FeatureFlags.disableDiscoverPeople || FeatureFlags.hideInstants;
    }

    private static boolean needsSweep() {
        return anyFlag() || !collapsedViews.isEmpty();
    }

    @SuppressLint("DiscouragedApi")
    private static void ensureIds(Context a) {
        if (idsResolved) return;
        try {
            Resources res = a.getResources();
            String pkg = a.getPackageName();
            trayId = res.getIdentifier("reels_tray_container", "id", pkg);
            clipsTabId = res.getIdentifier("clips_tab", "id", pkg);
            tabPagerId = res.getIdentifier("swipeable_tab_view_pager", "id", pkg);
            for (int i = 0; i < NAV_TAB_NAMES.length; i++)
                navTabIds[i] = res.getIdentifier(NAV_TAB_NAMES[i], "id", pkg);
            for (int i = 0; i < PLUS_UPSELL_NAMES.length; i++)
                plusUpsellIds[i] = res.getIdentifier(PLUS_UPSELL_NAMES[i], "id", pkg);
            discoverPeopleId = res.getIdentifier("similar_accounts_container", "id", pkg);
            instantsId = res.getIdentifier("direct_quick_snap_consumption_preview", "id", pkg);
            idsResolved = true;
            ModuleLog.detail("(IE|DistractUI) resolved: tray=" + trayId + " clipsTab=" + clipsTabId);
        } catch (Throwable ignored) {}
    }

    public static void watchActivity(final Activity a) {
        if (a == null) return;
        try {
            ensureIds(a);
            if (needsSweep()) sweep(a);
            final View decor = a.getWindow() != null ? a.getWindow().getDecorView() : null;
            if (decor == null || !watchedDecors.add(decor)) return;
            decor.getViewTreeObserver().addOnGlobalLayoutListener(() -> {
                if (!needsSweep() || !pendingSweeps.add(decor)) return;
                MAIN.postDelayed(() -> {
                    pendingSweeps.remove(decor);
                    if (needsSweep()) sweep(a);
                }, SWEEP_DEBOUNCE_MS);
            });
        } catch (Throwable ignored) {}
    }

    private static void sweep(Activity a) {
        try {
            View root = a.getWindow() != null ? a.getWindow().getDecorView() : null;
            if (!(root instanceof ViewGroup)) return;
            SparseBooleanArray hide = new SparseBooleanArray();
            if (trayId != 0) hide.put(trayId, FeatureFlags.disableStories || FeatureFlags.hideStoriesTray);
            for (int i = 0; i < NAV_TAB_NAMES.length; i++)
                if (navTabIds[i] != 0) hide.put(navTabIds[i], navTabHidden(i));
            if (clipsTabId != 0) hide.put(clipsTabId, FeatureFlags.disableReels || FeatureFlags.hideTabReels);
            for (int id : plusUpsellIds) if (id != 0) hide.put(id, FeatureFlags.hidePlusUpsells);
            if (discoverPeopleId != 0) hide.put(discoverPeopleId, FeatureFlags.disableDiscoverPeople);
            if (instantsId != 0) hide.put(instantsId, FeatureFlags.hideInstants);
            collapseAllById((ViewGroup) root, hide);
            if (FeatureFlags.hideStoriesTray && trayId != 0)
                ps.reso.instaeclipse.utils.feature.FeatureStatusTracker.setHooked("hideStoriesTray");
            if (anyNavTabHidden()) ps.reso.instaeclipse.utils.feature.FeatureStatusTracker.setHooked("HideNavTabs");
            if (FeatureFlags.hidePlusUpsells) ps.reso.instaeclipse.utils.feature.FeatureStatusTracker.setHooked("HidePlusUpsells");
            if (FeatureFlags.hideInstants && instantsId != 0)
                ps.reso.instaeclipse.utils.feature.FeatureStatusTracker.setHooked("HideInstants");
            if (FeatureFlags.disableReels && tabPagerId != 0) disableTabSwipe((ViewGroup) root);
            String order = FeatureFlags.navTabOrder;
            if (tabOrder.sweeps(order)) {
                boolean custom = tabOrder.custom(order);
                if (reorderTabs(a, (ViewGroup) root, custom ? order : igTabOrder)) tabOrder.reordered(custom);
            }
        } catch (Throwable ignored) {}
    }

    public static final String[] ORDERABLE_TABS = TabOrderPolicy.ORDERABLE_TABS;
    private static boolean orderLogged;
    private static String igTabOrder;

    public static boolean isCustomTabOrder(String saved) {
        return TabOrderPolicy.isCustom(saved);
    }

    private static boolean reorderTabs(Activity a, ViewGroup root, String order) {
        int feedId = ResIds.id(a, "feed_tab");
        View feed = feedId != 0 ? root.findViewById(feedId) : null;
        if (feed == null || !(feed.getParent() instanceof ViewGroup bar)) return false;
        java.util.Map<String, View> tabs = new java.util.LinkedHashMap<>();
        java.util.List<Integer> slots = new java.util.ArrayList<>();
        for (int i = 0; i < bar.getChildCount(); i++) {
            View c = bar.getChildAt(i);
            if (c.getId() == View.NO_ID || (c.getId() >>> 24) == 0) continue;
            String name;
            try { name = a.getResources().getResourceEntryName(c.getId()); } catch (Throwable t) { continue; }
            if (java.util.Arrays.asList(ORDERABLE_TABS).contains(name)) { tabs.put(name, c); slots.add(i); }
        }
        if (tabs.size() < 2) return false;
        if (igTabOrder == null) igTabOrder = String.join(",", tabs.keySet());
        java.util.List<View> wanted = new java.util.ArrayList<>();
        for (String n : (order != null ? order : "").split(","))
            if (tabs.containsKey(n.trim()) && !wanted.contains(tabs.get(n.trim()))) wanted.add(tabs.get(n.trim()));
        for (View v : tabs.values()) if (!wanted.contains(v)) wanted.add(v);
        boolean same = true;
        for (int k = 0; k < slots.size(); k++) if (bar.getChildAt(slots.get(k)) != wanted.get(k)) { same = false; break; }
        if (same) return true;
        for (View v : wanted) bar.removeView(v);
        for (int k = 0; k < wanted.size(); k++) bar.addView(wanted.get(k), Math.min(slots.get(k), bar.getChildCount()));
        if (!orderLogged) {
            orderLogged = true;
            ModuleLog.line("(IE|DistractUI) Bottom tabs reordered: " + order);
        }
        return true;
    }

    private static boolean pagerTouchHooked;

    private static void hookPagerTouch(View pager) {
        if (pagerTouchHooked || !(pager instanceof ViewGroup g)) return;
        Class<?> inner = null;
        for (int i = 0; i < g.getChildCount() && inner == null; i++) {
            View c = g.getChildAt(i);
            if (c instanceof ViewGroup) inner = c.getClass();
        }
        if (inner == null) return;
        pagerTouchHooked = true;
        ps.reso.instaeclipse.xposedcompat.XC_MethodHook gate = new ps.reso.instaeclipse.xposedcompat.XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) {
                if (!FeatureFlags.disableReels) return;
                if (param.thisObject instanceof View self
                        && self.getParent() instanceof View p && p.getId() == tabPagerId) {
                    if (param.method.getName().equals("onInterceptTouchEvent")
                            && param.args[0] instanceof android.view.MotionEvent ev) trackSwipe(self, ev);
                    param.setResult(false);
                }
            }
        };
        int n = 0;
        for (String name : new String[]{"onInterceptTouchEvent", "onTouchEvent"}) {
            try {
                java.lang.reflect.Method m = inner.getDeclaredMethod(name, android.view.MotionEvent.class);
                ps.reso.instaeclipse.xposedcompat.XposedBridge.hookMethod(m, gate);
                n++;
            } catch (Throwable ignored) {}
        }
        ModuleLog.detail("(IE|DistractUI) main tab pager touch gate on " + inner.getName() + ": " + n);
    }

    private static float swipeDownX, swipeDownY;
    private static boolean swipeDone;

    private static void trackSwipe(View pagerChild, android.view.MotionEvent ev) {
        switch (ev.getActionMasked()) {
            case android.view.MotionEvent.ACTION_DOWN:
                swipeDownX = ev.getRawX();
                swipeDownY = ev.getRawY();
                swipeDone = false;
                break;
            case android.view.MotionEvent.ACTION_MOVE:
            case android.view.MotionEvent.ACTION_UP:
                if (swipeDone) return;
                float dx = ev.getRawX() - swipeDownX, dy = ev.getRawY() - swipeDownY;
                float min = 90 * pagerChild.getResources().getDisplayMetrics().density;
                if (Math.abs(dx) < min || Math.abs(dx) < 2.5f * Math.abs(dy)) return;
                swipeDone = true;
                View root = pagerChild.getRootView();
                View home = findTab(root, "feed_tab"), dm = findTab(root, "direct_tab");
                if (home == null || dm == null) return;
                if (dx < 0 && home.isSelected()) dm.post(dm::performClick);
                else if (dx > 0 && dm.isSelected()) home.post(home::performClick);
                break;
            default:
        }
    }

    private static View findTab(View root, String name) {
        int id = ResIds.id(root.getContext(), name);
        return id != 0 ? root.findViewById(id) : null;
    }

    private static boolean swipeLogged;

    private static void disableTabSwipe(ViewGroup root) {
        java.util.ArrayDeque<View> stack = new java.util.ArrayDeque<>();
        stack.push(root);
        while (!stack.isEmpty()) {
            View v = stack.pop();
            if (v.getId() == tabPagerId) {
                hookPagerTouch(v);
                try {
                    java.lang.reflect.Field f = ps.reso.instaeclipse.utils.ui.ViewPager2Input.field(v);
                    if (f != null && f.getBoolean(v)) {
                        f.setBoolean(v, false);
                        if (!swipeLogged) {
                            swipeLogged = true;
                            ModuleLog.detail("(IE|DistractUI) main tab swipe disabled (Reels off) via " + f.getName());
                        }
                    }
                } catch (Throwable ignored) {}
                continue;
            }
            if (v instanceof ViewGroup vg)
                for (int i = 0; i < vg.getChildCount(); i++) stack.push(vg.getChildAt(i));
        }
    }

    private static void collapseAllById(ViewGroup root, SparseBooleanArray hide) {
        java.util.ArrayDeque<View> stack = new java.util.ArrayDeque<>();
        stack.push(root);
        while (!stack.isEmpty()) {
            View v = stack.pop();
            int index = v.getId() == View.NO_ID ? -1 : hide.indexOfKey(v.getId());
            if (index >= 0) {
                if (hide.valueAt(index)) collapse(v);
                else restore(v);
            }
            if (v instanceof ViewGroup vg)
                for (int i = 0; i < vg.getChildCount(); i++) stack.push(vg.getChildAt(i));
        }
    }

    private static void collapse(View v) {
        if (v == null || v.getVisibility() == View.GONE) return;
        ViewGroup.LayoutParams lp = v.getLayoutParams();
        if (!collapsedViews.containsKey(v)) {
            collapsedViews.put(v, new int[]{v.getVisibility(), lp != null ? lp.width : -2, lp != null ? lp.height : -2});
        }
        v.setVisibility(View.GONE);
        if (lp != null) { lp.height = 0; lp.width = 0; v.setLayoutParams(lp); }
    }

    private static void restore(View view) {
        int[] original = collapsedViews.remove(view);
        if (original == null) return;
        view.setVisibility(original[0]);
        ViewGroup.LayoutParams lp = view.getLayoutParams();
        if (lp != null) { lp.width = original[1]; lp.height = original[2]; view.setLayoutParams(lp); }
    }
}
