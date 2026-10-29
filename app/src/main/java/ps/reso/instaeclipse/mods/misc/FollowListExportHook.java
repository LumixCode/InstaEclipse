package ps.reso.instaeclipse.mods.misc;

import android.app.Activity;
import android.content.Intent;
import android.os.Handler;
import android.os.Looper;
import android.util.Base64;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AbsListView;
import android.widget.Toast;

import org.luckypray.dexkit.query.FindMethod;
import org.luckypray.dexkit.query.matchers.MethodMatcher;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import ps.reso.instaeclipse.xposedcompat.XC_MethodHook;
import ps.reso.instaeclipse.xposedcompat.XposedBridge;
import ps.reso.instaeclipse.R;
import ps.reso.instaeclipse.utils.feature.FeatureFlags;
import ps.reso.instaeclipse.utils.feature.FeatureStatusTracker;
import ps.reso.instaeclipse.utils.i18n.I18n;
import ps.reso.instaeclipse.utils.ipc.ExportDelegate;
import ps.reso.instaeclipse.utils.log.ModuleLog;
import ps.reso.instaeclipse.utils.core.DexKitQueryCache;
import ps.reso.instaeclipse.utils.core.DexKitQueryCache.Found;

public class FollowListExportHook {

    private static final String TAG = "(IE|FollowExport) ";

