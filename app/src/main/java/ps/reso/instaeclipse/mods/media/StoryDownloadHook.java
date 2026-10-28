package ps.reso.instaeclipse.mods.media;

import ps.reso.instaeclipse.xposedcompat.AndroidAppHelper;
import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.widget.Toast;

import org.luckypray.dexkit.DexKitBridge;
import org.luckypray.dexkit.query.FindMethod;
import org.luckypray.dexkit.query.matchers.MethodMatcher;
import org.luckypray.dexkit.result.MethodData;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import ps.reso.instaeclipse.xposedcompat.XC_MethodHook;
import ps.reso.instaeclipse.xposedcompat.XposedBridge;
import ps.reso.instaeclipse.R;
import ps.reso.instaeclipse.mods.misc.StoryMenu;
import ps.reso.instaeclipse.utils.core.DexKitCache;
import ps.reso.instaeclipse.utils.feature.FeatureFlags;
import ps.reso.instaeclipse.utils.feature.FeatureStatusTracker;
import ps.reso.instaeclipse.utils.i18n.I18n;
import ps.reso.instaeclipse.utils.users.UserUtils;
import ps.reso.instaeclipse.utils.log.ModuleLog;
import ps.reso.instaeclipse.utils.core.DexKitQueryCache;

public class StoryDownloadHook {

    private static final Handler mainHandler = new Handler(Looper.getMainLooper());

    private static final class StoryMediaOptions {
        final String imageUrl;
        final String videoUrl;

        StoryMediaOptions(String imageUrl, String videoUrl) {
            this.imageUrl = imageUrl;
            this.videoUrl = videoUrl;
        }
    }

    // ── Entry point ──────────────────────────────────────────────────────────

    // ── Highlight "Download all" (#250) ─────────────────────────────────────
    // Reel model = class using "Reel with pending media should have an owner." (X.04aX on 452,
    // X.04ey on 451). A highlight's reel has a String id starting with "highlight:"; its
    // (UserSession) -> List methods return the ReelItems (the whole highlight is fetched by
    // feed/reels_media/ when it opens).
    private static Class<?> reelClass;

    private static List<?> highlightItems(Object reel, Object holder) {
        return highlightItemsWith(reel, readFieldByTypeName(holder, "com.instagram.common.session.UserSession"));
    }

    private static List<?> highlightItemsWith(Object reel, Object session) {
        List<?> best = null;
        for (Method m : ps.reso.instaeclipse.utils.core.SafeReflect.publicMethods(reel.getClass())) {
            Class<?>[] p = m.getParameterTypes();
            if (p.length != 1 || !List.class.isAssignableFrom(m.getReturnType())
                    || !p[0].getName().equals("com.instagram.common.session.UserSession")) continue;
            try {
                Object r = m.invoke(reel, session);
                if (r instanceof List<?> l && (best == null || l.size() > best.size())) best = l;
            } catch (Throwable ignored) {}
        }
        return best;
    }

    private static void downloadHighlight(Context ctx, Object reel, Object holder) {
        downloadHighlightItems(ctx, highlightItems(reel, holder));
    }

    private static void downloadHighlightItems(Context ctx, List<?> items) {
        if (items == null || items.isEmpty()) {
            ps.reso.instaeclipse.utils.toast.IeToast.makeText(ctx, I18n.t(ctx, R.string.ig_toast_no_media_for_post), Toast.LENGTH_SHORT).show();
            return;
        }
        final List<Object> copy = new ArrayList<>(items);
        ps.reso.instaeclipse.utils.toast.IeToast.makeText(ctx, I18n.t(ctx, R.string.ig_highlight_downloading, copy.size()), Toast.LENGTH_LONG).show();
        ModuleLog.line("(IE|Highlight) Downloading " + copy.size() + " highlight items");
        new Thread(() -> {
            int ok = 0;
            for (Object item : copy) {
                try {
                    StoryMediaOptions m = extractStoryMediaOptions(ctx, item, true);
                    if (m == null) continue;
                    boolean video = m.videoUrl != null;
                    String url = video ? m.videoUrl : m.imageUrl;
                    String user = extractUsernameFromReelItemHolder(item);
                    String id = extractMediaIdFromReelItemHolder(item);
                    String fn = FeedVideoDownloadHook.buildFilename(user, "highlight", id, video);
                    FeedVideoDownloadHook.downloadAndSave(ctx, url, fn, video, user);
                    ok++;
                    Thread.sleep(400);
                } catch (Throwable t) {
                    ModuleLog.warn("(IE|Highlight) Skipped one highlight item that could not be downloaded: " + t);
                }
            }
            final int done = ok;
            mainHandler.post(() -> ps.reso.instaeclipse.utils.toast.IeToast.makeText(ctx, I18n.t(ctx, R.string.ig_highlight_done, done, copy.size()), Toast.LENGTH_LONG).show());
        }).start();
    }

    public void install(DexKitBridge bridge, ClassLoader classLoader) {
        ps.reso.instaeclipse.utils.media.StoryCache.pruneAfterStart();
        SelectedMediaAdapter.install(bridge, classLoader);
        try {
            for (MethodData md : DexKitQueryCache.findMethod(bridge, FindMethod.create().matcher(MethodMatcher.create()
                    .usingStrings("Reel with pending media should have an owner.")))) {
                reelClass = classLoader.loadClass(md.getClassName());
                break;
            }
            if (reelClass == null)
                ModuleLog.warn("(IE|Highlight) Downloading a whole highlight (Download all) is not available in this Instagram version; single stories still download");
            else reelIdGetter = idGetter(reelClass);
            ModuleLog.detail("(IE|Highlight) reel class " + (reelClass != null ? reelClass.getName() : "not found"));
        } catch (Throwable t) {
            ModuleLog.warn("(IE|Highlight) Downloading a whole highlight (Download all) is not available; single stories still download: " + t);
        }
        installMenuRows(bridge, classLoader);
        installHighlightButtonInjectorHook(bridge, classLoader);
        installHighlightTrayHook(bridge, classLoader);
        resolveExpiringGetter(bridge, classLoader);
        installStoryCaptureHook(bridge, classLoader);
    }

    private static void installMenuRows(DexKitBridge bridge, ClassLoader classLoader) {
        if (!StoryMenu.install(bridge, classLoader)) {
            ModuleLog.error("(IE|Story) Download will not appear in the story menu: the menu was not found in this Instagram version");
            if (FeatureFlags.enableStoryDownload) FeatureStatusTracker.setBroken("StoryDownload");
            return;
        }
        StoryMenu.register(StoryMenu.DOWNLOAD, new DownloadRows());
        StoryMenu.register(StoryMenu.SPEED, new SpeedRow());
        ModuleLog.ok("(IE|Story) ready");
        FeatureStatusTracker.setHooked("StoryDownload");
    }

    private static String label(int id) {
        return I18n.t(AndroidAppHelper.currentApplication(), id);
    }

