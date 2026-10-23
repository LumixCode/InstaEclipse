package ps.reso.instaeclipse.mods.misc;

import android.app.Activity;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AbsListView;
import android.widget.TextView;
import android.widget.Toast;

import org.luckypray.dexkit.DexKitBridge;
import org.luckypray.dexkit.query.FindMethod;
import org.luckypray.dexkit.query.matchers.MethodMatcher;
import org.luckypray.dexkit.result.MethodData;

import java.io.OutputStream;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import ps.reso.instaeclipse.xposedcompat.XC_MethodHook;
import ps.reso.instaeclipse.xposedcompat.XposedBridge;
import ps.reso.instaeclipse.R;
import ps.reso.instaeclipse.utils.feature.FeatureFlags;
import ps.reso.instaeclipse.utils.feature.FeatureStatusTracker;
import ps.reso.instaeclipse.utils.i18n.I18n;
import ps.reso.instaeclipse.utils.log.ModuleLog;
import ps.reso.instaeclipse.utils.core.DexKitQueryCache;

/**
 * Export a followers / following list to CSV (#224).
 *
 * FollowListFragment = class whose method uses "Invalid entry type for FollowListFragment"
 * (X.0D9r on 452, X.0D96 on 451). After its onViewCreated the follow list screen's action bar gets
 * one tools button (FollowListToolsIcon: Instagram's search glyph, a menu of the enabled actions).
 * Export CSV loads the list to the end with IG's own pager (FollowListLoader: IG sends the API
 * requests and parses each page into the adapter) and writes every account of the adapter's account
 * list as username,full_name,id (the profile owner and the logged-in user, held in plain fields,
 * are not in that list). A progress dialog keeps the user on the list (IG's pager only runs while
 * it is open); "Stop & save" ends early.
 *
 * Followers history: on your own Followers tab the tools button also offers "Followers history",
 * which loads the whole list the same way and hands it to FollowersHistory (compared with the last
 * check: who unfollowed / followed). Own Followers tab = the fragment's FollowListFragment.FollowListData
 * argument holds the FOLLOWERS entry of IG's list-type enum (constant names are kept) and a String
 * equal to your user id, which is the part after ':' of the IgSessionManager.SESSION_TOKEN_KEY
 * argument. Only a list that loaded to the end (no next_max_id, not stopped) is compared.
 *
 * When Instagram refuses a load (rate limit, challenge, action block: FollowListLoader's failure
 * callback, Result.blocked), Export CSV writes nothing and Followers history compares nothing;
 * both show what Instagram said instead.
 */
public class FollowListExportHook {

    private static final String TAG = "(IE|FollowExport) ";

