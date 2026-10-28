package ps.reso.instaeclipse.mods.feed;

import android.content.Context;
import android.text.InputType;
import android.widget.EditText;

import org.json.JSONObject;
import org.luckypray.dexkit.query.FindMethod;
import org.luckypray.dexkit.query.matchers.MethodMatcher;

import java.io.File;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Predicate;

import ps.reso.instaeclipse.R;
import ps.reso.instaeclipse.mods.ui.ProfileSpoofHook;
import ps.reso.instaeclipse.mods.ui.UIHookManager;
import ps.reso.instaeclipse.utils.core.DexKitCache;
import ps.reso.instaeclipse.utils.core.DexKitQueryCache;
import ps.reso.instaeclipse.utils.core.DexKitQueryCache.Found;
import ps.reso.instaeclipse.utils.dialog.IeDialog;
import ps.reso.instaeclipse.utils.feature.FeatureFlags;
import ps.reso.instaeclipse.utils.feature.FeatureStatusTracker;
import ps.reso.instaeclipse.utils.i18n.I18n;
import ps.reso.instaeclipse.utils.log.ModuleLog;
import ps.reso.instaeclipse.utils.ui.ResIds;
import ps.reso.instaeclipse.utils.users.UserUtils;
import ps.reso.instaeclipse.xposedcompat.XC_MethodHook;
import ps.reso.instaeclipse.xposedcompat.XposedBridge;

/**
 * Edit a post's likes / comments / reposts / shares, on this phone only (post ⋮ menu → "Edit
 * counts"). Nothing is sent to Instagram: the counts are kept per post id in
 * ie_post_counts.json (part of the data backup) and returned by the Media model's count getters,
 * which are found by their JSON field names' hash like the module's other model getters.
 */
public final class PostCountSpoof {

    private static final String TAG = "(IE|PostCounts) ";
    private static final String MEDIA = "com.instagram.feed.media.Media";
    public static final String FILE = "ie_post_counts.json";
    static final String[] FIELDS = PostCountStore.FIELDS;
    private static final String[] KEYS = {"like_count", "comment_count", "media_repost_count", "reshare_count"};
    private static final String[] HIDDEN = {"like_and_view_counts_disabled", "share_count_disabled"};
    private static final String AUTHOR = "user";
    private static final String USER = "com.instagram.user.model.User";
    private static final String CACHE = "PostCountV4_";
    private static final PostCountStore STORE = new PostCountStore();
    private static volatile boolean loaded;
    private static Method getId;
    private static volatile Method ownerGetter;

    private PostCountSpoof() {}

    public static void install(ClassLoader cl) {
        StringBuilder log = new StringBuilder();
        int hooked = 0;
        try { getId = cl.loadClass(MEDIA).getMethod("getId"); } catch (Throwable t) { log.append(" no getId"); }
        for (int i = 0; i < KEYS.length; i++) {
            final int which = i;
            List<Method> found = lookup(cl, KEYS[i], m -> isCount(m.getReturnType()), "like_count".equals(KEYS[i]));
            if (!found.isEmpty()) log.append(" ").append(KEYS[i]);
            for (Method m : found) {
                XposedBridge.hookMethod(m, new XC_MethodHook() {
                    @Override protected void afterHookedMethod(MethodHookParam param) {
                        ps.reso.instaeclipse.utils.core.JitGuard.tick(param.method);
                        if (!FeatureFlags.postCountSpoof) return;
                        ensureLoaded();
                        if (STORE.isEmpty()) return;
                        Object media = param.thisObject != null ? param.thisObject : mediaIn(param.args);
                        int[] c = countsFor(media);
                        if (c == null || c[which] < 0) return;
                        if (param.getResult() instanceof Number real && param.thisObject != null) remember(media, which, real.intValue());
                        Class<?> t = ((Method) param.method).getReturnType();
                        param.setResult(t == long.class || t == Long.class ? (Object) (long) c[which] : (Object) c[which]);
                    }
                });
                log.append("=").append(m.getName());
                hooked++;
            }
            if (found.isEmpty()) log.append(" ").append(FIELDS[i]).append("=none");
        }
        for (String key : HIDDEN) {
            for (Method m : lookup(cl, key, m -> m.getReturnType() == Boolean.class || m.getReturnType() == boolean.class, false)) {
                XposedBridge.hookMethod(m, new XC_MethodHook() {
                    @Override protected void afterHookedMethod(MethodHookParam param) {
                        ps.reso.instaeclipse.utils.core.JitGuard.tick(param.method);
                        if (!FeatureFlags.postCountSpoof || !Boolean.TRUE.equals(param.getResult())) return;
                        if (countsFor(param.thisObject) != null) param.setResult(Boolean.FALSE);
                    }
                });
                log.append(" ").append(key).append("=").append(m.getName());
            }
        }
        List<Method> owner = lookup(cl, AUTHOR, m -> m.getReturnType().getName().equals(USER), false);
        ownerGetter = owner.isEmpty() ? null : owner.get(0);
        log.append(" owner=").append(ownerGetter != null ? ownerGetter.getName() : "none");
        if (FeatureFlags.postCountSpoof) hookCountViews();
        if (hooked > 0) FeatureStatusTracker.setHooked("PostCountSpoof");
        if (hooked > 0) ModuleLog.ok(TAG + "ready");
        else ModuleLog.error(TAG + "Will not work: the post counts were not found in this Instagram version");
        ModuleLog.detail(TAG + "getters=" + hooked + log);
    }

