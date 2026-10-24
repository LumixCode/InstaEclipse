package ps.reso.instaeclipse.mods.misc;

import android.app.Activity;
import android.app.Dialog;
import android.content.Context;
import android.content.DialogInterface;
import android.content.res.Resources;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.widget.Toast;

import org.luckypray.dexkit.DexKitBridge;
import org.luckypray.dexkit.query.FindMethod;
import org.luckypray.dexkit.query.matchers.MethodMatcher;

import java.lang.reflect.Field;
import java.lang.reflect.Member;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.IdentityHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

import ps.reso.instaeclipse.R;
import ps.reso.instaeclipse.mods.media.StoryDownloadHook;
import ps.reso.instaeclipse.mods.ui.UIHookManager;
import ps.reso.instaeclipse.utils.core.DexKitCache;
import ps.reso.instaeclipse.utils.core.DexKitQueryCache;
import ps.reso.instaeclipse.utils.core.JitGuard;
import ps.reso.instaeclipse.utils.core.SafeReflect;
import ps.reso.instaeclipse.utils.i18n.I18n;
import ps.reso.instaeclipse.utils.log.ModuleLog;
import ps.reso.instaeclipse.utils.toast.IeToast;
import ps.reso.instaeclipse.xposedcompat.XC_MethodHook;
import ps.reso.instaeclipse.xposedcompat.XposedBridge;

public final class StoryMenu {

    public static final int INFO = 0;
    public static final int MENTIONS = 1;
    public static final int LINKS = 2;

    public interface Row {
        boolean enabled();

        void rows(Object media, List<CharSequence> out);

        boolean owns(CharSequence tapped);

        void tap(CharSequence tapped, Object media, Context ctx);
    }

    private static final class Entry {
        final int order;
        final Row row;

        Entry(int order, Row row) {
            this.order = order;
            this.row = row;
        }
    }

    private static final String ANCHOR = "[INTERNAL] Pause Playback";
    private static final String BUILDERS = "StoryMenuBuilders_v1";
    private static final String DISPATCHERS = "StoryMenuDispatchers_v1";
    private static final String SHARED_BUILDERS = "MentionButtons";
    private static final String REEL_ITEM = "com.instagram.model.reels.ReelItem";
    private static final String MEDIA = "com.instagram.feed.media.Media";
    private static final int GRAPH_MAX_DEPTH = 6;
    private static final Comparator<Member> BY_NAME = Comparator.comparing(Member::getName);

    private static final Handler mainHandler = new Handler(Looper.getMainLooper());
    private static final List<Entry> entries = new CopyOnWriteArrayList<>();
    private static final Set<Class<?>> menus = ConcurrentHashMap.newKeySet();
    private static final Map<Class<?>, Field> storyFields = new ConcurrentHashMap<>();
    private static final Map<Class<?>, Field> activityFields = new ConcurrentHashMap<>();
    private static final Map<Class<?>, Field[]> graphFields = new ConcurrentHashMap<>();
    private static final Map<Class<?>, Boolean> skippedTypes = new ConcurrentHashMap<>();
    private static final Map<Object, Set<String>> shown = Collections.synchronizedMap(new WeakHashMap<>());

    private static Boolean installed;
    private static volatile List<Method> builders = Collections.emptyList();
    private static volatile List<Method> dispatchers = Collections.emptyList();
    private static volatile List<Method> dialogs = Collections.emptyList();
    private static volatile List<Method> mediaGetters = Collections.emptyList();

    private StoryMenu() {}