    public void install(DexKitBridge bridge, ClassLoader cl) {
        try {
            Class<?> frag = null;
            for (MethodData md : DexKitQueryCache.findMethod(bridge, FindMethod.create().matcher(MethodMatcher.create()
                    .usingStrings("Invalid entry type for FollowListFragment")))) {
                frag = cl.loadClass(md.getClassName());
                break;
            }
            if (frag == null) { ModuleLog.error(TAG + "Could not turn on the follower list tools: the follower list screen was not found in this Instagram version"); return; }
            Method ovc = null;
            for (Class<?> c = frag; c != null && ovc == null; c = c.getSuperclass()) {
                try { ovc = c.getDeclaredMethod("onViewCreated", View.class, android.os.Bundle.class); } catch (NoSuchMethodException ignored) {}
            }
            if (ovc == null) {
                ModuleLog.error(TAG + "Could not turn on the follower list tools: the follower list screen is not supported in this Instagram version");
                ModuleLog.detail(TAG + "onViewCreated not found on " + frag.getName());
                return;
            }
            fragClass = frag;
            XposedBridge.hookMethod(ovc, new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) { ps.reso.instaeclipse.utils.core.JitGuard.tick(param.method);
                    if ((!FeatureFlags.followListExport && !FeatureFlags.followersHistory && !FeatureFlags.profileAnalyzer)
                            || !fragClass.isInstance(param.thisObject)) return;
                    if (param.args[0] instanceof View root) {
                        live.put(param.thisObject, new java.lang.ref.WeakReference<>(root));
                        Object frag = param.thisObject;
                        root.post(() -> {
                            if (FeatureFlags.profileAnalyzer) {
                                ListInfo info = listInfo(frag);
                                if (info != null)
                                    ps.reso.instaeclipse.utils.users.WatchlistStore.setSelf(root.getContext().getApplicationContext(), info.self);
                            }
                            FollowListToolsIcon.attach(root);
                        });
                    }
                }
            });
            FollowListLoader.resolve(bridge, cl, frag);
            ModuleLog.ok(TAG + "Ready");
            ModuleLog.detail(TAG + "list fragment " + frag.getName());
            FeatureStatusTracker.setHooked("FollowListExport");
            FeatureStatusTracker.setHooked("FollowersHistory");
            FeatureStatusTracker.setHooked("ProfileAnalyzer");
        } catch (Throwable t) {
            ModuleLog.error(TAG + "Could not turn on the follower list tools", t);
        }
    }

    private static Class<?> fragClass;

    /** Live list fragments → their root view (followers / following / mutual tabs each have one).
     *  The root is held weakly: it reaches its own fragment (view-tree lifecycle owner tag), so a
     *  strong value would keep every fragment, its views and its Activity alive forever. */
    private static final Map<Object, java.lang.ref.WeakReference<View>> live = Collections.synchronizedMap(new java.util.WeakHashMap<>());

    /** Every live list fragment with its root view: {fragment, root}. */
    static List<Object[]> liveLists() {
        List<Object[]> out = new ArrayList<>();
        synchronized (live) {
            for (Map.Entry<Object, java.lang.ref.WeakReference<View>> e : live.entrySet()) {
                View r = e.getValue() != null ? e.getValue().get() : null;
                if (r != null) out.add(new Object[]{e.getKey(), r});
            }
        }
        return out;
    }

    /** The tab whose list is on screen right now. The tab pager keeps neighbouring tabs attached
     *  (isShown() is true for them too), so take the one with the largest on-screen area. */
    static Object[] visibleList() {
        Object[] best = null;
        long bestArea = 0;
        android.graphics.Rect rc = new android.graphics.Rect();
        for (Object[] e : liveLists()) {
            View r = (View) e[1];
            if (!r.isShown() || findList(r) == null || !r.getGlobalVisibleRect(rc)) continue;
            long area = (long) rc.width() * rc.height();
            if (area > bestArea) { bestArea = area; best = e; }
        }
        return best;
    }

    // ---- Export CSV ----

    /** Export CSV: loads the list to the end, then writes it. */
    static void exportList(View root, Object frag) {
        ListInfo info = listInfo(frag);
        String type = info != null && info.type != null ? info.type : FOLLOWERS;
        FollowListLoader.loadAll(root, frag, I18n.t(root.getContext(), R.string.ig_follow_export), r -> export(root, r, type));
    }

    private static void export(View root, FollowListLoader.Result r, String type) {
        Activity act = ps.reso.instaeclipse.mods.ui.UIHookManager.getCurrentActivity();
        android.content.Context ctx = act != null ? act : root.getContext();
        // Instagram refused the load (rate limit, challenge, action block): say so, save nothing
        if (r.blocked != null) {
            ModuleLog.warn(TAG + "Export stopped: Instagram blocked loading the list (" + r.blocked.kind + "), nothing was saved");
            FollowListLoader.showBlocked(ctx, r.blocked);
            return;
        }
        if (r.users.isEmpty()) {
            ps.reso.instaeclipse.utils.toast.IeToast.makeText(root.getContext(), I18n.t(root.getContext(), R.string.ig_follow_export_none), Toast.LENGTH_SHORT).show();
            return;
        }
        String file = "follow_list_" + new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(new Date()) + ".csv";
        // a list Instagram flagged as limited: the toast says so
        String note = r.limited ? I18n.t(ctx, R.string.ig_pa_limited_note, ProfileAnalyzer.label(ctx, type)) : null;
        new Thread(() -> writeCsv(ctx, r.users, file, note)).start();
    }

    /** {@code users}: id → {username, full name}; {@code note}: a line added to the done toast, or null. */
    private static void writeCsv(android.content.Context ctx, Map<String, String[]> users, String file, String note) {
        try {
            StringBuilder csv = new StringBuilder("username,full_name,id\n");
            for (Map.Entry<String, String[]> e : users.entrySet())
                csv.append(q(e.getValue()[0])).append(',').append(q(e.getValue()[1])).append(',').append(q(e.getKey())).append('\n');
            csv.append(ps.reso.instaeclipse.utils.core.ExportBrand.csvFooter());
            try (OutputStream out = ps.reso.instaeclipse.mods.media.FeedVideoDownloadHook.openExport(ctx, file, "text/csv")) {
                out.write(new byte[]{(byte) 0xEF, (byte) 0xBB, (byte) 0xBF}); // UTF-8 BOM for Excel
                out.write(csv.toString().getBytes(StandardCharsets.UTF_8));
            }
            ModuleLog.line(TAG + "Exported " + users.size() + " accounts to " + file);
            new Handler(Looper.getMainLooper()).post(() ->
                    ps.reso.instaeclipse.utils.toast.IeToast.makeText(ctx, I18n.t(ctx, R.string.ig_follow_export_done, users.size(), file)
                            + (note != null ? "\n" + note : ""), Toast.LENGTH_LONG).show());
        } catch (Throwable t) {
            ModuleLog.error(TAG + "Could not save the exported list", t);
        }
    }

    // ---- Whose list, which tab (Profile analyzer) ----

    static final String FOLLOWERS = "FOLLOWERS", FOLLOWING = "FOLLOWING";

    /** A list fragment's arguments: the logged-in user, the profile whose list it is, and the tab. */
    static final class ListInfo {
        String self;      // logged-in user id (from the session token argument)
        String owner;     // the profile whose list this is (FollowListData's numeric String)
        String type;      // FOLLOWERS / FOLLOWING, or null for other tabs (mutual, suggested, …)
        String username;  // the profile's username when IG passes it
        Object frag;
    }

    /** What {@code frag} lists, from its FollowListFragment.FollowListData argument (IG's list-type
     *  enum keeps its constant names; its String fields hold the profile's id). */
    static ListInfo listInfo(Object frag) {
        try {
            android.os.Bundle b = (android.os.Bundle) frag.getClass().getMethod("getArguments").invoke(frag);
            if (b == null) return null;
            Object token = b.get("IgSessionManager.SESSION_TOKEN_KEY");
            Object data = b.get("FollowListFragment.FollowListData");
            if (data == null) return null;
            ListInfo info = new ListInfo();
            info.frag = frag;
            if (token instanceof String t) {
                String s = t.substring(t.lastIndexOf(':') + 1);
                if (s.matches("\\d+")) info.self = s;
            }
            Object un = b.get("FollowListFragment.Username");
            if (un instanceof String u && !u.isEmpty()) info.username = u;
            for (Field f : ps.reso.instaeclipse.utils.core.SafeReflect.fields(data.getClass())) {
                if (Modifier.isStatic(f.getModifiers())) continue;
                f.setAccessible(true);
                Object v = f.get(data);
                if (v instanceof Enum<?> e) {
                    String n = e.name();
                    if (n.equals("FOLLOWERS") || n.equals("SELF_FOLLOWERS")) info.type = FOLLOWERS;
                    else if (n.equals("FOLLOWING") || n.equals("FOLLOWING_SIMPLIFIED") || n.equals("SELF_FOLLOWING")) info.type = FOLLOWING;
                } else if (v instanceof String s && s.matches("\\d+") && info.owner == null) info.owner = s;
            }
            return info.owner != null ? info : null;
        } catch (Throwable t) {
            return null;
        }
    }

    /** Live list fragments of {@code owner}'s {@code type} tab, the one on screen first. */
    static Object[] listOf(String owner, String type) {
        Object[] vis = visibleList();
        if (vis != null) {
            ListInfo i = listInfo(vis[0]);
            if (i != null && owner.equals(i.owner) && type.equals(i.type)) return vis;
        }
        return null;
    }

    /**
     * The follow list screen around {@code frag}: its parent UnifiedFollowFragment keeps its tabs
     * (IG's list-type enum, constant names kept) in a List and its ViewPager2 in the "viewPager"
     * field. {ViewPager2, tabs}, or null when {@code frag} isn't a tab of that screen.
     */
    static Object[] tabsOf(Object frag) {
        try {
            Object parent = null;
            for (Class<?> c = frag.getClass(); c != null && parent == null; c = c.getSuperclass())
                try {
                    Field pf = c.getDeclaredField("mParentFragment");
                    pf.setAccessible(true);
                    parent = pf.get(frag);
                } catch (NoSuchFieldException ignored) {}
            if (parent == null) return null;
            Object pager = null;
            List<?> tabs = null;
            for (Class<?> c = parent.getClass(); c != null && c != Object.class; c = c.getSuperclass())
                for (Field f : ps.reso.instaeclipse.utils.core.SafeReflect.fields(c)) {
                    if (Modifier.isStatic(f.getModifiers())) continue;
                    f.setAccessible(true);
                    Object v = f.get(parent);
                    if (v instanceof View vp && pager == null) {
                        for (Class<?> k = vp.getClass(); k != null; k = k.getSuperclass())
                            if (k.getName().equals("androidx.viewpager2.widget.ViewPager2")) { pager = vp; break; }
                    } else if (v instanceof List<?> l && tabs == null && isTabList(l)) tabs = l;
                }
            return pager != null && tabs != null ? new Object[]{pager, tabs} : null;
        } catch (Throwable t) {
            ModuleLog.detail(TAG + "tabs: " + t);
            return null;
        }
    }

    /** Shows {@code type}'s tab of the follow list screen that holds {@code frag}; false when the
     *  screen doesn't have that tab. */
    static boolean showTab(Object frag, String type) {
        try {
            Object[] t = tabsOf(frag);
            if (t == null) return false;
            List<?> tabs = (List<?>) t[1];
            for (int i = 0; i < tabs.size(); i++) {
                String n = ((Enum<?>) tabs.get(i)).name();
                boolean match = FOLLOWERS.equals(type) ? n.equals("FOLLOWERS") || n.equals("SELF_FOLLOWERS")
                        : n.equals("FOLLOWING") || n.equals("FOLLOWING_SIMPLIFIED") || n.equals("SELF_FOLLOWING");
                if (match) {
                    t[0].getClass().getMethod("setCurrentItem", int.class).invoke(t[0], i);
                    return true;
                }
            }
        } catch (Throwable t) {
            ModuleLog.detail(TAG + "tab: " + t);
        }
        return false;
    }

    private static boolean isTabList(List<?> l) {
        boolean tab = false;
        for (Object o : l) {
            if (!(o instanceof Enum<?> e)) return false;
            if (e.name().startsWith("FOLLOW")) tab = true;
        }
        return tab;
    }

    static String idOf(Object user) { return FollowListLoader.idOf(user); }

    // ---- Followers history ----

    /** Your user id when {@code frag} is your own Followers tab, else null. */
    static String ownFollowersOf(Object frag) {
        try {
            android.os.Bundle b = (android.os.Bundle) frag.getClass().getMethod("getArguments").invoke(frag);
            if (b == null) return null;
            Object token = b.get("IgSessionManager.SESSION_TOKEN_KEY");
            Object data = b.get("FollowListFragment.FollowListData");
            if (!(token instanceof String t) || data == null) return null;
            String self = t.substring(t.lastIndexOf(':') + 1);
            boolean followers = false, own = false;
            for (Field f : ps.reso.instaeclipse.utils.core.SafeReflect.fields(data.getClass())) {
                if (Modifier.isStatic(f.getModifiers())) continue;
                f.setAccessible(true);
                Object v = f.get(data);
                if (v instanceof Enum<?> e && e.name().equals("FOLLOWERS")) followers = true;
                if (self.equals(v)) own = true;
            }
            return followers && own && self.matches("\\d+") ? self : null;
        } catch (Throwable t) {
            return null;
        }
    }

    /** Followers history: loads your own Followers list to the end, then compares it. */
    static void checkHistory(View root, Object frag) {
        String self = ownFollowersOf(frag);
        if (self == null) {
            ps.reso.instaeclipse.utils.toast.IeToast.makeText(root.getContext(), I18n.t(root.getContext(), R.string.ig_followers_history_wrong_tab), Toast.LENGTH_SHORT).show();
            return;
        }
        FollowListLoader.loadAll(root, frag, I18n.t(root.getContext(), R.string.ig_followers_history), r -> checkHistory(root, self, r));
    }

    private static void checkHistory(View root, String self, FollowListLoader.Result loaded) {
        Activity act = ps.reso.instaeclipse.mods.ui.UIHookManager.getCurrentActivity();
        android.content.Context ctx = act != null ? act : root.getContext();
        // Instagram refused the load (rate limit, challenge, action block): say so, compare nothing
        if (loaded.blocked != null) {
            ModuleLog.warn(TAG + "Followers history stopped: Instagram blocked loading the list (" + loaded.blocked.kind + "), nothing was compared");
            FollowListLoader.showBlocked(ctx, loaded.blocked);
            return;
        }
        Map<String, String[]> users = loaded.users;
        boolean complete = loaded.complete;
        new Thread(() -> {
            ps.reso.instaeclipse.utils.users.FollowersHistory.State state =
                    ps.reso.instaeclipse.utils.users.FollowersHistory.load(ctx, self);
            ps.reso.instaeclipse.utils.users.FollowersHistory.Result result = null;
            String error = null;
            if (complete && !users.isEmpty()) {
                result = ps.reso.instaeclipse.utils.users.FollowersHistory.apply(state, users, System.currentTimeMillis());
                try {
                    ps.reso.instaeclipse.utils.users.FollowersHistory.save(ctx, self, state);
                } catch (Throwable t) {
                    error = t.toString();
                    ModuleLog.error(TAG + "Could not save the followers history", t);
                }
                ModuleLog.line(TAG + "Followers history: " + users.size() + " followers, " + result.added.size() + " new changes"
                        + (result.first ? " (first check)" : ""));
            } else {
                ModuleLog.line(TAG + "Followers history: the list did not load completely (" + users.size() + " accounts), nothing was compared");
            }
            final ps.reso.instaeclipse.utils.users.FollowersHistory.Result r = result;
            final String err = error;
            new Handler(Looper.getMainLooper()).post(() -> showHistory(ctx, self, state, r, complete && !users.isEmpty(), err, loaded));
        }).start();
    }

    private static void showHistory(android.content.Context ctx, String self,
                                    ps.reso.instaeclipse.utils.users.FollowersHistory.State state,
                                    ps.reso.instaeclipse.utils.users.FollowersHistory.Result r,
                                    boolean compared, String error, FollowListLoader.Result loaded) {
        try {
            java.text.DateFormat df = java.text.DateFormat.getDateTimeInstance(java.text.DateFormat.MEDIUM, java.text.DateFormat.SHORT);
            ps.reso.instaeclipse.utils.dialog.IeDialog dlg = new ps.reso.instaeclipse.utils.dialog.IeDialog(ctx)
                    .title(I18n.t(ctx, R.string.ig_followers_history));
            String head;
            if (!compared) head = I18n.t(ctx, R.string.ig_followers_history_partial);
            else if (r.first) head = I18n.t(ctx, R.string.ig_followers_history_first, r.count);
            else {
                int lost = 0, gained = 0;
                for (ps.reso.instaeclipse.utils.users.FollowersHistory.Event e : r.added)
                    if (ps.reso.instaeclipse.utils.users.FollowersHistory.LOST.equals(e.type)) lost++; else gained++;
                head = I18n.t(ctx, R.string.ig_followers_history_changes, df.format(new Date(r.previousCheck)), lost, gained);
            }
            if (state.lastCheck > 0)
                head += "\n" + I18n.t(ctx, R.string.ig_followers_history_summary, state.users.size(), df.format(new Date(state.lastCheck)));
            if (compared) {
                String listed = ProfileAnalyzer.listedNotes(ctx, new String[]{FOLLOWERS}, new int[]{loaded.users.size()},
                        new int[]{loaded.expected}, new boolean[]{loaded.limited});
                if (!listed.isEmpty()) head += "\n" + listed;
            }
            if (error != null) head += "\n⚠️ " + error;
            dlg.message(head);

            if (state.events.isEmpty()) {
                TextView empty = new TextView(dlg.ctx);
                empty.setText(I18n.t(ctx, R.string.ig_followers_history_empty));
                empty.setTextColor(dlg.sub);
                empty.setTextSize(14);
                dlg.view(empty);
            } else {
                android.widget.LinearLayout rows = new android.widget.LinearLayout(dlg.ctx);
                rows.setOrientation(android.widget.LinearLayout.VERTICAL);
                int shown = 0;
                for (ps.reso.instaeclipse.utils.users.FollowersHistory.Event e : state.events) {
                    if (shown++ >= 300) break;
                    boolean lost = ps.reso.instaeclipse.utils.users.FollowersHistory.LOST.equals(e.type);
                    android.text.SpannableStringBuilder sb = new android.text.SpannableStringBuilder();
                    sb.append(lost ? "− " : "+ ");
                    sb.append("@").append(e.username != null ? e.username : e.id);
                    sb.setSpan(new android.text.style.StyleSpan(android.graphics.Typeface.BOLD), 0, sb.length(), 0);
                    if (e.fullName != null && !e.fullName.isEmpty()) sb.append("  ").append(e.fullName);
                    int start = sb.length();
                    sb.append("\n").append(I18n.t(ctx, lost ? R.string.ig_followers_history_unfollowed : R.string.ig_followers_history_followed))
                            .append(" · ").append(df.format(new Date(e.ts)));
                    sb.setSpan(new android.text.style.ForegroundColorSpan(lost ? 0xFFFF453A : 0xFF34C759), start, sb.length(), 0);
                    sb.setSpan(new android.text.style.RelativeSizeSpan(0.85f), start, sb.length(), 0);
                    TextView row = new TextView(dlg.ctx);
                    row.setText(sb);
                    row.setTextColor(dlg.text);
                    row.setTextSize(14);
                    row.setPadding(0, dlg.dp(6), 0, dlg.dp(6));
                    row.setTextIsSelectable(true);
                    rows.addView(row);
                }
                dlg.view(rows);
                TextView note = new TextView(dlg.ctx);
                note.setText(I18n.t(ctx, R.string.ig_followers_history_note));
                note.setTextColor(dlg.sub);
                note.setTextSize(12);
                dlg.view(note);
            }
            if (state.lastCheck > 0) {
                dlg.secondary(I18n.t(ctx, R.string.ig_followers_history_clear), () ->
                        new Thread(() -> ps.reso.instaeclipse.utils.users.FollowersHistory.clear(ctx, self)).start());
            }
            dlg.show();
        } catch (Throwable t) {
            ModuleLog.error(TAG + "Could not show the followers history", t);
        }
    }

    private static String q(String s) {
        if (s == null) return "";
        return s.contains(",") || s.contains("\"") || s.contains("\n") ? "\"" + s.replace("\"", "\"\"") + "\"" : s;
    }

    static AbsListView findList(View root) {
        ArrayDeque<View> st = new ArrayDeque<>();
        st.push(root);
        while (!st.isEmpty()) {
            View v = st.pop();
            if (v instanceof AbsListView l && l.isShown()) return l;
            if (v instanceof ViewGroup g) for (int i = 0; i < g.getChildCount(); i++) st.push(g.getChildAt(i));
        }
        return null;
    }
}