    private static boolean isCount(Class<?> t) {
        return t == Integer.class || t == int.class || t == Long.class || t == long.class;
    }

    private static List<Method> lookup(ClassLoader cl, String key, Predicate<Method> getter, boolean helpers) {
        String cacheKey = CACHE + key;
        List<Method> cached = DexKitCache.isCacheValid() ? DexKitCache.loadMethods(cacheKey, cl) : null;
        if (cached != null && !cached.isEmpty()) return cached;
        List<Method> found = new ArrayList<>();
        try {
            for (Found md : DexKitQueryCache.methods(FindMethod.create().matcher(MethodMatcher.create()
                    .declaredClass(MEDIA).paramCount(0).usingNumbers(key.hashCode())))) {
                if (!md.isMethod()) continue;
                Method m = md.getMethodInstance(cl);
                if (!Modifier.isStatic(m.getModifiers()) && getter.test(m)) found.add(m);
            }
            if (helpers && !found.isEmpty()) addHelpers(cl, key, found);
        } catch (Throwable t) {
            return found;
        }
        if (!found.isEmpty()) DexKitCache.saveMethods(cacheKey, found);
        return found;
    }

    private static void addHelpers(ClassLoader cl, String key, List<Method> found) throws NoSuchMethodException {
        for (Found helper : DexKitQueryCache.methods(FindMethod.create().matcher(MethodMatcher.create()
                .usingNumbers(key.hashCode()).returnType("int").paramCount(1)))) {
            if (!helper.isMethod() || helper.getClassName().equals(MEDIA)) continue;
            Method h = helper.getMethodInstance(cl);
            if (Modifier.isStatic(h.getModifiers()) && !found.contains(h)) found.add(h);
            for (Found md : DexKitQueryCache.methods(FindMethod.create().matcher(MethodMatcher.create()
                    .declaredClass(MEDIA).paramCount(0).returnType("int")
                    .addInvoke(MethodMatcher.create().declaredClass(helper.getClassName()).name(helper.getName()))))) {
                if (!md.isMethod()) continue;
                Method m = md.getMethodInstance(cl);
                if (!Modifier.isStatic(m.getModifiers()) && !found.contains(m)) found.add(m);
            }
        }
    }

    private static volatile Class<?> mediaClass;

    private static final ConcurrentHashMap<Class<?>, Field[]> MEDIA_FIELDS = new ConcurrentHashMap<>();

    private static Class<?> mediaClass() {
        Class<?> mc = mediaClass;
        if (mc == null && getId != null) mediaClass = mc = getId.getDeclaringClass();
        return mc;
    }

    private static Object mediaIn(Object[] args) {
        Class<?> mc = mediaClass();
        if (mc == null || args == null) return null;
        for (Object a : args) {
            if (a == null) continue;
            if (mc.isInstance(a)) return a;
            for (Field f : MEDIA_FIELDS.computeIfAbsent(a.getClass(), k -> mediaFields(k, mc))) {
                try {
                    Object v = f.get(a);
                    if (mc.isInstance(v)) return v;
                } catch (Throwable ignored) { }
            }
        }
        return null;
    }