    private static final class DownloadRows implements StoryMenu.Row {
        @Override
        public boolean enabled() {
            return FeatureFlags.enableStoryDownload;
        }

        @Override
        public void rows(Object menu, Object media, List<CharSequence> out) {
            out.add(label(R.string.ig_dl_title));
            if (highlight(menuReel(menu))) out.add(label(R.string.ig_highlight_download_all));
        }

        @Override
        public boolean owns(CharSequence tapped) {
            return label(R.string.ig_dl_title).contentEquals(tapped)
                    || label(R.string.ig_highlight_download_all).contentEquals(tapped);
        }

        @Override
        public void tap(CharSequence tapped, Object menu, Object media, Context ctx, Runnable resume) {
            if (label(R.string.ig_highlight_download_all).contentEquals(tapped)) {
                Object reel = menuReel(menu);
                if (highlight(reel)) downloadHighlight(ctx, reel, menu);
                else ps.reso.instaeclipse.utils.toast.IeToast.makeText(ctx, I18n.t(ctx, R.string.ig_toast_story_unavailable), Toast.LENGTH_SHORT).show();
                resume.run();
                return;
            }
            try {
                SelectedMediaDownloads.showPost(ctx, SelectedMediaDownloads.capture(ctx, media, "story"), resume);
            } catch (Throwable unavailable) {
                SelectedMediaDownloads.unavailable(ctx, unavailable);
                resume.run();
            }
        }
    }

    private static final class SpeedRow implements StoryMenu.Row {
        @Override
        public boolean enabled() {
            return FeatureFlags.offerPlaybackSpeed;
        }

        @Override
        public void rows(Object menu, Object media, List<CharSequence> out) {
            out.add(label(R.string.ig_playback_speed_title));
        }

        @Override
        public boolean owns(CharSequence tapped) {
            return label(R.string.ig_playback_speed_title).contentEquals(tapped);
        }

        @Override
        public void tap(CharSequence tapped, Object menu, Object media, Context ctx, Runnable resume) {
            PlaybackSpeedHook.showChooser(ctx, resume);
        }
    }

    private static final Field[] NO_PATH = new Field[0];
    private static final Map<Class<?>, Field[]> reelPaths = new ConcurrentHashMap<>();
    private static volatile Method reelIdGetter;

    private static Method idGetter(Class<?> reel) {
        try {
            Method m = reel.getMethod("getId");
            return m.getReturnType() == String.class ? m : null;
        } catch (Throwable t) {
            return null;
        }
    }

    private static Object menuReel(Object menu) {
        if (menu == null || reelClass == null) return null;
        Field[] path = reelPaths.computeIfAbsent(menu.getClass(), StoryDownloadHook::reelPath);
        if (path.length == 0) return null;
        Object at = menu;
        for (Field f : path) {
            if (at == null) return null;
            try {
                at = f.get(at);
            } catch (Throwable t) {
                return null;
            }
        }
        return at;
    }

    private static Field[] reelPath(Class<?> menu) {
        List<Field> direct = instanceFields(menu, reelClass);
        if (direct.size() == 1) return new Field[]{direct.get(0)};
        if (!direct.isEmpty()) return NO_PATH;
        Field holder = null, inner = null;
        for (Field f : ps.reso.instaeclipse.utils.core.SafeReflect.fields(menu)) {
            if (java.lang.reflect.Modifier.isStatic(f.getModifiers()) || !f.getType().getName().startsWith("X.")) continue;
            List<Field> reels = instanceFields(f.getType(), reelClass);
            if (reels.isEmpty()) continue;
            if (holder != null || reels.size() > 1) return NO_PATH;
            holder = f;
            inner = reels.get(0);
        }
        if (holder == null) return NO_PATH;
        try {
            holder.setAccessible(true);
            inner.setAccessible(true);
        } catch (Throwable t) {
            return NO_PATH;
        }
        return new Field[]{holder, inner};
    }

    private static List<Field> instanceFields(Class<?> owner, Class<?> type) {
        List<Field> out = new ArrayList<>();
        for (Field f : ps.reso.instaeclipse.utils.core.SafeReflect.fields(owner)) {
            if (java.lang.reflect.Modifier.isStatic(f.getModifiers()) || f.getType() != type) continue;
            try {
                f.setAccessible(true);
            } catch (Throwable t) {
                return Collections.emptyList();
            }
            out.add(f);
        }
        return out;
    }

    private static boolean highlight(Object reel) {
        if (reel == null) return false;
        Method getter = reelIdGetter;
        if (getter == null) return highlightId(reel) != null;
        try {
            return getter.invoke(reel) instanceof String id && id.startsWith("highlight:");
        } catch (Throwable t) {
            return false;
        }
    }

    // ── Highlight long-press menu (profile tray): "Download whole highlight" ──
    // Helper = class of the menu builder A0G(View, ?, ReelItem, Integer), the only method using
    // "HighlightsTrayDialogHelper" (X.0MGm on 452); it holds the Activity, UserSession and the highlight
    // reel. The builder shows either the legacy dialog (rows via add(String, OnClickListener), then a
    // no-arg -> List collects them) or the newer popup (List of rows passed to set(List); rows come from
    // the helper's static (rowIface, String) -> List factory, rowIface = boolean method + void click).
    private static final ThreadLocal<Object> trayHelper = new ThreadLocal<>();
    private static final Set<Object> trayDialogsDone = Collections.newSetFromMap(new java.util.WeakHashMap<>());