    public static synchronized boolean install(DexKitBridge bridge, ClassLoader classLoader) {
        if (installed != null) return installed;
        installed = false;
        try {
            List<Method> b = DexKitCache.isCacheValid() ? DexKitCache.loadMethods(BUILDERS, classLoader) : null;
            List<Method> d = b == null || b.isEmpty() ? null : DexKitCache.loadMethods(DISPATCHERS, classLoader);
            if (b == null || b.isEmpty() || d == null || d.isEmpty()) {
                b = new ArrayList<>(StoryDownloadHook.storyOptionBuilders(DexKitQueryCache.findMethod(bridge, FindMethod.create()
                        .matcher(MethodMatcher.create().usingStrings(ANCHOR))), classLoader));
                b.sort(BY_NAME);
                d = dispatchersOf(b);
                if (!b.isEmpty()) {
                    DexKitCache.saveMethods(BUILDERS, b);
                    DexKitCache.saveMethods(SHARED_BUILDERS, b);
                }
                if (!d.isEmpty()) DexKitCache.saveMethods(DISPATCHERS, d);
            }
            if (b.isEmpty() || d.isEmpty()) {
                ModuleLog.error("(IE|StoryMenu) Story menu rows will not appear: the story menu was not found in this Instagram version");
                return false;
            }
            resolveShape(b);
            int rows = hookAll(b, new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) { JitGuard.tick(param.method);
                    try {
                        addRows(param);
                    } catch (Throwable t) {
                        ModuleLog.warn("(IE|StoryMenu) Could not add rows to this story menu: " + t);
                    }
                }
            });
            int taps = hookAll(d, new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) { JitGuard.tick(param.method);
                    try {
                        onTap(param);
                    } catch (Throwable t) {
                        ModuleLog.error("(IE|StoryMenu) Could not open this story menu row", t);
                    }
                }
            });
            hookAll(dialogs, new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) { JitGuard.tick(param.method);
                    try {
                        wrapDialog(param);
                    } catch (Throwable t) {
                        ModuleLog.warn("(IE|StoryMenu) Rows may not open from the older story menu: " + t);
                    }
                }
            });
            builders = b;
            dispatchers = d;
            ModuleLog.detail("(IE|StoryMenu) " + describe());
            installed = rows > 0 && taps > 0;
            if (!installed) ModuleLog.error("(IE|StoryMenu) Story menu rows will not appear: the story menu could not be changed");
        } catch (Throwable t) {
            ModuleLog.error("(IE|StoryMenu) Story menu rows will not appear", t);
        }
        return installed;
    }

    public static synchronized void register(int order, Row row) {
        entries.removeIf(e -> e.order == order);
        int at = 0;
        while (at < entries.size() && entries.get(at).order < order) at++;
        entries.add(at, new Entry(order, row));
    }

    public static synchronized String describe() {
        return "builders=" + names(builders) + " dispatchers=" + names(dispatchers) + " dialog=" + names(dialogs)
                + " story=" + names(storyFields.values()) + " media=" + names(mediaGetters) + " rows=" + entries.size();
    }

    private static String names(Collection<? extends Member> members) {
        List<String> out = new ArrayList<>();
        for (Member m : members) out.add(m.getDeclaringClass().getName() + "." + m.getName());
        return String.join(",", out);
    }

    private static int hookAll(List<Method> methods, XC_MethodHook hook) {
        int hooked = 0;
        for (Method m : methods) {
            try {
                XposedBridge.hookMethod(m, hook);
                hooked++;
            } catch (Throwable t) {
                ModuleLog.warn("(IE|StoryMenu) Some story menus may miss their rows: " + t);
            }
        }
        return hooked;
    }

    private static Set<Class<?>> owners(List<Method> methods) {
        Set<Class<?>> out = new LinkedHashSet<>();
        for (Method m : methods) out.add(m.getDeclaringClass());
        return out;
    }

    private static List<Method> dispatchersOf(List<Method> builderMethods) {
        List<Method> out = new ArrayList<>();
        for (Class<?> c : owners(builderMethods)) {
            for (Method m : SafeReflect.methods(c)) {
                if (!Modifier.isStatic(m.getModifiers()) || m.getReturnType() != void.class) continue;
                Class<?>[] p = m.getParameterTypes();
                if (p.length == 0 || p[p.length - 1] != CharSequence.class) continue;
                if (Arrays.asList(p).contains(c)) out.add(m);
            }
        }
        out.sort(BY_NAME);
        return out;
    }

    private static void resolveShape(List<Method> builderMethods) {
        List<Method> factories = new ArrayList<>();
        List<Method> getters = new ArrayList<>();
        for (Class<?> c : owners(builderMethods)) {
            menus.add(c);
            for (Method m : SafeReflect.methods(c)) {
                Class<?>[] p = m.getParameterTypes();
                if (Modifier.isStatic(m.getModifiers()) && m.getReturnType() == Dialog.class && p.length == 4
                        && p[0] == DialogInterface.OnClickListener.class && p[1] == DialogInterface.OnDismissListener.class
                        && p[2] == c && p[3] == CharSequence[].class) factories.add(m);
            }
            for (Field f : SafeReflect.fields(c)) {
                if (Modifier.isStatic(f.getModifiers())) continue;
                if (!storyFields.containsKey(c) && f.getType().getName().equals(REEL_ITEM)) {
                    f.setAccessible(true);
                    storyFields.put(c, f);
                    if (getters.isEmpty()) getters.addAll(mediaGettersOf(f.getType()));
                } else if (!activityFields.containsKey(c) && Activity.class.isAssignableFrom(f.getType())) {
                    f.setAccessible(true);
                    activityFields.put(c, f);
                }
            }
        }
        factories.sort(BY_NAME);
        dialogs = factories;
        mediaGetters = getters;
    }

    private static List<Method> mediaGettersOf(Class<?> item) {
        List<Method> out = new ArrayList<>();
        for (Method m : SafeReflect.publicMethods(item)) {
            if (Modifier.isStatic(m.getModifiers()) || m.getParameterCount() != 0) continue;
            if (!m.getReturnType().getName().equals(MEDIA)) continue;
            m.setAccessible(true);
            out.add(m);
        }
        out.sort(BY_NAME);
        return out;
    }

    private static List<Row> active() {
        List<Row> out = new ArrayList<>();
        for (Entry e : entries) if (e.row.enabled()) out.add(e.row);
        return out;
    }

    private static Row ownerOf(CharSequence label) {
        if (label == null) return null;
        for (Entry e : entries) if (e.row.enabled() && e.row.owns(label)) return e.row;
        return null;
    }

    private static void addRows(XC_MethodHook.MethodHookParam param) {
        List<Row> active = active();
        if (active.isEmpty()) return;
        if (!(param.getResult() instanceof CharSequence[] original)) return;
        Object menu = menuOf(param.args);
        Object media = mediaOf(menu, param.args);
        List<CharSequence> add = new ArrayList<>();
        for (Row row : active) {
            try {
                row.rows(media, add);
            } catch (Throwable t) {
                ModuleLog.warn("(IE|StoryMenu) A row could not be added to this story menu: " + t);
            }
        }
        CharSequence[] extended = StoryMenuRows.append(original, add);
        if (extended == original) return;
        if (menu != null) {
            Set<String> labels = new LinkedHashSet<>();
            for (int i = original.length; i < extended.length; i++) labels.add(extended[i].toString());
            synchronized (shown) {
                Set<String> known = shown.get(menu);
                if (known == null) shown.put(menu, labels);
                else known.addAll(labels);
            }
        }
        param.setResult(extended);
    }

    private static boolean added(Object menu, CharSequence label) {
        if (menu == null) return true;
        synchronized (shown) {
            Set<String> labels = shown.get(menu);
            return labels != null && labels.contains(label.toString());
        }
    }

    private static void onTap(XC_MethodHook.MethodHookParam param) {
        CharSequence tapped = StoryMenuRows.tapped(param.args);
        Row owner = ownerOf(tapped);
        if (owner == null) return;
        Object menu = menuOf(param.args);
        if (!added(menu, tapped)) return;
        Object media = mediaOf(menu, param.args);
        Context ctx = contextOf(menu, param.args);
        param.setResult(null);
        deliver(owner, tapped.toString(), media, ctx);
    }

    private static void wrapDialog(XC_MethodHook.MethodHookParam param) {
        if (!(param.args[0] instanceof DialogInterface.OnClickListener instagram)
                || !(param.args[3] instanceof CharSequence[] items)) return;
        Object menu = param.args[2];
        boolean any = false;
        for (CharSequence item : items) {
            if (ownerOf(item) != null && added(menu, item)) {
                any = true;
                break;
            }
        }
        if (!any) return;
        Object[] args = {menu};
        param.args[0] = (DialogInterface.OnClickListener) (dialog, which) -> {
            CharSequence label = StoryMenuRows.ownedAt(items, which, item -> ownerOf(item) != null && added(menu, item));
            Row owner = ownerOf(label);
            if (owner == null) {
                instagram.onClick(dialog, which);
                return;
            }
            try {
                dialog.dismiss();
            } catch (Throwable ignored) {}
            deliver(owner, label.toString(), mediaOf(menu, args), contextOf(menu, args));
        };
    }

    private static void deliver(Row owner, String label, Object media, Context ctx) {
        if (ctx == null) {
            ModuleLog.error("(IE|StoryMenu) Could not open \"" + label + "\": no open screen found");
            return;
        }
        if (media == null) {
            IeToast.makeText(ctx, I18n.t(ctx, R.string.ig_toast_story_unavailable), Toast.LENGTH_SHORT).show();
            return;
        }
        mainHandler.post(() -> {
            try {
                owner.tap(label, media, ctx);
            } catch (Throwable t) {
                ModuleLog.error("(IE|StoryMenu) Could not open \"" + label + "\"", t);
            }
        });
    }

    private static Object menuOf(Object[] args) {
        for (Object a : args) if (a != null && menus.contains(a.getClass())) return a;
        return null;
    }

    private static Object mediaOf(Object menu, Object[] args) {
        Field story = menu == null ? null : storyFields.get(menu.getClass());
        if (story != null) return storyMedia(menu, story);
        Set<Object> visited = Collections.newSetFromMap(new IdentityHashMap<>());
        for (Object a : args) {
            Object media = findMediaInGraph(a, 0, visited);
            if (media != null) return media;
        }
        return null;
    }

    private static Object storyMedia(Object menu, Field story) {
        Object item;
        try {
            item = story.get(menu);
        } catch (Throwable t) {
            return null;
        }
        if (item == null) return null;
        for (Method g : mediaGetters) {
            try {
                Object media = g.invoke(item);
                if (media != null) return media;
            } catch (Throwable ignored) {}
        }
        return null;
    }

    private static Context contextOf(Object menu, Object[] args) {
        for (Object a : args) if (a instanceof Context c) return c;
        Field activity = menu == null ? null : activityFields.get(menu.getClass());
        if (activity != null) {
            try {
                if (activity.get(menu) instanceof Context c) return c;
            } catch (Throwable ignored) {}
        }
        return UIHookManager.getCurrentActivity();
    }

    private static Object findMediaInGraph(Object obj, int depth, Set<Object> visited) {
        if (obj == null || depth > GRAPH_MAX_DEPTH || !walkable(obj) || !visited.add(obj)) return null;
        if (obj.getClass().getName().equals(MEDIA)) return obj;
        for (Field f : graphFields(obj.getClass())) {
            Object val;
            try {
                val = f.get(obj);
            } catch (Throwable ignored) {
                continue;
            }
            if (val == null) continue;
            if (val.getClass().getName().equals(MEDIA)) return val;
            Object found = findMediaInGraph(val, depth + 1, visited);
            if (found != null) return found;
        }
        return null;
    }

    private static boolean walkable(Object obj) {
        String n = obj.getClass().getName();
        if (!n.startsWith("com.instagram.") && !n.startsWith("com.facebook.") && !n.startsWith("X.")) return false;
        return !skippedTypes.computeIfAbsent(obj.getClass(), StoryMenu::skipped);
    }

    private static boolean skipped(Class<?> c) {
        if (c.isEnum() || Activity.class.isAssignableFrom(c) || Dialog.class.isAssignableFrom(c)
                || View.class.isAssignableFrom(c) || Context.class.isAssignableFrom(c)
                || Resources.class.isAssignableFrom(c)) return true;
        for (Class<?> k = c; k != null; k = k.getSuperclass()) {
            if (k.getName().equals("androidx.fragment.app.Fragment") || k.getName().equals("android.app.Fragment")) return true;
        }
        return false;
    }

    private static Field[] graphFields(Class<?> type) {
        Field[] cached = graphFields.get(type);
        if (cached != null) return cached;
        List<Field> out = new ArrayList<>();
        for (Class<?> c = type; c != null && c != Object.class; c = c.getSuperclass()) {
            for (Field f : SafeReflect.fields(c)) {
                Class<?> ft = f.getType();
                if (Modifier.isStatic(f.getModifiers()) || ft.isPrimitive() || ft.isArray()) continue;
                try {
                    f.setAccessible(true);
                    out.add(f);
                } catch (Throwable ignored) {}
            }
        }
        Field[] fields = out.toArray(new Field[0]);
        graphFields.put(type, fields);
        return fields;
    }
}