    private static Field[] mediaFields(Class<?> type, Class<?> mc) {
        List<Field> out = new ArrayList<>();
        for (Class<?> k = type; k != null && k != Object.class; k = k.getSuperclass())
            for (Field f : ps.reso.instaeclipse.utils.core.SafeReflect.fields(k)) {
                if (Modifier.isStatic(f.getModifiers()) || !f.getType().isAssignableFrom(mc)) continue;
                try {
                    f.setAccessible(true);
                    out.add(f);
                } catch (Throwable ignored) { }
            }
        return out.toArray(new Field[0]);
    }

    private static int[] countsFor(Object media) {
        ensureLoaded();
        if (getId == null || STORE.isEmpty()) return null;
        if (media == null) return null;
        try {
            Object id = getId.invoke(media);
            return id == null ? null : STORE.counts(key(id.toString()));
        } catch (Throwable t) {
            return null;
        }
    }

    private static String key(String id) {
        return PostCountStore.key(id);
    }

    private static String idOf(Object media) {
        try {
            Object id = getId.invoke(media);
            return id == null ? null : key(id.toString());
        } catch (Throwable t) {
            return null;
        }
    }

    private static String ownerOf(Object media) {
        Method getter = ownerGetter;
        if (getter == null || media == null) return null;
        try {
            return UserUtils.callUsernameGetter(getter.invoke(media));
        } catch (Throwable t) {
            return null;
        }
    }

    // ----- the counts row under a post -----
    //
    // The row's numbers come from a view state that doesn't always re-read the model (a post you
    // liked, a count Instagram hides at 0). So when a post with edited counts is bound, the row's
    // count labels (the label right after the heart / comment / repost / send button) are set too.

    private static final String[] BUTTONS = {"row_feed_button_like", "row_feed_button_comment",
            "row_feed_button_repost", "row_feed_button_share"};
    private static volatile int[] buttonIds;
    private static final ThreadLocal<Boolean> SETTING = new ThreadLocal<>();

    private static void hookCountViews() {
        try {
            XC_MethodHook rebind = new XC_MethodHook() {
                @Override protected void afterHookedMethod(MethodHookParam param) {
                    ps.reso.instaeclipse.utils.core.JitGuard.tick(param.method);
                    if (!FeatureFlags.postCountSpoof || SETTING.get() == Boolean.TRUE) return;
                    ensureLoaded();
                    if (STORE.isEmpty()) return;
                    android.view.View v = (android.view.View) param.thisObject;
                    int[] ids = buttonIds(v.getContext());
                    if (ids[0] == 0) return;
                    if (v.getId() != ids[0] && !(v instanceof android.widget.TextView && afterButton(v, ids))) return;
                    v.post(() -> {
                        android.view.ViewGroup row = rowOf(v, ids[0]);
                        if (row != null) applyRow(row);
                    });
                }
            };
            XposedBridge.hookAllMethods(android.view.View.class, "setContentDescription", rebind);
            XposedBridge.hookMethod(android.widget.TextView.class.getMethod("setText", CharSequence.class,
                    android.widget.TextView.BufferType.class), rebind);
        } catch (Throwable t) {
            ModuleLog.warn(TAG + "Edited counts may not show in the row under posts: " + t.getClass().getSimpleName() + ": " + t.getMessage());
        }
    }

    private static int[] buttonIds(Context ctx) {
        int[] ids = buttonIds;
        if (ids != null) return ids;
        ids = new int[BUTTONS.length];
        for (int i = 0; i < BUTTONS.length; i++) ids[i] = ResIds.id(ctx, BUTTONS[i]);
        buttonIds = ids;
        return ids;
    }

    private static boolean afterButton(android.view.View v, int[] ids) {
        if (!(v.getParent() instanceof android.view.ViewGroup p)) return false;
        int at = p.indexOfChild(v);
        if (at <= 0) return false;
        android.view.View before = p.getChildAt(at - 1);
        if (isButton(before.getId(), ids)) return true;
        if (before instanceof android.view.ViewGroup g)
            for (int i = 0; i < g.getChildCount(); i++) if (isButton(g.getChildAt(i).getId(), ids)) return true;
        return false;
    }