    private void installHighlightTrayHook(DexKitBridge bridge, ClassLoader cl) {
        try {
            MethodData builder = null;
            for (MethodData md : DexKitQueryCache.findMethod(bridge, FindMethod.create().matcher(MethodMatcher.create()
                    .usingStrings("HighlightsTrayDialogHelper").paramCount(4)))) { builder = md; break; }
            if (builder == null) { ModuleLog.warn("(IE|HighlightTray) Download all is not available in the highlight menu in this Instagram version"); return; }
            Method build = builder.getMethodInstance(cl);
            final Class<?> helper = build.getDeclaringClass();
            Method addRow = null, legacyItems = null, popupSet = null, rowFactory = null;
            for (MethodData inv : builder.getInvokes()) {
                try {
                    if (inv.isConstructor()) continue;
                    Method m = inv.getMethodInstance(cl);
                    Class<?>[] p = m.getParameterTypes();
                    if (p.length == 2 && p[0] == String.class && p[1] == View.OnClickListener.class && m.getReturnType() == void.class) addRow = m;
                    else if (p.length == 1 && p[0] == List.class && m.getReturnType() == void.class && m.getDeclaringClass().getName().startsWith("X.")) popupSet = m;
                    else if (java.lang.reflect.Modifier.isStatic(m.getModifiers()) && m.getDeclaringClass() == helper && p.length == 2
                            && p[0].isInterface() && p[1] == String.class && m.getReturnType() == List.class) rowFactory = m;
                } catch (Throwable ignored) {}
            }
            if (addRow != null)
                for (MethodData inv : builder.getInvokes()) {
                    try {
                        if (inv.isConstructor() || !inv.getClassName().equals(addRow.getDeclaringClass().getName())) continue;
                        Method m = inv.getMethodInstance(cl);
                        if (m.getParameterCount() == 0 && m.getReturnType() == List.class) legacyItems = m;
                    } catch (Throwable ignored) {}
                }
            XposedBridge.hookMethod(build, new XC_MethodHook() {
                @Override protected void beforeHookedMethod(MethodHookParam p) { ps.reso.instaeclipse.utils.core.JitGuard.tick(p.method);
                    if (FeatureFlags.enableStoryDownload) trayHelper.set(p.thisObject);
                }
                @Override protected void afterHookedMethod(MethodHookParam p) { ps.reso.instaeclipse.utils.core.JitGuard.tick(p.method); trayHelper.remove(); }
            });
            if (addRow != null && legacyItems != null) {
                final Method add = addRow;
                XposedBridge.hookMethod(legacyItems, new XC_MethodHook() {
                    @Override protected void beforeHookedMethod(MethodHookParam p) { ps.reso.instaeclipse.utils.core.JitGuard.tick(p.method);
                        Object h = trayHelper.get();
                        if (h == null || !trayDialogsDone.add(p.thisObject)) return;
                        try {
                            Context c = AndroidAppHelper.currentApplication();
                            add.invoke(p.thisObject, I18n.t(c, R.string.ig_highlight_download_all), (View.OnClickListener) v -> trayDownload(h));
                        } catch (Throwable t) { ModuleLog.warn("(IE|HighlightTray) Could not add Download all to this highlight menu: " + t); }
                    }
                });
            }
            if (popupSet != null && rowFactory != null) {
                final Method factory = rowFactory;
                final Class<?> iface = rowFactory.getParameterTypes()[0];
                XposedBridge.hookMethod(popupSet, new XC_MethodHook() {
                    @Override protected void beforeHookedMethod(MethodHookParam p) { ps.reso.instaeclipse.utils.core.JitGuard.tick(p.method);
                        Object h = trayHelper.get();
                        if (h == null || !(p.args[0] instanceof List<?>)) return;
                        try {
                            Object click = java.lang.reflect.Proxy.newProxyInstance(cl, new Class<?>[]{iface}, (proxy, m, a) -> {
                                if (m.getReturnType() == boolean.class) return true;
                                if (m.getReturnType() == void.class && m.getDeclaringClass() == iface) { mainHandler.post(() -> trayDownload(h)); return null; }
                                if (m.getName().equals("hashCode")) return System.identityHashCode(proxy);
                                if (m.getName().equals("equals")) return proxy == a[0];
                                if (m.getName().equals("toString")) return "IEHighlightRow";
                                return null;
                            });
                            List<?> rows = (List<?>) factory.invoke(null, click, I18n.t(AndroidAppHelper.currentApplication(), R.string.ig_highlight_download_all));
                            @SuppressWarnings("unchecked") List<Object> list = (List<Object>) p.args[0];
                            try { list.addAll(rows); }
                            catch (UnsupportedOperationException e) { List<Object> copy = new ArrayList<>(list); copy.addAll(rows); p.args[0] = copy; }
                        } catch (Throwable t) { ModuleLog.warn("(IE|HighlightTray) Could not add Download all to this highlight menu: " + t); }
                    }
                });
            }
            findReelLoader(bridge, cl);
            if ((addRow != null && legacyItems != null) || (popupSet != null && rowFactory != null))
                ModuleLog.ok("(IE|HighlightTray) ready");
            else
                ModuleLog.warn("(IE|HighlightTray) Download all is not available in the highlight menu in this Instagram version");
            ModuleLog.detail("(IE|HighlightTray) " + helper.getName() + " legacy=" + (addRow != null && legacyItems != null)
                    + " popup=" + (popupSet != null && rowFactory != null) + " loader=" + (loaderGetter != null && loaderFetch != null));
        } catch (Throwable t) {
            ModuleLog.warn("(IE|HighlightTray) Download all is not available in the highlight menu: " + t);
        }
    }

    /** Long-press row tapped: download every item of the highlight the helper was built for. */
    private static void trayDownload(Object helper) {
        Object reel = reelClass != null ? readFieldByTypeName(helper, reelClass.getName()) : null;
        Object session = readFieldByTypeName(helper, "com.instagram.common.session.UserSession");
        Context ctx = (Context) readFieldByTypeName(helper, "android.app.Activity");
        if (ctx == null) ctx = ps.reso.instaeclipse.mods.ui.UIHookManager.getCurrentActivity();
        if (ctx == null || reel == null) { ModuleLog.error("(IE|HighlightTray) Could not download the highlight: it was not found on screen"); return; }
        List<?> items = highlightItemsWith(reel, session);
        ModuleLog.detail("(IE|HighlightTray) items loaded: " + (items != null ? items.size() : -1));
        if (items != null && !items.isEmpty()) { downloadHighlightItems(ctx, items); return; }
        loadThenDownload(ctx, reel, session); // highlight never opened: only its cover is known
    }

    // Reel loader = first ctor param of the class using "reel_store_on_story_unavailable" (X.02jU on
    // 452), obtained from a static (UserSession) -> loader getter. register(callback, reelId, String,
    // boolean) calls back at once when the reel is already loaded, else when fetch(reason enum, reelId,
    // module, Map) completes; callback = success(String id, boolean) / failure(String id). The loader
    // only keeps a WeakReference to the callback, so a strong one is held here until it fires.
    private static Method loaderGetter, loaderRegister, loaderUnregister, loaderFetch;
    private static final Set<Object> pendingLoads = Collections.synchronizedSet(new java.util.HashSet<>());

    private static void findReelLoader(DexKitBridge bridge, ClassLoader cl) {
        try {
            Class<?> loader = null;
            for (MethodData md : DexKitQueryCache.findMethod(bridge, FindMethod.create().matcher(MethodMatcher.create()
                    .usingStrings("reel_store_on_story_unavailable")))) {
                for (java.lang.reflect.Constructor<?> c : ps.reso.instaeclipse.utils.core.SafeReflect.constructors(cl.loadClass(md.getClassName())))
                    if (c.getParameterCount() > 0 && c.getParameterTypes()[0].getName().startsWith("X.")) { loader = c.getParameterTypes()[0]; break; }
                if (loader != null) break;
            }
            if (loader == null) return;
            for (MethodData md : DexKitQueryCache.findMethod(bridge, FindMethod.create().matcher(MethodMatcher.create()
                    .returnType(loader.getName()).paramTypes("com.instagram.common.session.UserSession")))) {
                Method m = md.getMethodInstance(cl);
                if (java.lang.reflect.Modifier.isStatic(m.getModifiers())) { m.setAccessible(true); loaderGetter = m; break; }
            }
            for (Method m : ps.reso.instaeclipse.utils.core.SafeReflect.methods(loader)) {
                Class<?>[] p = m.getParameterTypes();
                if (p.length == 4 && p[0].isInterface() && p[1] == String.class && p[2] == String.class && p[3] == boolean.class) loaderRegister = m;
                else if (p.length == 4 && p[0].isEnum() && p[1] == String.class && p[2] == String.class && p[3] == java.util.Map.class) loaderFetch = m;
            }
            if (loaderRegister != null)
                for (Method m : ps.reso.instaeclipse.utils.core.SafeReflect.methods(loader)) {
                    Class<?>[] p = m.getParameterTypes();
                    if (p.length == 2 && p[0] == loaderRegister.getParameterTypes()[0] && p[1] == String.class) loaderUnregister = m;
                }
        } catch (Throwable t) {
            ModuleLog.warn("(IE|HighlightTray) Highlights that were never opened cannot be downloaded with Download all: " + t);
        }
    }