    public void install(ClassLoader cl) {
        try {
            Class<?> frag = null;
            for (Found md : DexKitQueryCache.methods(FindMethod.create().matcher(MethodMatcher.create()
                    .usingStrings("Invalid entry type for FollowListFragment")))) {
                frag = cl.loadClass(md.getClassName());
                break;
            }
            if (frag == null) {
                ModuleLog.error(TAG + "Could not turn on the follower list tools: the follower list screen was not found in this Instagram version");
                broken();
                return;
            }
            Method ovc = null;
            for (Class<?> c = frag; c != null && ovc == null; c = c.getSuperclass()) {
                try { ovc = c.getDeclaredMethod("onViewCreated", View.class, android.os.Bundle.class); } catch (NoSuchMethodException ignored) {}
            }
            if (ovc == null) {
                ModuleLog.error(TAG + "Could not turn on the follower list tools: the follower list screen is not supported in this Instagram version");
                ModuleLog.detail(TAG + "onViewCreated not found on " + frag.getName());
                broken();
                return;
            }
            fragClass = frag;
            XposedBridge.hookMethod(ovc, new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    if ((!FeatureFlags.followListExport && !FeatureFlags.profileAnalyzer)
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
            boolean ready = FollowListLoader.resolve(cl, frag);
            ModuleLog.detail(TAG + "list fragment " + frag.getName());
            if (!ready) {
                broken();
                return;
            }
            ModuleLog.ok(TAG + "Ready");
            FeatureStatusTracker.setHooked("FollowListExport");
            FeatureStatusTracker.setHooked("FollowersHistory");
            FeatureStatusTracker.setHooked("ProfileAnalyzer");
        } catch (Throwable t) {
            ModuleLog.error(TAG + "Could not turn on the follower list tools", t);
            broken();
        }
    }

    private static void broken() {
        if (FeatureFlags.followListExport) FeatureStatusTracker.setBroken("FollowListExport");
        if (FeatureFlags.profileAnalyzer) FeatureStatusTracker.setBroken("ProfileAnalyzer");
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
        String named = info != null && info.username != null ? info.username : ProfileAnalyzer.titleOf(root);
        String owner = named != null ? named : info != null ? info.owner : null;
        FollowListLoader.loadAll(root, frag, I18n.t(root.getContext(), R.string.ig_follow_export),
                I18n.t(root.getContext(), R.string.ig_follow_export_stop), r -> export(root, r, type, owner));
    }

    private static void export(View root, FollowListLoader.Result r, String type, String owner) {
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
        String file = CsvCells.fileName(owner, type, System.currentTimeMillis());
        // a list Instagram flagged as limited: the toast says so
        String note = r.limited ? I18n.t(ctx, R.string.ig_pa_limited_note, ProfileAnalyzer.label(ctx, type)) : null;
        new Thread(() -> writeCsv(ctx, r.users, file, note)).start();
    }

    /** {@code users}: id → {username, full name}; {@code note}: a line added to the done toast, or null. */
    private static void writeCsv(android.content.Context ctx, Map<String, String[]> users, String file, String note) {
        Handler main = new Handler(Looper.getMainLooper());
        java.util.function.Consumer<Throwable> failed = t -> {
            ModuleLog.error(TAG + "Could not save the exported list", t);
            main.post(() -> ps.reso.instaeclipse.utils.toast.IeToast.makeText(ctx,
                    I18n.t(ctx, R.string.ig_follow_export_failed, CsvCells.reason(t)), Toast.LENGTH_LONG).show());
        };
        byte[] bytes;
        try {
            bytes = csv(users);
        } catch (Throwable t) {
            failed.accept(t);
            return;
        }
        int count = users.size();
        Runnable writeLocally = () -> writeLocally(ctx, main, failed, bytes, file, count, note);
        if (!sendToCompanion(ctx, bytes, file, count, writeLocally)) {
            writeLocally.run();
            return;
        }
        if (note != null)
            main.post(() -> ps.reso.instaeclipse.utils.toast.IeToast.makeText(ctx, note, Toast.LENGTH_LONG).show());
    }

    private static void writeLocally(android.content.Context ctx, Handler main, java.util.function.Consumer<Throwable> failed,
                                     byte[] bytes, String file, int count, String note) {
        if (!CsvCells.write(() -> ps.reso.instaeclipse.mods.media.FeedVideoDownloadHook.openExport(ctx, file, "text/csv"),
                bytes, failed)) return;
        ModuleLog.line(TAG + "Exported " + count + " accounts to " + file);
        main.post(() -> ps.reso.instaeclipse.utils.toast.IeToast.makeText(ctx, I18n.t(ctx, R.string.ig_follow_export_done,
                count, "exports/" + file) + (note != null ? "\n" + note : ""), Toast.LENGTH_LONG).show());
    }

    private static boolean sendToCompanion(android.content.Context ctx, byte[] bytes, String file, int count,
                                           Runnable writeLocally) {
        String saf = FeatureFlags.downloaderCustomUri;
        if (saf == null || saf.isEmpty()) return false;
        try {
            Intent request = new Intent();
            request.setClassName("ps.reso.instaeclipse", "ps.reso.instaeclipse.mods.media.DownloadSaveService");
            request.putExtra("chatExportSubdir", "exports");
            request.putExtra("exportMime", "text/csv");
            request.putExtra("htmlFilename", file);
            request.putExtra("htmlB64", Base64.encodeToString(bytes, Base64.NO_WRAP));
            request.putExtra("saveUri", saf);
            boolean sent = ExportDelegate.delegate(ctx, request, TAG, writeLocally);
            if (sent) ModuleLog.line(TAG + "Exporting " + count + " accounts through the InstaEclipse app to exports/" + file);
            else ModuleLog.detail(TAG + "The list is outside the InstaEclipse app's limits (" + bytes.length + " bytes), saving it from Instagram");
            return sent;
        } catch (Throwable t) {
            ModuleLog.warn(TAG + "Could not hand the export to the InstaEclipse app, saving it from Instagram instead: " + t);
            return false;
        }
    }

    private static byte[] csv(Map<String, String[]> users) {
        StringBuilder csv = new StringBuilder(CsvCells.cell("username")).append(',').append(CsvCells.cell("full_name"))
                .append(',').append(CsvCells.cell("id")).append('\n');
        for (Map.Entry<String, String[]> e : users.entrySet())
            csv.append(CsvCells.cell(e.getValue()[0])).append(',').append(CsvCells.cell(e.getValue()[1])).append(',')
                    .append(CsvCells.cell(e.getKey())).append('\n');
        csv.append(ps.reso.instaeclipse.utils.core.ExportBrand.csvFooter());
        byte[] text = csv.toString().getBytes(StandardCharsets.UTF_8);
        byte[] bytes = new byte[text.length + 3];
        bytes[0] = (byte) 0xEF;
        bytes[1] = (byte) 0xBB;
        bytes[2] = (byte) 0xBF;
        System.arraycopy(text, 0, bytes, 3, text.length);
        return bytes;
    }

    // ---- Whose list, which tab (Profile analyzer) ----

    static final String FOLLOWERS = "FOLLOWERS", FOLLOWING = "FOLLOWING";

    /** A list fragment's arguments: the logged-in user, the profile whose list it is, and the tab. */
    static final class ListInfo {
        String self;      // logged-in user id (from the session token argument)
        String owner;     // the profile whose list this is (FollowListData's numeric String)
        String type;      // FOLLOWERS / FOLLOWING, or null for other tabs (mutual, suggested, …)
        String username;  // the profile's username when IG passes it
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
                    if (FollowListPolicy.isFollowersType(n)) info.type = FOLLOWERS;
                    else if (FollowListPolicy.isFollowingType(n)) info.type = FOLLOWING;
                } else if (v instanceof String s && s.matches("\\d+") && info.owner == null) info.owner = s;
            }
            return info.owner != null ? info : null;
        } catch (Throwable t) {
            return null;
        }
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
                boolean match = FOLLOWERS.equals(type) ? FollowListPolicy.isFollowersType(n) : FollowListPolicy.isFollowingType(n);
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
                if (v instanceof Enum<?> e && FollowListPolicy.isFollowersType(e.name())) followers = true;
                if (self.equals(v)) own = true;
            }
            return followers && own && self.matches("\\d+") ? self : null;
        } catch (Throwable t) {
            return null;
        }
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