    private static boolean isButton(int id, int[] ids) {
        if (id == android.view.View.NO_ID || id == 0) return false;
        for (int b : ids) if (b == id) return true;
        return false;
    }

    private static android.view.ViewGroup rowOf(android.view.View v, int heartId) {
        android.view.View heartGroup = null;
        if (v.getId() == heartId && v.getParent() instanceof android.view.View g) heartGroup = g;
        else if (v instanceof android.widget.TextView && v.getParent() instanceof android.view.ViewGroup p) {
            for (int i = 0; i < p.getChildCount() && heartGroup == null; i++) {
                android.view.View c = p.getChildAt(i);
                if (c instanceof android.view.ViewGroup g) {
                    android.view.View heart = g.findViewById(heartId);
                    if (heart != null && heart.getParent() == g) heartGroup = g;
                }
            }
        }
        return heartGroup != null && heartGroup.getParent() instanceof android.view.ViewGroup row ? row : null;
    }

    private static void applyRow(android.view.ViewGroup row) {
        try {
            Object media = mediaForView(row);
            int[] c = media != null ? countsFor(media) : STORE.byContent(rowShown(row), ProfileSpoofHook.knownUsername());
            if (c != null) writeRow(row, c);
        } catch (Throwable ignored) { }
    }

    private static void writeRow(android.view.ViewGroup row, int[] c) {
        int[] ids = buttonIds(row.getContext());
        for (int i = 0; i < row.getChildCount() - 1; i++) {
            android.view.View child = row.getChildAt(i);
            for (int k = 0; k < ids.length; k++) {
                if (ids[k] == 0 || c[k] < 0 || child.findViewById(ids[k]) == null) continue;
                if (!(row.getChildAt(i + 1) instanceof android.widget.TextView label)) continue;
                String text = PostCountStore.label(c[k]);
                if (label.getVisibility() != android.view.View.VISIBLE) label.setVisibility(android.view.View.VISIBLE);
                if (!text.contentEquals(label.getText())) {
                    SETTING.set(Boolean.TRUE);
                    try { label.setText(text); } finally { SETTING.remove(); }
                }
                fitWidth(row, label, text);
            }
        }
    }

    /**
     * The row is laid out by Litho for the real numbers, so a longer number would be cut: widen
     * the label to fit and move the buttons after it along.
     */
    private static void fitWidth(android.view.ViewGroup row, android.widget.TextView label, String text) {
        int need = (int) Math.ceil(label.getPaint().measureText(text)) + label.getPaddingLeft() + label.getPaddingRight();
        int have = label.getRight() - label.getLeft();
        if (need == have) return;
        int idx = row.indexOfChild(label);
        int left = label.getLeft(), top = label.getTop(), bottom = label.getBottom();
        if (have <= 0 && idx > 0) { // a count Instagram hid (0): place it after its button
            android.view.View button = row.getChildAt(idx - 1);
            left = button.getRight();
            top = button.getTop();
            bottom = button.getBottom();
            have = 0;
        }
        int delta = need - have;
        label.layout(left, top, left + need, bottom);
        int saveId = ResIds.id(row.getContext(), "row_feed_button_save");
        for (int i = idx + 1; i < row.getChildCount(); i++) {
            android.view.View v = row.getChildAt(i);
            if (v.getId() == saveId && saveId != 0) continue; // the save button stays on the right
            v.offsetLeftAndRight(delta);
        }
    }

    private static void refreshShown(String id, String owner, int[] before, int[] after) {
        android.app.Activity a = UIHookManager.getCurrentActivity();
        android.view.View root = a != null ? a.getWindow().getDecorView() : null;
        if (root == null) return;
        root.post(() -> {
            int heartId = buttonIds(root.getContext())[0];
            if (heartId == 0) return;
            java.util.ArrayDeque<android.view.View> st = new java.util.ArrayDeque<>();
            st.push(root);
            while (!st.isEmpty()) {
                android.view.View v = st.pop();
                if (v.getId() == heartId) {
                    android.view.ViewGroup row = rowOf(v, heartId);
                    if (row != null && shows(row, id, owner, before)) writeRow(row, after != null ? after : STORE.realOf(id));
                    continue;
                }
                if (v instanceof android.view.ViewGroup g) for (int i = 0; i < g.getChildCount(); i++) st.push(g.getChildAt(i));
            }
        });
    }