    private static String highlightId(Object reel) {
        for (Class<?> k = reel.getClass(); k != null && k != Object.class; k = k.getSuperclass())
            for (Field f : ps.reso.instaeclipse.utils.core.SafeReflect.fields(k)) {
                if (f.getType() != String.class || java.lang.reflect.Modifier.isStatic(f.getModifiers())) continue;
                try { f.setAccessible(true); if (f.get(reel) instanceof String s && s.startsWith("highlight:")) return s; } catch (Throwable ignored) {}
            }
        return null;
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static void loadThenDownload(Context ctx, Object reel, Object session) {
        String id = highlightId(reel);
        if (id == null || session == null || loaderGetter == null || loaderRegister == null || loaderFetch == null) {
            ps.reso.instaeclipse.utils.toast.IeToast.makeText(ctx, I18n.t(ctx, R.string.ig_toast_no_media_for_post), Toast.LENGTH_SHORT).show();
            return;
        }
        try {
            Object loader = loaderGetter.invoke(null, session);
            Class<?> cbType = loaderRegister.getParameterTypes()[0];
            Object[] self = new Object[1];
            Object cb = java.lang.reflect.Proxy.newProxyInstance(cbType.getClassLoader(), new Class<?>[]{cbType}, (proxy, m, a) -> {
                switch (m.getName()) {
                    case "hashCode": return System.identityHashCode(proxy);
                    case "equals": return proxy == a[0];
                    case "toString": return "IEHighlightLoad";
                }
                if (a == null || a.length == 0 || !id.equals(a[0])) return null;
                boolean success = a.length == 2;
                mainHandler.post(() -> {
                    if (!pendingLoads.remove(self[0])) return; // fire once
                    try { if (loaderUnregister != null) loaderUnregister.invoke(loader, self[0], id); } catch (Throwable ignored) {}
                    List<?> items = success ? highlightItemsWith(reel, session) : null;
                    if (!success) ModuleLog.error("(IE|HighlightTray) Could not download the highlight: Instagram could not load it");
                    ModuleLog.detail("(IE|HighlightTray) load " + (success ? "ok" : "failed") + ": " + (items != null ? items.size() : -1) + " items");
                    downloadHighlightItems(ctx, items);
                });
                return null;
            });
            self[0] = cb;
            pendingLoads.add(cb);
            ps.reso.instaeclipse.utils.toast.IeToast.makeText(ctx, I18n.t(ctx, R.string.ig_highlight_loading), Toast.LENGTH_SHORT).show();
            loaderRegister.invoke(loader, cb, id, "profile", false);
            if (!pendingLoads.contains(cb)) return; // was already loaded: callback ran
            Class<? extends Enum> reason = (Class<? extends Enum>) loaderFetch.getParameterTypes()[0];
            Object why;
            try { why = Enum.valueOf(reason, "PROFILE_HIGHLIGHTS_TRAY"); } catch (Throwable e) { why = Enum.valueOf(reason, "ON_TAP"); }
            loaderFetch.invoke(loader, why, id, "profile", null);
            ModuleLog.detail("(IE|HighlightTray) fetching " + id);
            mainHandler.postDelayed(() -> { // give up after 20 s
                if (pendingLoads.remove(cb)) ps.reso.instaeclipse.utils.toast.IeToast.makeText(ctx, I18n.t(ctx, R.string.ig_toast_no_media_for_post), Toast.LENGTH_SHORT).show();
            }, 20000);
        } catch (Throwable t) {
            ModuleLog.error("(IE|HighlightTray) Could not download the highlight: " + t);
        }
    }

    // ── Story cache: capture each VIEWED story (per-page bind, fires for every story shown) ──
    private static java.lang.reflect.Method expiringGetter; // Media."expiring_at" getter, () -> Long

    private void resolveExpiringGetter(DexKitBridge bridge, ClassLoader cl) {
        try {
            if (DexKitCache.isCacheValid()) {
                java.lang.reflect.Method c = DexKitCache.loadMethod("StoryCache_expiring", cl);
                if (c != null) { c.setAccessible(true); expiringGetter = c; return; }
            }
            for (MethodData md : DexKitQueryCache.findMethod(bridge, FindMethod.create().matcher(MethodMatcher.create()
                    .declaredClass("com.instagram.feed.media.Media").paramCount(0)
                    .returnType("java.lang.Long").usingStrings("expiring_at")))) {
                try {
                    java.lang.reflect.Method m = md.getMethodInstance(cl);
                    m.setAccessible(true); expiringGetter = m;
                    DexKitCache.saveMethod("StoryCache_expiring", m);
                    break;
                } catch (Throwable ignored) {}
            }
        } catch (Throwable t) { ModuleLog.detail("(IE|StoryCache) expiring getter: " + t); }
    }

    /**
     * Hooks the story viewer's per-page bind (ReelViewerFragment.onCurrentActiveItemBound — anchored
     * by that stable string; the method name is obfuscated) which fires for EVERY story shown. Its
     * first ReelItem param is the current story; we record it to the story cache.
     */
    private void installStoryCaptureHook(DexKitBridge bridge, ClassLoader cl) {
        XC_MethodHook capture = new XC_MethodHook() {
            @Override protected void afterHookedMethod(MethodHookParam p) { ps.reso.instaeclipse.utils.core.JitGuard.tick(p.method);
                if (!FeatureFlags.cacheStories) return;
                Object reelItem = null;
                for (Object a : p.args) {
                    if (a != null && a.getClass().getName().equals("com.instagram.model.reels.ReelItem")) { reelItem = a; break; }
                }
                if (reelItem != null) captureFromReelItem(reelItem);
            }
        };
        try {
            int n = 0;
            for (MethodData md : DexKitQueryCache.findMethod(bridge, FindMethod.create().matcher(MethodMatcher.create()
                    .usingStrings("ReelViewerFragment.onCurrentActiveItemBound")))) {
                try { XposedBridge.hookMethod(md.getMethodInstance(cl), capture); n++; } catch (Throwable ignored) {}
            }
            if (n > 0) FeatureStatusTracker.setHooked("CacheStories");
            if (n > 0) ModuleLog.ok("(IE|StoryCache) ready");
            else ModuleLog.error("(IE|StoryCache) Could not turn on the story cache: the story viewer was not found in this Instagram version");
            ModuleLog.detail("(IE|StoryCache) capture hook: " + n + " method(s)");
        } catch (Throwable t) { ModuleLog.error("(IE|StoryCache) Could not turn on the story cache: " + t.getClass().getSimpleName() + ": " + t.getMessage()); }
    }

    private void captureFromReelItem(Object reelItem) {
        try {
            Object media = findMediaObject(reelItem);
            if (media == null) return;
            Context ctx = AndroidAppHelper.currentApplication();
            String id;
            try {
                Object rid = reelItem.getClass().getMethod("getId").invoke(reelItem);
                id = (rid instanceof String s && !s.isEmpty()) ? s.split("_")[0] : null;
            } catch (Throwable t) { id = null; }
            if (id == null || ps.reso.instaeclipse.utils.media.StoryCache.has(id)) return;
            List<String> urls = FeedVideoDownloadHook.extractAllUrlsFromMedia(ctx, media);
            if (urls == null || urls.isEmpty()) return;
            final String url = urls.get(0);
            final boolean video = FeedVideoDownloadHook.isVideoUrl(url);
            // Use the ReelItem-based username resolver (handles a ReelItem passed directly) — the
            // media-dictionary resolver doesn't populate for story media, giving "unknown".
            String author = extractUsernameFromReelItemHolder(reelItem);
            if (author == null || author.isEmpty()) author = FeedVideoDownloadHook.extractUsernameFromMediaObject(media);
            long expiring = 0;
            if (expiringGetter != null) {
                try {
                    Object v = expiringGetter.invoke(media);
                    if (v instanceof Long l && l > 0) expiring = l < 100000000000L ? l * 1000L : l; // sec→ms
                } catch (Throwable ignored) {}
            }
            final String fid = id, fauthor = author; final long fexp = expiring;
            FeedVideoDownloadHook.executor.submit(() ->
                    ps.reso.instaeclipse.utils.media.StoryCache.capture(fid, fauthor, url, video, fexp));
        } catch (Throwable t) { ModuleLog.detail("(IE|StoryCache) captureFromReelItem: " + t); }
    }

    /**
     * Every CharSequence[] option builder of the story-options helper class (X.0mt9 on 452).
     * IG 452 moved the "[INTERNAL] …" rows into a private List helper (A0c), so the builder used
     * for other people's stories (A0t) no longer contains the anchor string itself; taking all
     * CharSequence[] methods of the anchor's declaring class(es) catches it again.
     */
    public static java.util.Set<Method> storyOptionBuilders(List<MethodData> anchorMethods, ClassLoader classLoader) {
        java.util.Set<Method> out = new java.util.LinkedHashSet<>();
        java.util.Set<Class<?>> owners = new java.util.LinkedHashSet<>();
        for (MethodData md : anchorMethods) {
            try {
                Method m = md.getMethodInstance(classLoader);
                owners.add(m.getDeclaringClass());
                if (isCharSequenceArray(m.getReturnType())) out.add(m);
            } catch (Throwable ignored) {}
        }
        for (Class<?> c : owners) {
            for (Method m : ps.reso.instaeclipse.utils.core.SafeReflect.methods(c)) {
                if (isCharSequenceArray(m.getReturnType())) out.add(m);
            }
        }
        return out;
    }

    private static boolean isCharSequenceArray(Class<?> rt) {
        return rt.isArray() && CharSequence.class.isAssignableFrom(rt.getComponentType());
    }

    private static XC_MethodHook menuButtonInjector() {
        return new XC_MethodHook() {
            @Override protected void afterHookedMethod(MethodHookParam param) { ps.reso.instaeclipse.utils.core.JitGuard.tick(param.method);
                if (!FeatureFlags.enableStoryDownload || param.hasThrowable()) return;
                Object original = param.getResult();
                String label = I18n.t(AndroidAppHelper.currentApplication(), R.string.ig_dl_title);
                if (!StoryMenu.remember(param.args, label)) return;
                Object extended = StoryMenuOptions.appendDownload(original, label);
                if (extended != original) param.setResult(extended);
            }
        };
    }

    private void installHighlightButtonInjectorHook(DexKitBridge bridge, ClassLoader loader) {
        // The native Highlight sheet iterates two alternative ArrayList builders. Only one
        // carries the semantic anchor; discover its paired builder through that sheet's
        // call graph. Both must belong to the same exact ReelItem owner as the dispatcher.
        try {
            Class<?> itemType = loader.loadClass("com.instagram.model.reels.ReelItem");
            List<MethodData> anchors = new ArrayList<>();
            for (MethodData data : DexKitQueryCache.findMethod(bridge, FindMethod.create().matcher(MethodMatcher.create()
                    .returnType("java.util.ArrayList").usingStrings("story_highlight_action_sheet")))) {
                if (StoryMenuOptions.isOwnedListBuilder(data.getMethodInstance(loader), itemType)) {
                    anchors.add(data);
                }
            }
            if (anchors.size() != 1) {
                ModuleLog.warn("(IE|Story) Download is not available in the highlight menu in this Instagram version; stories still have it");
                ModuleLog.detail("(IE|Story) Highlight menu anchor unavailable or ambiguous (" + anchors.size() + " matches)");
                return;
            }
            MethodData anchor = anchors.get(0);
            String ownerName = anchor.getDeclaredClassName();
            Set<Method> builders = new LinkedHashSet<>();
            for (MethodData caller : DexKitQueryCache.callers(bridge, anchor)) {
                if (!ownerName.equals(caller.getDeclaredClassName())
                        || !"void".equals(caller.getReturnTypeName())) continue;
                for (MethodData invoked : caller.getInvokes()) {
                    if (!ownerName.equals(invoked.getDeclaredClassName())
                            || !"java.util.ArrayList".equals(invoked.getReturnTypeName())) continue;
                    Method method = invoked.getMethodInstance(loader);
                    if (StoryMenuOptions.isOwnedListBuilder(method, itemType)) builders.add(method);
                }
            }
            // No independent/global menu injection when the native sheet cannot be identified.
            if (!builders.contains(anchor.getMethodInstance(loader))) return;
            XC_MethodHook injector = menuButtonInjector();
            int hooked = 0;
            for (Method builder : builders) {
                XposedBridge.hookMethod(builder, injector);
                hooked++;
            }
            ModuleLog.detail("(IE|Story) Highlight injector hooked " + hooked + " builder(s)");
        } catch (Throwable error) {
            ModuleLog.warn("(IE|Story) Download is not available in the highlight menu; stories still have it: "
                    + error.getClass().getSimpleName() + ": " + error.getMessage());
        }
    }

    /** The original Story selectors, restricted to the already resolved selected Media. */
    static String originalVideoFromSelected(Object media) {
        return FeedVideoDownloadHook.bestVideoUrlFromMedia(media);
    }

    static String originalImageFromSelected(Context context, Object media) {
        String image = FeedVideoDownloadHook.imageUrlFromMedia(context, media);
        if (image != null && FeedVideoDownloadHook.isVideoUrl(image)) image = null;
        if (image != null) return image;
        List<CandidateInfo> candidates = new ArrayList<>();
        collectImageCandidates(media, candidates, Collections.newSetFromMap(new IdentityHashMap<>()), 0);
        if (!candidates.isEmpty()) {
            candidates.sort((a, b) -> Integer.compare(b.area, a.area));
            return candidates.get(0).url;
        }
        List<String> urls = new ArrayList<>();
        scanCdnUrls(media, urls, 0, Collections.newSetFromMap(new IdentityHashMap<>()));
        urls.removeIf(FeedVideoDownloadHook::isVideoUrl);
        return urls.isEmpty() ? null : pickBestUrl(urls);
    }

    // ── URL extraction ────────────────────────────────────────────────────────

    /** {@code direct}: holder IS the ReelItem (don't look for a nested ReelItem field). */
    private static StoryMediaOptions extractStoryMediaOptions(Context ctx, Object holder, boolean direct) {
        if (holder == null) return null;
        try {
            Object reelItem = direct ? holder : readFieldByTypeName(holder, "com.instagram.model.reels.ReelItem");
            ModuleLog.probe("(IE|Story) reelItem=" +
                    (reelItem != null ? reelItem.getClass().getName() : "null"));

            Object target = reelItem != null ? reelItem : holder;
            Object mediaObject = findMediaObject(target);
            // Every walk below stays inside the item's own Media when there is one: the
            // ReelItem's other Media field (A08) is the feed post a story ring was opened from.
            Object modelTarget = mediaObject != null ? mediaObject : target;

            // Use the same source-aware extractor as feed/reels first. It understands
            // Pando video_versions getters whose URLs no longer expose a video-looking path.
            String videoUrl = FeedVideoDownloadHook.bestVideoUrlFromMedia(modelTarget);

            // MediaExtKt knows the canonical image_versions2 URL and avoids choosing a
            // smaller music-sticker/album-art image when a Media object is available.
            String imageUrl = mediaObject != null
                    ? FeedVideoDownloadHook.imageUrlFromMedia(ctx, mediaObject) : null;
            if (imageUrl != null && FeedVideoDownloadHook.isVideoUrl(imageUrl)) {
                imageUrl = null;
            }

            // Walk the graph looking for image Candidate objects when the canonical
            // Media helper is unavailable.
            // A Candidate has a CDN URL string field + at least two int fields with
            // plausible pixel dimensions. Field names are obfuscated so we match by type
            // and value range. Pick the candidate with the largest width×height area.
            if (imageUrl == null) {
                List<CandidateInfo> candidates = new ArrayList<>();
                collectImageCandidates(modelTarget, candidates,
                        Collections.newSetFromMap(new IdentityHashMap<>()), 0);
                ModuleLog.probe("(IE|Story) imageCandidates=" + candidates.size());
                if (!candidates.isEmpty()) {
                    candidates.sort((a, b) -> Integer.compare(b.area, a.area));
                    imageUrl = candidates.get(0).url;
                    ModuleLog.probe("(IE|Story) bestCandidate area=" + candidates.get(0).area);
                }
            }

            // Last resort: split a raw CDN scan into image and video candidates. This
            // never silently labels an image cover as a video (the issue #204 failure).
            List<String> cdnUrls = new ArrayList<>();
            scanCdnUrls(modelTarget, cdnUrls, 0, Collections.newSetFromMap(new IdentityHashMap<>()));
            List<String> imageUrls = new ArrayList<>();
            for (String candidate : cdnUrls) {
                if (FeedVideoDownloadHook.isVideoUrl(candidate)) {
                    if (videoUrl == null) {
                        videoUrl = candidate;
                        FeedVideoDownloadHook.rememberVideoUrl(candidate);
                    }
                } else {
                    imageUrls.add(candidate);
                }
            }
            if (imageUrl == null && !imageUrls.isEmpty()) imageUrl = pickBestUrl(imageUrls);

            if (imageUrl == null && videoUrl == null) return null;
            return new StoryMediaOptions(imageUrl, videoUrl);

        } catch (Throwable t) {
            ModuleLog.warn("(IE|Story) Could not read one story item: " + t);
        }
        return null;
    }

    private static volatile boolean loggedMediaProbe;

    /** Resolves the Media nested in ReelItem without relying on obfuscated method names. */
    private static Object findMediaObject(Object obj) {
        if (obj == null) return null;
        if (obj.getClass().getName().equals("com.instagram.feed.media.Media")) return obj;

        // The item's own Media field. Never the first Media-typed one: on IG 451 that is A08,
        // the feed post a story ring was opened from, which X.010G copies over from Reel.A0F.
        Field own = OwnMedia.field(obj.getClass(), "com.instagram.feed.media.Media");
        if (own != null) {
            // null = an item without media (IG's own A0G() throws "Required value was null.")
            try { return own.get(obj); } catch (Throwable ignored) { return null; }
        }

        // Fallback for builds without a single own Media field: invoke the getters.
        if (!loggedMediaProbe) {
            loggedMediaProbe = true;
            ModuleLog.detail("(IE|Story) no own Media field on " + obj.getClass().getName()
                    + ", probing its getters");
        }
        Class<?> cls = obj.getClass();
        while (cls != null && cls != Object.class) {
            for (Method method : ps.reso.instaeclipse.utils.core.SafeReflect.methods(cls)) {
                if (method.getParameterCount() != 0
                        || java.lang.reflect.Modifier.isStatic(method.getModifiers())) continue;
                Class<?> returnType = method.getReturnType();
                if (returnType.isPrimitive() || returnType == String.class
                        || returnType == void.class) continue;
                try {
                    method.setAccessible(true);
                    Object result = method.invoke(obj);
                    if (result != null && result.getClass().getName()
                            .equals("com.instagram.feed.media.Media")) return result;
                } catch (Throwable ignored) {}
            }
            cls = cls.getSuperclass();
        }
        return null;
    }

    /**
     * A ReelItem's own Media field, kept free of Android types for unit tests. IG 446/451 give
     * ReelItem two Media fields: the final one its constructor sets (A12/A17, the field A0G() and
     * Cgu() return) and the mutable A08 holding the feed post a story ring was opened from.
     */
    static final class OwnMedia {
        private OwnMedia() { }

        /** The only instance field of {@code typeName}, else the only final one; null when ambiguous. */
        static Field field(Class<?> cls, String typeName) {
            Field only = null, onlyFinal = null;
            int all = 0, finals = 0;
            for (Class<?> c = cls; c != null && c != Object.class; c = c.getSuperclass()) {
                for (Field f : ps.reso.instaeclipse.utils.core.SafeReflect.fields(c)) {
                    int mod = f.getModifiers();
                    if (java.lang.reflect.Modifier.isStatic(mod) || !f.getType().getName().equals(typeName)) continue;
                    all++;
                    only = f;
                    if (java.lang.reflect.Modifier.isFinal(mod)) { finals++; onlyFinal = f; }
                }
            }
            Field own = all == 1 ? only : finals == 1 ? onlyFinal : null;
            if (own == null) return null;
            try { own.setAccessible(true); } catch (Throwable ignored) { return null; }
            return own;
        }
    }

    /** Reads the first field whose declared type name equals {@code typeName}. */
    private static Object readFieldByTypeName(Object obj, String typeName) {
        Class<?> cls = obj.getClass();
        while (cls != null && cls != Object.class) {
            for (Field f : ps.reso.instaeclipse.utils.core.SafeReflect.fields(cls)) {
                if (f.getType().getName().equals(typeName)) {
                    f.setAccessible(true);
                    try { return f.get(obj); } catch (Throwable ignored) {}
                }
            }
            cls = cls.getSuperclass();
        }
        return null;
    }

    /** Depth-limited field-graph scan for Instagram CDN URL strings. */
    private static void scanCdnUrls(Object obj, List<String> out, int depth, Set<Object> visited) {
        if (obj == null || depth > 5 || out.size() >= 20) return;
        if (!visited.add(obj)) return;
        String cn = obj.getClass().getName();
        if (cn.startsWith("android.") || cn.startsWith("java.lang.") || cn.startsWith("kotlin.")) return;

        Class<?> cls = obj.getClass();
        while (cls != null && cls != Object.class) {
            for (Field f : ps.reso.instaeclipse.utils.core.SafeReflect.fields(cls)) {
                try {
                    if (java.lang.reflect.Modifier.isStatic(f.getModifiers())) continue;
                    f.setAccessible(true);
                    Object val = f.get(obj);
                    if (val == null) continue;
                    if (val instanceof String s) {
                        if (isCdnUrl(s) && !out.contains(s)) out.add(s);
                    } else if (val instanceof List<?> list) {
                        for (Object item : list) scanCdnUrls(item, out, depth + 1, visited);
                    } else {
                        String vcn = val.getClass().getName();
                        if (vcn.startsWith("X.") || vcn.startsWith("com.instagram.")
                                || vcn.startsWith("com.facebook.")) {
                            scanCdnUrls(val, out, depth + 1, visited);
                        }
                    }
                } catch (Throwable ignored) {}
            }
            cls = cls.getSuperclass();
        }
    }

    // ── Image candidate scanner ───────────────────────────────────────────────

    private static final class CandidateInfo {
        final String url;
        final int    area;
        CandidateInfo(String url, int area) { this.url = url; this.area = area; }
    }

    /**
     * Walks the object graph looking for Instagram image Candidate objects.
     * A Candidate is identified by having:
     *   • At least one String field/method that is a CDN image URL (not video)
     *   • At least two int/long fields/methods whose values are plausible pixel dimensions (50–20 000 px)
     *
     * Field names are ignored — they are obfuscated in Instagram builds.
     * No-arg methods are also probed to handle Pando/LiveTree JNI-backed nodes where
     * data is not exposed as Java fields (fixes lower-quality photos on some story types).
     * The two largest plausible-dimension ints are multiplied to estimate the area.
     */
    /** {url, area} of the largest image candidate reachable from {@code media}, or null. */
    static Object[] largestImage(Object media) {
        List<CandidateInfo> candidates = new ArrayList<>();
        collectImageCandidates(media, candidates, Collections.newSetFromMap(new IdentityHashMap<>()), 0);
        CandidateInfo best = null;
        for (CandidateInfo c : candidates) if (best == null || c.area > best.area) best = c;
        return best == null ? null : new Object[]{best.url, best.area};
    }

    private static void collectImageCandidates(Object obj, List<CandidateInfo> out,
                                               Set<Object> visited, int depth) {
        if (obj == null || depth > 7 || out.size() >= 40) return;
        if (!visited.add(obj)) return;

        String cn = obj.getClass().getName();
        if (cn.startsWith("android.") || cn.startsWith("java.lang.") || cn.startsWith("kotlin.")) return;

        // Scan this object's own fields looking for (url + dims) pattern
        String candidateUrl = null;
        List<Integer> dims = new ArrayList<>();

        Class<?> cls = obj.getClass();
        while (cls != null && cls != Object.class) {
            for (Field f : ps.reso.instaeclipse.utils.core.SafeReflect.fields(cls)) {
                if (java.lang.reflect.Modifier.isStatic(f.getModifiers())) continue;
                f.setAccessible(true);
                try {
                    if (f.getType() == String.class) {
                        String v = (String) f.get(obj);
                        if (v != null && isCdnUrl(v) && !isVideoUrl(v)) candidateUrl = v;
                    } else if (f.getType() == int.class) {
                        int v = f.getInt(obj);
                        if (v >= 50 && v <= 20_000) dims.add(v);
                    } else if (f.getType() == long.class) {
                        long v = f.getLong(obj);
                        if (v >= 50 && v <= 20_000) dims.add((int) v);
                    }
                } catch (Throwable ignored) {}
            }
            cls = cls.getSuperclass();
        }

        // Method probe for Pando/LiveTree JNI-backed nodes — data not exposed as Java fields
        if (cn.startsWith("X.") || cn.startsWith("com.instagram.") || cn.startsWith("com.facebook.")) {
            cls = obj.getClass();
            while (cls != null && cls != Object.class) {
                for (Method m : ps.reso.instaeclipse.utils.core.SafeReflect.methods(cls)) {
                    if (m.getParameterCount() != 0) continue;
                    try {
                        m.setAccessible(true);
                        Class<?> ret = m.getReturnType();
                        if (ret == String.class) {
                            Object r = m.invoke(obj);
                            if (r instanceof String s && isCdnUrl(s) && !isVideoUrl(s)
                                    && candidateUrl == null) candidateUrl = s;
                        } else if (ret == int.class) {
                            Object r = m.invoke(obj);
                            if (r instanceof Integer v && v >= 50 && v <= 20_000) dims.add(v);
                        } else if (ret == long.class) {
                            Object r = m.invoke(obj);
                            if (r instanceof Long v && v >= 50 && v <= 20_000) dims.add((int)(long) v);
                        }
                    } catch (Throwable ignored) {}
                }
                cls = cls.getSuperclass();
            }
        }

        if (candidateUrl != null && dims.size() >= 2) {
            dims.sort(Collections.reverseOrder());
            out.add(new CandidateInfo(candidateUrl, dims.get(0) * dims.get(1)));
            return; // leaf candidate — don't recurse further into it
        }

        // Not a candidate — recurse into Instagram/Facebook/X. objects and lists
        cls = obj.getClass();
        while (cls != null && cls != Object.class) {
            for (Field f : ps.reso.instaeclipse.utils.core.SafeReflect.fields(cls)) {
                try {
                    if (java.lang.reflect.Modifier.isStatic(f.getModifiers())) continue;
                    f.setAccessible(true);
                    Object val = f.get(obj);
                    if (val == null) continue;
                    if (val instanceof List<?> list) {
                        for (Object item : list)
                            collectImageCandidates(item, out, visited, depth + 1);
                    } else if (!(val instanceof String)) {
                        String vcn = val.getClass().getName();
                        if (vcn.startsWith("X.") || vcn.startsWith("com.instagram.")
                                || vcn.startsWith("com.facebook.")) {
                            collectImageCandidates(val, out, visited, depth + 1);
                        }
                    }
                } catch (Throwable ignored) {}
            }
            cls = cls.getSuperclass();
        }
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private static boolean isCdnUrl(String url) {
        if (url == null || url.isEmpty()) return false;
        if (!url.startsWith("http://") && !url.startsWith("https://")) return false;
        if (!url.contains("cdninstagram.com") && !url.contains("fbcdn.net")) return false;
        if (url.contains("/t51.") && url.contains("-19/")) return false; // profile pics
        return true;
    }

    private static boolean isVideoUrl(String url) {
        return FeedVideoDownloadHook.isVideoUrl(url);
    }

    /**
     * Prefer video URLs; among images pick the one with the largest pixel area.
     * Instagram embeds resolution as NNNxNNN in CDN paths (e.g. 1080x1920), so
     * parsing it directly is the most reliable way to select the full-size copy.
     */
    private static String pickBestUrl(List<String> urls) {
        for (String u : urls) if (isVideoUrl(u)) return u;
        String best = null;
        int bestArea = 0;
        for (String u : urls) {
            int area = parseUrlArea(u);
            if (area > bestArea) { bestArea = area; best = u; }
        }
        return best != null ? best : urls.get(0);
    }

    /** Extracts the largest NNNxNNN area found inside a CDN URL. */
    private static int parseUrlArea(String url) {
        int maxArea = 0;
        int i = 0;
        while (i < url.length()) {
            // Find a digit run
            if (!Character.isDigit(url.charAt(i))) { i++; continue; }
            int numStart = i;
            while (i < url.length() && Character.isDigit(url.charAt(i))) i++;
            // Must be followed by 'x'
            if (i >= url.length() || url.charAt(i) != 'x') continue;
            i++; // skip 'x'
            if (i >= url.length() || !Character.isDigit(url.charAt(i))) continue;
            int numMid = i;
            while (i < url.length() && Character.isDigit(url.charAt(i))) i++;
            try {
                int w = Integer.parseInt(url.substring(numStart, numMid - 1));
                int h = Integer.parseInt(url.substring(numMid, i));
                int area = w * h;
                if (area > maxArea) maxArea = area;
            } catch (NumberFormatException ignored) {}
        }
        return maxArea;
    }

    // ── Username extraction ───────────────────────────────────────────────────

    /**
     * Tries to extract the story author's username from the holder or ReelItem object.
     * ReelItem is non-obfuscated so getUser() and getUsername() are stable method names.
     */
    private static String extractUsernameFromReelItemHolder(Object holder) {
        if (holder == null) {
            ModuleLog.probe("(IE|Story|Username) holder is null");
            return null;
        }
        ModuleLog.probe("(IE|Story|Username) searching in holder=" + holder.getClass().getName());
        try {
            // Step 1: find the ReelItem field on the holder
            Object reelItem = null;
            Class<?> cls = holder.getClass();
            while (cls != null && cls != Object.class) {
                for (Field f : ps.reso.instaeclipse.utils.core.SafeReflect.fields(cls)) {
                    f.setAccessible(true);
                    try {
                        Object val = f.get(holder);
                        if (val != null && val.getClass().getName()
                                .equals("com.instagram.model.reels.ReelItem")) {
                            reelItem = val;
                            ModuleLog.probe("(IE|Story|Username) found ReelItem in field="
                                    + f.getName() + " on " + cls.getName());
                            break;
                        }
                    } catch (Throwable ignored) {}
                }
                if (reelItem != null) break;
                cls = cls.getSuperclass();
            }
            if (reelItem == null && holder.getClass().getName()
                    .equals("com.instagram.model.reels.ReelItem")) {
                reelItem = holder;
                ModuleLog.probe("(IE|Story|Username) holder is itself a ReelItem");
            }
            if (reelItem == null) {
                ModuleLog.probe("(IE|Story|Username) ReelItem not found in holder");
                return null;
            }

            // Step 2: probe all no-arg non-primitive methods on ReelItem.
            // Priority: find a method returning com.instagram.user.model.User.
            // Fallback: if a method returns com.instagram.feed.media.Media → delegate to feed extractor.
            for (Method m : ps.reso.instaeclipse.utils.core.SafeReflect.methods(reelItem.getClass())) {
                if (m.getParameterCount() != 0) continue;
                Class<?> ret = m.getReturnType();
                if (ret.isPrimitive() || ret == String.class || ret == void.class) continue;
                try {
                    m.setAccessible(true);
                    Object candidate = m.invoke(reelItem);
                    if (candidate == null) continue;

                    String candidateClass = candidate.getClass().getName();

                    // Direct User object — use DexKit-resolved getter (stable int constant -265713450)
                    if (candidateClass.equals("com.instagram.user.model.User")) {
                        String username = UserUtils.callUsernameGetter(candidate);
                        if (username != null) {
                            ModuleLog.probe("(IE|Story|Username) reelItem." + m.getName() + "() [User] → " + username);
                            return username;
                        }
                        continue;
                    }

                    // Media object — delegate to feed extractor (has LiveTreeMediaDict path)
                    if (candidateClass.equals("com.instagram.feed.media.Media")) {
                        String username = FeedVideoDownloadHook.extractUsernameFromMediaObject(candidate);
                        if (username != null) {
                            ModuleLog.probe("(IE|Story|Username) reelItem." + m.getName()
                                    + "() [Media] → " + username);
                            return username;
                        }
                        continue; // don't probe String methods on Media
                    }
                } catch (Throwable ignored) {}
            }

            ModuleLog.probe("(IE|Story|Username) username not found on ReelItem methods");
        } catch (Throwable t) {
            ModuleLog.detail("(IE|Story|Username) username lookup failed: " + t);
        }
        return null;
    }

    /** Extracts the short media ID from the ReelItem held by the holder (first segment of getId()). */
    private static String extractMediaIdFromReelItemHolder(Object holder) {
        if (holder == null) return null;
        try {
            Object reelItem = readFieldByTypeName(holder, "com.instagram.model.reels.ReelItem");
            if (reelItem == null && holder.getClass().getName().equals("com.instagram.model.reels.ReelItem")) {
                reelItem = holder;
            }
            if (reelItem == null) return null;
            Object id = reelItem.getClass().getMethod("getId").invoke(reelItem);
            if (id instanceof String s && !s.isEmpty()) return s.split("_")[0];
        } catch (Throwable ignored) {}
        return null;
    }
}