    private static boolean shows(android.view.ViewGroup row, String id, String owner, int[] before) {
        Object media = mediaForView(row);
        return media != null ? id.equals(idOf(media)) : STORE.showsPost(rowShown(row), id, owner, before);
    }

    private static String[] shownIn(android.view.ViewGroup row) {
        int[] ids = buttonIds(row.getContext());
        String[] shown = new String[ids.length];
        for (int i = 0; i < row.getChildCount() - 1; i++)
            for (int k = 0; k < ids.length; k++)
                if (ids[k] != 0 && row.getChildAt(i).findViewById(ids[k]) != null
                        && row.getChildAt(i + 1) instanceof android.widget.TextView label)
                    shown[k] = label.getVisibility() == android.view.View.VISIBLE && label.getText() != null ? label.getText().toString() : "";
        return shown;
    }

    private static void remember(Object media, int which, int real) {
        String id = idOf(media);
        if (id != null) STORE.remember(id, which, real, System.currentTimeMillis());
    }

    private static PostCountStore.Row rowShown(android.view.ViewGroup row) {
        android.view.View item = row;
        for (int i = 0; i < 8 && item.getParent() instanceof android.view.View p; i++) item = p;
        int nameId = ResIds.id(row.getContext(), "row_feed_photo_profile_name");
        android.view.View name = nameId != 0 ? item.findViewById(nameId) : null;
        String author = name instanceof android.widget.TextView tv && tv.getText() != null ? tv.getText().toString().trim() : null;
        return new PostCountStore.Row(shownIn(row), author);
    }

    /** The post a feed row shows: a Media held by the row's views (their tags or fields). */
    private static Object mediaForView(android.view.View v) {
        Class<?> mc = mediaClass();
        if (mc == null) return null;
        for (android.view.View x = v; x != null; x = x.getParent() instanceof android.view.View p ? p : null) {
            Object m = mediaInObject(x.getTag(), mc, 2);
            if (m != null) return m;
            if (!x.getClass().getName().startsWith("android.")) {
                m = mediaInObject(x, mc, 2);
                if (m != null) return m;
            }
            // The list item: its ViewHolder holds the bound post.
            if (x.getParent() instanceof android.view.ViewGroup list && isRecyclerView(list.getClass())) {
                try {
                    Object holder = list.getClass().getMethod("getChildViewHolder", android.view.View.class).invoke(list, x);
                    return mediaInObject(holder, mc, 3);
                } catch (Throwable ignored) { return null; }
            }
        }
        return null;
    }

    private static boolean isRecyclerView(Class<?> c) {
        for (; c != null; c = c.getSuperclass()) if (c.getName().equals("androidx.recyclerview.widget.RecyclerView")) return true;
        return false;
    }

    private static Object mediaInObject(Object o, Class<?> mc, int depth) {
        if (o == null) return null;
        if (mc.isInstance(o)) return o;
        if (depth == 0) return null;
        String cn = o.getClass().getName();
        if (!(cn.startsWith("X.") || cn.startsWith("com.instagram.") || cn.startsWith("androidx.recyclerview."))) return null;
        for (Class<?> k = o.getClass(); k != null && k != Object.class && !k.getName().startsWith("android."); k = k.getSuperclass())
            for (java.lang.reflect.Field f : ps.reso.instaeclipse.utils.core.SafeReflect.fields(k)) {
                if (Modifier.isStatic(f.getModifiers()) || f.getType().isPrimitive() || f.getType() == String.class) continue;
                try {
                    f.setAccessible(true);
                    Object m = mediaInObject(f.get(o), mc, depth - 1);
                    if (m != null) return m;
                } catch (Throwable ignored) { }
            }
        return null;
    }

    // ----- storage -----

    private static File file() {
        Context c = ps.reso.instaeclipse.xposedcompat.AndroidAppHelper.currentApplication();
        return c == null ? null : new File(c.getFilesDir(), FILE);
    }

    private static volatile long checkedAt, fileTime = -1;

    /** Reads the saved counts, again whenever the file changed (a restore, another process). */
    private static void ensureLoaded() {
        long now = System.currentTimeMillis();
        if (loaded && now - checkedAt < 1000) return;
        synchronized (PostCountSpoof.class) {
            checkedAt = now;
            File f = file();
            if (f == null) return;
            long t = f.exists() ? f.lastModified() : 0;
            if (loaded && t == fileTime) return;
            loaded = true;
            fileTime = t;
            STORE.clear();
            if (t == 0) return;
            try {
                STORE.read(new JSONObject(new String(new android.util.AtomicFile(f).readFully(), StandardCharsets.UTF_8)));
            } catch (Throwable e) {
                ModuleLog.warn(TAG + "Could not read your saved post counts: " + e.getClass().getSimpleName() + ": " + e.getMessage());
            }
        }
    }

    private static synchronized void save() {
        File f = file();
        if (f == null) return;
        android.util.AtomicFile atomic = new android.util.AtomicFile(f);
        java.io.FileOutputStream out = null;
        try {
            JSONObject o = STORE.toJson();
            out = atomic.startWrite();
            out.write(o.toString().getBytes(StandardCharsets.UTF_8));
            atomic.finishWrite(out);
            fileTime = f.lastModified();
        } catch (Throwable t) {
            atomic.failWrite(out);
            ModuleLog.error(TAG + "Could not save the edited post counts: " + t.getClass().getSimpleName() + ": " + t.getMessage());
        }
    }

    // ----- editor (post ⋮ menu) -----

    public static void edit(Context ctx, Object media) {
        String id;
        try { id = key(String.valueOf(getId.invoke(media))); } catch (Throwable t) { return; }
        String owner = ownerOf(media);
        String shownOwner = owner != null ? owner : ProfileSpoofHook.knownUsername();
        ensureLoaded();
        int[] cur = STORE.counts(id);
        IeDialog dlg = new IeDialog(UIHookManager.dialogHost(ctx)).title(I18n.t(ctx, R.string.ig_post_counts_title))
                .message(I18n.t(ctx, R.string.ig_post_counts_hint));
        int[] labels = {R.string.ig_post_counts_likes, R.string.ig_post_counts_comments,
                R.string.ig_post_counts_reposts, R.string.ig_post_counts_shares};
        EditText[] inputs = new EditText[FIELDS.length];
        for (int i = 0; i < FIELDS.length; i++) {
            android.widget.TextView label = new android.widget.TextView(dlg.ctx);
            label.setText(I18n.t(ctx, labels[i]));
            label.setTextColor(dlg.sub);
            label.setTextSize(13);
            dlg.view(label);
            inputs[i] = dlg.input(I18n.t(ctx, R.string.ig_post_counts_keep));
            inputs[i].setInputType(InputType.TYPE_CLASS_NUMBER);
            inputs[i].setFilters(new android.text.InputFilter[]{new android.text.InputFilter.LengthFilter(10)});
            if (cur != null && cur[i] >= 0) inputs[i].setText(String.valueOf(cur[i]));
        }
        final int[] before = cur == null ? null : cur.clone();
        dlg.secondary(I18n.t(ctx, R.string.ig_post_counts_reset), () -> { STORE.remove(id); save(); refreshShown(id, shownOwner, before, null); });
        dlg.primary(ctx.getString(android.R.string.ok), () -> {
            int[] c = new int[FIELDS.length];
            boolean any = false;
            for (int i = 0; i < FIELDS.length; i++) {
                c[i] = ps.reso.instaeclipse.utils.profile.SpoofCounts.parse(inputs[i].getText().toString());
                any |= c[i] >= 0;
            }
            if (any) STORE.put(id, c, owner); else STORE.remove(id);
            save();
            refreshShown(id, shownOwner, before, any ? c : null);
            ps.reso.instaeclipse.utils.toast.IeToast.makeText(ctx, I18n.t(ctx, R.string.ig_post_counts_saved),
                    android.widget.Toast.LENGTH_SHORT).show();
        });
        dlg.show();
    }
}
