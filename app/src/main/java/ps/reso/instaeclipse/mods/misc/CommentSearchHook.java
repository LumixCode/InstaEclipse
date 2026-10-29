package ps.reso.instaeclipse.mods.misc;

import android.app.Activity;
import android.content.Context;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;

import org.luckypray.dexkit.query.FindMethod;
import org.luckypray.dexkit.query.matchers.MethodMatcher;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import ps.reso.instaeclipse.xposedcompat.XC_MethodHook;
import ps.reso.instaeclipse.xposedcompat.XposedBridge;
import ps.reso.instaeclipse.R;
import ps.reso.instaeclipse.mods.media.DownloadFiles;
import ps.reso.instaeclipse.mods.ui.UIHookManager;
import ps.reso.instaeclipse.utils.dialog.IeDialog;
import ps.reso.instaeclipse.utils.feature.FeatureFlags;
import ps.reso.instaeclipse.utils.feature.FeatureStatusTracker;
import ps.reso.instaeclipse.utils.i18n.I18n;
import ps.reso.instaeclipse.utils.log.ModuleLog;
import ps.reso.instaeclipse.utils.text.CopyFeedback;
import ps.reso.instaeclipse.utils.text.HtmlExport;
import ps.reso.instaeclipse.utils.text.MediaIds;
import ps.reso.instaeclipse.utils.ui.A11yIds;
import ps.reso.instaeclipse.utils.ui.IePalette;
import ps.reso.instaeclipse.utils.ui.ResIds;
import ps.reso.instaeclipse.utils.users.UserUtils;
import ps.reso.instaeclipse.utils.core.DexKitQueryCache;
import ps.reso.instaeclipse.utils.core.DexKitQueryCache.Found;

public class CommentSearchHook {

    private static final String TAG = "(IE|CommentSearch) ";
    private static final String USER = "com.instagram.user.model.User";
    private static volatile List<String[]> latest = new ArrayList<>();
    private static volatile java.lang.ref.WeakReference<Object> lastState = new java.lang.ref.WeakReference<>(null);
    private static java.lang.ref.WeakReference<Object> lastFragment = new java.lang.ref.WeakReference<>(null);
    private static volatile String[] postRow;
    private static Method commentSerializer, jsonWriterMethod;
    private static Class<?> jsonFactory, commentType;
    private static Object jsonFactoryInstance;
    private static final Map<Class<?>, Field[]> FIELDS = new java.util.concurrent.ConcurrentHashMap<>();
    private static final java.util.regex.Pattern NUMERIC = java.util.regex.Pattern.compile("[0-9_:.-]+");
    private static volatile Method mediaIdGetter;
    private static final Map<Object, String[]> ROW_CACHE = Collections.synchronizedMap(new java.util.WeakHashMap<>());
    private static java.lang.ref.WeakReference<View> sheet = new java.lang.ref.WeakReference<>(null);
    private static final android.os.Handler MAIN = new android.os.Handler(android.os.Looper.getMainLooper());
    private static volatile Runnable loading;
    private static boolean exportPostMedia = true;
    private static final java.util.regex.Pattern TOKEN = java.util.regex.Pattern.compile("[A-Za-z0-9+/=_-]{32,}");

    public void install(ClassLoader cl) {
        try {
            Class<?> vm = null;
            for (Found md : DexKitQueryCache.methods(FindMethod.create().matcher(MethodMatcher.create()
                    .usingStrings("select_comment_screen_pin_comment_tap")))) {
                vm = cl.loadClass(md.getClassName());
                break;
            }
            if (vm == null) { ModuleLog.error(TAG + "Could not turn on comment search: the comment sheet was not found in this Instagram version"); return; }
            int builders = 0;
            for (Method m : ps.reso.instaeclipse.utils.core.SafeReflect.methods(vm)) {
                if (!Modifier.isStatic(m.getModifiers()) || m.getReturnType().isPrimitive()
                        || m.getReturnType() == void.class || m.getParameterCount() < 10) continue;
                boolean takesVm = false;
                for (Class<?> p : m.getParameterTypes()) if (p == vm) takesVm = true;
                if (!takesVm) continue;
                XposedBridge.hookMethod(m, new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) {
                        Object state = param.getResult();
                        if ((!FeatureFlags.commentSearch && !FeatureFlags.enableCaptionCopy) || state == null) return;
                        Object sortRow = findSortRow(state);
                        Object post = postOf(sortRow);
                        if (post != null) sheetPost = new java.lang.ref.WeakReference<>(post);
                        if (!FeatureFlags.commentSearch) return;
                        stateHasSortRow = sortRow != null;
                        stateAt = android.os.SystemClock.uptimeMillis();
                        if (lastState.get() != state) { lastState = new java.lang.ref.WeakReference<>(state); stateUpdates++; }
                        SortRowOverlay.poke();
                    }
                });
                builders++;
            }
            try {
                for (Found md : DexKitQueryCache.methods(FindMethod.create().matcher(MethodMatcher.create()
                        .usingStrings("parent_comment_id", "child_comment_count").returnType("void").paramCount(2)))) {
                    Method m = md.getMethodInstance(cl);
                    if (Modifier.isStatic(m.getModifiers())) { commentSerializer = m; break; }
                }
                if (commentSerializer != null) {
                    Class<?> gen = commentSerializer.getParameterTypes()[0];
                    for (Found md : DexKitQueryCache.methods(FindMethod.create().matcher(MethodMatcher.create()
                            .paramTypes("java.io.Writer").returnType(gen.getName())))) {
                        Method m = md.getMethodInstance(cl);
                        if (Modifier.isStatic(m.getModifiers())) continue;
                        try { m.getDeclaringClass().getConstructor(); jsonWriterMethod = m; jsonFactory = m.getDeclaringClass(); break; }
                        catch (NoSuchMethodException ignored) {}
                    }
                    commentSerializer.setAccessible(true);
                    commentType = commentSerializer.getParameterTypes()[1];
                }
                ModuleLog.detail(TAG + "comment json: serializer=" + (commentSerializer != null) + " factory=" + (jsonFactory != null));
            } catch (Throwable t) {
                ModuleLog.warn(TAG + "Comment search works on the text only: likes, dates and replies cannot be read: " + t);
            }
            try {
                for (Found md : DexKitQueryCache.methods(FindMethod.create().matcher(MethodMatcher.create()
                        .usingEqStrings("sort_order_selector_row")))) {
                    Class<?> model = cl.loadClass(md.getClassName());
                    for (java.lang.reflect.Constructor<?> c : model.getDeclaredConstructors())
                        for (Class<?> t : c.getParameterTypes()) if (t.isEnum()) sortEnum = t;
                    if (sortEnum != null) { sortRowModel = model; break; }
                }
                ModuleLog.detail(TAG + "sort order enum: " + (sortEnum != null ? sortEnum.getName() : "not found"));
            } catch (Throwable t) {
                ModuleLog.warn(TAG + "The search icon goes to the sheet's header instead of the sort row: " + t);
            }
            try {
                int selectors = 0;
                for (Found md : DexKitQueryCache.methods(FindMethod.create().matcher(MethodMatcher.create()
                        .usingStrings("CommentSortOrderSelector.kt")))) {
                    Method m = md.getMethodInstance(cl);
                    if (!Modifier.isStatic(m.getModifiers()) || m.getReturnType() != void.class) continue;
                    XposedBridge.hookMethod(m, new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            if (FeatureFlags.commentSearch) SortRowOverlay.poke();
                        }
                    });
                    selectors++;
                }
                if (selectors == 0) ModuleLog.detail(TAG + "sort row composable not found (icon finds the row by polling)");
            } catch (Throwable t) {
                ModuleLog.detail(TAG + "sort row composable: " + t);
            }
            int frags = 0;
            for (Found md : DexKitQueryCache.methods(FindMethod.create().matcher(MethodMatcher.create()
                    .usingStrings("Failed to ingest comments close signal")))) {
                Class<?> frag = cl.loadClass(md.getClassName());
                try {
                    Method ovc = frag.getDeclaredMethod("onViewCreated", View.class, android.os.Bundle.class);
                    XposedBridge.hookMethod(ovc, new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) {
                            latest = new ArrayList<>();
                            lastState = new java.lang.ref.WeakReference<>(null);
                            stateUpdates = 0;
                            sheetPost = new java.lang.ref.WeakReference<>(null);
                            postRow = null;
                        }

                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            if (param.thisObject != null) lastFragment = new java.lang.ref.WeakReference<>(param.thisObject);
                            Object frag = param.thisObject;
                            if (FeatureFlags.commentSearch && param.args[0] instanceof View root) root.post(() -> addPill(host(root), frag));
                        }
                    });
                    frags++;
                    break;
                } catch (NoSuchMethodException ignored) {}
            }
            ModuleLog.line(TAG + (builders > 0 && frags > 0 ? "✅ Ready"
                    : "❌ Could not turn on comment search: the comment sheet is not supported in this Instagram version"));
            ModuleLog.detail(TAG + "vm " + vm.getName() + " builders=" + builders + " fragments=" + frags);
            if (builders > 0 && frags > 0) FeatureStatusTracker.setHooked("CommentSearch");
        } catch (Throwable t) {
            ModuleLog.error(TAG + "Could not turn on comment search", t);
        }
    }

    private static List<String[]> collect(Object state) {
        Map<String, String[]> out = new LinkedHashMap<>();
        Set<Object> seen = Collections.newSetFromMap(new IdentityHashMap<>());
        java.util.ArrayDeque<Object[]> q = new java.util.ArrayDeque<>();
        Object media = sheetPost.get();
        String[] post = null;
        q.add(new Object[]{state, 0});
        while (!q.isEmpty() && seen.size() < 6000) {
            Object[] e = q.poll();
            Object o = e[0];
            int d = (int) e[1];
            if (o == null || !seen.add(o)) continue;
            if (o instanceof List<?> l) {
                for (Object it : l) q.add(new Object[]{it, d + 1});
                continue;
            }
            String n = o.getClass().getName();
            if (!n.startsWith("X.") && !n.startsWith("com.instagram.")) continue;
            Object user = null;
            String text = null;
            boolean ofPost = false;
            for (Field f : fields(o.getClass())) {
                try {
                    Object v = f.get(o);
                    if (v == null) continue;
                    if (v == media) ofPost = true;
                    if (f.getType().getName().equals(USER)) user = v;
                    else if (v instanceof String s) {
                        if (!s.startsWith("http") && !TOKEN.matcher(s).matches()
                                && (text == null || s.length() > text.length())) text = s;
                    } else if (d < 4 && !f.getType().isPrimitive() && !(v instanceof Number) && !(v instanceof Boolean)) {
                        q.add(new Object[]{v, d + 1});
                    }
                } catch (Throwable ignored) {}
            }
            if (user == null) continue;
            boolean comment = commentType != null && commentType.isInstance(o);
            if (comment) {
                String u = UserUtils.callUsernameGetter(user);
                String[] row = u != null ? commentRow(o, u) : null;
                if (row != null) { out.put(row[2], row); continue; }
            }
            if (text != null && !text.isEmpty() && !NUMERIC.matcher(text).matches()) {
                String u = UserUtils.callUsernameGetter(user);
                if (u == null || text.equals(u)) continue;
                out.putIfAbsent(u + "\u0000" + text, new String[]{u, text, "", "", "", "", "", "", "", ""});
                if (ofPost && !comment && post == null) post = new String[]{u, text};
            }
        }
        postRow = post;
        Set<String> withId = new java.util.HashSet<>();
        for (String[] r : out.values()) if (!r[2].isEmpty()) withId.add(r[0] + "\u0000" + r[1]);
        List<String[]> rows = new ArrayList<>();
        for (String[] r : out.values()) if (!r[2].isEmpty() || !withId.contains(r[0] + "\u0000" + r[1])) rows.add(r);
        return rows;
    }

    private static Object findSortRow(Object state) {
        if (sortRowModel == null) return null;
        Set<Object> seen = Collections.newSetFromMap(new IdentityHashMap<>());
        java.util.ArrayDeque<Object[]> q = new java.util.ArrayDeque<>();
        q.add(new Object[]{state, 0});
        while (!q.isEmpty() && seen.size() < 6000) {
            Object[] e = q.poll();
            Object o = e[0];
            int d = (int) e[1];
            if (o == null || !seen.add(o)) continue;
            if (o instanceof List<?> l) {
                for (Object it : l) q.add(new Object[]{it, d + 1});
                continue;
            }
            if (sortRowModel.isInstance(o)) return o;
            String n = o.getClass().getName();
            if (d >= 4 || (!n.startsWith("X.") && !n.startsWith("com.instagram."))) continue;
            for (Field f : fields(o.getClass())) {
                if (f.getType().isPrimitive()) continue;
                try {
                    Object v = f.get(o);
                    if (v != null && !(v instanceof String) && !(v instanceof Number) && !(v instanceof Boolean))
                        q.add(new Object[]{v, d + 1});
                } catch (Throwable ignored) {}
            }
        }
        return null;
    }

    private static Field[] fields(Class<?> type) {
        Field[] cached = FIELDS.get(type);
        if (cached != null) return cached;
        List<Field> out = new ArrayList<>();
        for (Class<?> k = type; k != null && k != Object.class; k = k.getSuperclass())
            for (Field f : ps.reso.instaeclipse.utils.core.SafeReflect.fields(k)) {
                if (Modifier.isStatic(f.getModifiers())) continue;
                try {
                    f.setAccessible(true);
                    out.add(f);
                } catch (Throwable ignored) {}
            }
        Field[] found = out.toArray(new Field[0]);
        FIELDS.put(type, found);
        return found;
    }

    private static String mediaIdOf(Object media) {
        if (media == null) return "";
        try {
            Method getter = mediaIdGetter;
            if (getter == null || getter.getDeclaringClass() != media.getClass()) {
                getter = media.getClass().getMethod("getId");
                mediaIdGetter = getter;
            }
            return getter.invoke(media) instanceof String id ? id : "";
        } catch (Throwable t) {
            return "";
        }
    }

    private static String[] header(List<String[]> rows, Object media, String[] post) {
        boolean ours = post != null && CommentSearchPolicy.sameMedia(mediaIdOf(media), CommentSearchPolicy.mediaId(rows));
        return CommentSearchPolicy.header(rows, ours ? post : null);
    }

    private static String[] commentRow(Object c, String user) {
        if (commentSerializer == null || jsonFactory == null) return null;
        if (!commentType.isInstance(c)) {
            if (!rowLogged) { rowLogged = true; ModuleLog.detail(TAG + "row class " + c.getClass().getName() + " is not " + commentType.getName()); }
            return null;
        }
        String[] cached = ROW_CACHE.get(c);
        if (cached != null) return cached;
        try {
            java.io.StringWriter sw = new java.io.StringWriter();
            Object factory = jsonFactoryInstance;
            if (factory == null) jsonFactoryInstance = factory = jsonFactory.getConstructor().newInstance();
            Object gen = jsonWriterMethod.invoke(factory, sw);
            commentSerializer.invoke(null, gen, c);
            if (gen instanceof java.io.Flushable f) f.flush();
            org.json.JSONObject j = new org.json.JSONObject(sw.toString());
            String id = j.optString("pk", "");
            if (id.isEmpty()) return null;
            String parent = j.optString("parent_comment_id", "");
            if (parent.equals("null") || parent.equals("0")) parent = "";
            String[] row = {user, j.optString("text", ""), id, parent,
                    j.optString("comment_like_count", ""), j.optString("child_comment_count", ""), j.optString("created_at", ""),
                    j.optString("media_id", ""), "", ""};
            String[] media = CommentSearchPolicy.commentMedia(j);
            row[8] = media[0];
            row[9] = media[1];
            ROW_CACHE.put(c, row);
            return row;
        } catch (Throwable t) {
            if (!rowLogged) { rowLogged = true; ModuleLog.warn(TAG + "Some comment details (likes, dates, replies) could not be read; search and export still use the text: " + (t.getCause() != null ? t.getCause() : t)); }
            return null;
        }
    }

    private static boolean rowLogged;

    private static View host(View root) {
        View v = root;
        while (v != null && v.getClass().getName().contains("ComposeView")) {
            v = v.getParent() instanceof View p ? p : null;
        }
        return v != null ? v : root;
    }

    private static final String ICON_TAG = "ie_comment_search";

    private static void addPill(View root, Object fragment) {
        ViewGroup content = root instanceof FrameLayout f ? f
                : root.getParent() instanceof FrameLayout p ? p
                : root instanceof ViewGroup g ? g : null;
        if (content == null) return;
        sheet = new java.lang.ref.WeakReference<>(content);
        if (content.findViewWithTag(ICON_TAG) != null) return;
        ViewGroup header = headerRow(content);
        if (header != null && header.findViewWithTag(ICON_TAG) != null) return;
        Context ctx = content.getContext();
        int tint = iconTint(ctx, fragment);
        if (content instanceof FrameLayout frame && !sortLabels(ctx).isEmpty()) {
            frame.addView(new SortRowOverlay(ctx, frame, newIcon(ctx, tint), newIcon(ctx, tint)),
                    new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
            return;
        }
        ModuleLog.detail(TAG + "search icon: no sort labels / frame, header fallback");
        headerIcon(content, newIcon(ctx, tint));
    }

    @android.annotation.SuppressLint({"UseCompatLoadingForDrawables", "ClickableViewAccessibility"})
    private static android.widget.ImageView newIcon(Context ctx, int tint) {
        float d = ctx.getResources().getDisplayMetrics().density;
        android.widget.ImageView icon = new android.widget.ImageView(ctx);
        icon.setTag(ICON_TAG);
        android.graphics.drawable.Drawable glyph = null;
        for (String name : new String[]{"instagram_search_outline_24", "instagram_search_pano_outline_24", "tab_search_drawable"}) {
            int id = ResIds.drawable(ctx, name);
            if (id == 0) continue;
            try { glyph = ctx.getDrawable(id); } catch (Throwable ignored) {}
            if (glyph != null) break;
        }
        if (glyph == null) glyph = ctx.getDrawable(android.R.drawable.ic_menu_search);
        if (glyph != null) {
            glyph = glyph.mutate();
            glyph.setTint(tint);
        }
        icon.setImageDrawable(glyph);
        icon.setScaleType(android.widget.ImageView.ScaleType.FIT_CENTER);
        int pad = (int) (10 * d);
        icon.setPadding(pad, pad, pad, pad);
        android.util.TypedValue ripple = new android.util.TypedValue();
        if (ctx.getTheme().resolveAttribute(android.R.attr.selectableItemBackgroundBorderless, ripple, true) && ripple.resourceId != 0) {
            try { icon.setBackground(ctx.getDrawable(ripple.resourceId)); } catch (Throwable ignored) {}
        }
        String label = I18n.t(ctx, R.string.ig_comment_search);
        icon.setContentDescription(label);
        icon.setTooltipText(label);
        icon.setClickable(true);
        icon.setOnClickListener(v -> {
            try {
                showSearch(v.getContext());
            } catch (Throwable t) {
                ModuleLog.error(TAG + "Could not open comment search", t);
            }
        });
        final int slop = android.view.ViewConfiguration.get(ctx).getScaledTouchSlop();
        final float[] down = new float[2];
        icon.setOnTouchListener((v, e) -> {
            switch (e.getActionMasked()) {
                case android.view.MotionEvent.ACTION_DOWN:
                    if (v.getParent() != null) v.getParent().requestDisallowInterceptTouchEvent(true);
                    down[0] = e.getX();
                    down[1] = e.getY();
                    v.setPressed(true);
                    return true;
                case android.view.MotionEvent.ACTION_MOVE:
                    if (Math.abs(e.getX() - down[0]) > slop || Math.abs(e.getY() - down[1]) > slop) v.setPressed(false);
                    return true;
                case android.view.MotionEvent.ACTION_UP:
                    boolean tap = v.isPressed();
                    v.setPressed(false);
                    if (tap && e.getX() >= 0 && e.getY() >= 0 && e.getX() <= v.getWidth() && e.getY() <= v.getHeight()) v.performClick();
                    return true;
                case android.view.MotionEvent.ACTION_CANCEL:
                    v.setPressed(false);
                    return true;
                default:
                    return true;
            }
        });
        int size = (int) (44 * d);
        icon.setLayoutParams(new ViewGroup.LayoutParams(size, size));
        return icon;
    }

    private static int iconTint(Context ctx, Object fragment) {
        Context themed = null;
        try {
            for (Class<?> k = fragment != null ? fragment.getClass() : null; k != null && themed == null; k = k.getSuperclass()) {
                for (Method m : k.getDeclaredMethods()) {
                    if (!m.getName().equals("getThemedContext") || m.getParameterCount() != 0
                            || !Context.class.isAssignableFrom(m.getReturnType())) continue;
                    m.setAccessible(true);
                    themed = (Context) m.invoke(fragment);
                    break;
                }
            }
        } catch (Throwable ignored) {}
        for (Context c : new Context[]{themed, ctx}) {
            if (c == null) continue;
            try {
                int attr = ResIds.attr(c, "igds_color_primary_icon");
                android.util.TypedValue v = new android.util.TypedValue();
                if (attr == 0 || !c.getTheme().resolveAttribute(attr, v, true)) continue;
                if (v.resourceId != 0) return c.getColor(v.resourceId);
                if (v.type >= android.util.TypedValue.TYPE_FIRST_COLOR_INT && v.type <= android.util.TypedValue.TYPE_LAST_COLOR_INT) return v.data;
            } catch (Throwable ignored) {}
        }
        boolean night = (ctx.getResources().getConfiguration().uiMode
                & android.content.res.Configuration.UI_MODE_NIGHT_MASK) == android.content.res.Configuration.UI_MODE_NIGHT_YES;
        return night ? 0xFFF5F5F5 : 0xFF000000;
    }

    private static void headerIcon(ViewGroup content, android.widget.ImageView icon) {
        if (icon.getParent() != null) return;
        float d = content.getResources().getDisplayMetrics().density;
        final int margin = (int) (6 * d);
        ViewGroup row = headerRow(content);
        if (row != null && row.findViewWithTag(ICON_TAG) == null) {
            try {
                ViewGroup.LayoutParams hl = headerParams(row, margin, icon.getLayoutParams().width);
                if (hl != null) {
                    row.addView(icon, hl);
                    if (!(row instanceof FrameLayout)) {
                        icon.addOnLayoutChangeListener((v, l, t, r, b, ol, ot, or, ob) -> {
                            boolean rtl = row.getLayoutDirection() == View.LAYOUT_DIRECTION_RTL;
                            v.setTranslationX(rtl ? margin - l : (row.getWidth() - margin) - r);
                        });
                    }
                    return;
                }
            } catch (Throwable t) {
                ModuleLog.detail(TAG + "header icon: " + t);
            }
        }
        try {
            FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(icon.getLayoutParams().width,
                    icon.getLayoutParams().height, Gravity.TOP | Gravity.END);
            lp.setMarginEnd(margin);
            if (content instanceof FrameLayout) {
                content.addView(icon, lp);
                icon.bringToFront();
            } else {
                FrameLayout overlay = new FrameLayout(content.getContext());
                overlay.addView(icon, lp);
                content.addView(overlay, new ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            }
            icon.setElevation(30 * d);
        } catch (Throwable t) {
            ModuleLog.warn(TAG + "Could not add the search icon to the comments", t);
        }
    }

    private static ViewGroup headerRow(View sheet) {
        int id = ResIds.id(sheet.getContext(), "title_text_view");
        if (id == 0) return null;
        for (View v = sheet; v != null; v = v.getParent() instanceof View p ? p : null) {
            View title = v.findViewById(id);
            if (title != null) return title.getParent() instanceof ViewGroup g ? g : null;
        }
        return null;
    }

    private static ViewGroup.LayoutParams headerParams(ViewGroup row, int endMargin, int size) throws Exception {
        if (row instanceof FrameLayout) {
            FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(size, size, Gravity.END | Gravity.CENTER_VERTICAL);
            lp.setMarginEnd(endMargin);
            return lp;
        }
        View title = null;
        for (int i = 0; i < row.getChildCount(); i++)
            if (row.getChildAt(i) instanceof TextView t && t.getId() != View.NO_ID && (t.getId() >>> 24) != 0
                    && "title_text_view".equals(row.getResources().getResourceEntryName(t.getId()))) title = t;
        if (title == null) return null;
        ViewGroup.LayoutParams src = title.getLayoutParams();
        ViewGroup.MarginLayoutParams lp = (ViewGroup.MarginLayoutParams)
                src.getClass().getConstructor(int.class, int.class).newInstance(size, size);
        for (Class<?> k = src.getClass(); k != null && k != Object.class; k = k.getSuperclass())
            for (java.lang.reflect.Field f : ps.reso.instaeclipse.utils.core.SafeReflect.fields(k)) {
                if (java.lang.reflect.Modifier.isStatic(f.getModifiers()) || !f.getType().isPrimitive()) continue;
                try { f.setAccessible(true); f.set(lp, f.get(src)); } catch (Throwable ignored) {}
            }
        lp.width = size;
        lp.height = size;
        return lp;
    }

    private static Set<String> sortLabels(Context ctx) {
        String locale = ctx.getResources().getConfiguration().getLocales().toLanguageTags();
        if (locale.equals(sortLabelsLocale)) return sortLabelSet;
        Set<String> labels = new java.util.HashSet<>();
        if (sortEnum != null) {
            for (Object constant : sortEnum.getEnumConstants()) {
                for (Field f : sortEnum.getDeclaredFields()) {
                    if (Modifier.isStatic(f.getModifiers()) || f.getType() != int.class) continue;
                    try {
                        f.setAccessible(true);
                        int id = f.getInt(constant);
                        if (id > 0) labels.add(ctx.getString(id).trim());
                    } catch (Throwable ignored) {}
                }
            }
        }
        labels.remove("");
        sortLabelSet = labels;
        sortLabelsLocale = locale;
        return labels;
    }

    private static Class<?> sortEnum;
    private static Class<?> sortRowModel;
    private static Set<String> sortLabelSet = Collections.emptySet();
    private static String sortLabelsLocale;
    private static volatile int stateUpdates;
    private static volatile boolean stateHasSortRow;
    private static volatile long stateAt;
    private static volatile java.lang.ref.WeakReference<Object> sheetPost = new java.lang.ref.WeakReference<>(null);
    private static Field postField;

    static Object sheetPost() {
        return sheetPost.get();
    }

    private static Object postOf(Object sortRow) {
        if (sortRow == null) return null;
        try {
            if (postField == null || postField.getDeclaringClass() != sortRow.getClass()) {
                for (Field f : sortRow.getClass().getDeclaredFields()) {
                    if (Modifier.isStatic(f.getModifiers()) || !f.getType().getName().equals("com.instagram.feed.media.Media")) continue;
                    f.setAccessible(true);
                    postField = f;
                    break;
                }
            }
            return postField != null && postField.getDeclaringClass() == sortRow.getClass() ? postField.get(sortRow) : null;
        } catch (Throwable t) {
            return null;
        }
    }

    @android.annotation.SuppressLint("ViewConstructor")
    private static final class SortRowOverlay extends FrameLayout {
        private static final int NONE = Integer.MIN_VALUE;
        private static final long SCAN_INTERVAL = 100, FALLBACK_AFTER = 3000;
        private static java.lang.ref.WeakReference<SortRowOverlay> live = new java.lang.ref.WeakReference<>(null);
        private final FrameLayout content;
        private final android.widget.ImageView icon, headerIcon;
        private final int iconSize, edge;
        private final List<java.lang.ref.WeakReference<View>> lists = new ArrayList<>();
        private java.lang.ref.WeakReference<View> list = new java.lang.ref.WeakReference<>(null);
        private java.lang.ref.WeakReference<View> labelView = new java.lang.ref.WeakReference<>(null);
        private int nodeId = NONE, fullHeight, startUpdates, waits;
        private long nextScan, attachedAt;
        private boolean found, fallbackUsed, logged, waitLogged, rescanPending;
        private final android.graphics.Rect bounds = new android.graphics.Rect();
        private android.graphics.Rect clip;
        private final int[] loc = new int[2], listLoc = new int[2];
        private final Runnable rescan = () -> {
            rescanPending = false;
            invalidate();
        };
        private final android.view.ViewTreeObserver.OnPreDrawListener preDraw = () -> {
            if (lists.isEmpty()) {
                if (!fallbackUsed && getParent() instanceof View c && c.isDirty()) invalidate();
                return true;
            }
            for (java.lang.ref.WeakReference<View> r : lists) {
                View l = r.get();
                if (l != null && l.isDirty()) { invalidate(); break; }
            }
            return true;
        };

        SortRowOverlay(Context ctx, FrameLayout content, android.widget.ImageView icon, android.widget.ImageView headerIcon) {
            super(ctx);
            this.content = content;
            this.icon = icon;
            this.headerIcon = headerIcon;
            float d = ctx.getResources().getDisplayMetrics().density;
            iconSize = icon.getLayoutParams().width;
            edge = (int) (48 * d);
            setClickable(false);
            setFocusable(false);
            setElevation(30 * d);
            FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(iconSize, iconSize, Gravity.TOP | Gravity.END);
            lp.setMarginEnd((int) (6 * d));
            icon.setVisibility(INVISIBLE);
            addView(icon, lp);
        }

        static void poke() {
            SortRowOverlay o = live.get();
            if (o == null) return;
            o.post(() -> {
                o.nextScan = 0;
                o.invalidate();
                if (android.os.SystemClock.uptimeMillis() - o.attachedAt >= FALLBACK_AFTER) o.checkFallback();
            });
        }

        @Override
        protected void onAttachedToWindow() {
            super.onAttachedToWindow();
            live = new java.lang.ref.WeakReference<>(this);
            attachedAt = android.os.SystemClock.uptimeMillis();
            startUpdates = stateUpdates;
            getViewTreeObserver().addOnPreDrawListener(preDraw);
            for (long delay : new long[]{100, 300, 700, 1500}) postDelayed(this::invalidate, delay);
            postDelayed(this::checkFallback, FALLBACK_AFTER);
        }

        @Override
        protected void onDetachedFromWindow() {
            getViewTreeObserver().removeOnPreDrawListener(preDraw);
            if (live.get() == this) live = new java.lang.ref.WeakReference<>(null);
            super.onDetachedFromWindow();
        }

        @Override
        protected void dispatchDraw(android.graphics.Canvas canvas) {
            try {
                track();
            } catch (Throwable t) {
                if (!logged) { logged = true; ModuleLog.warn(TAG + "The search icon is hidden on this comment sheet: " + t); }
                show(false);
            }
            super.dispatchDraw(canvas);
        }

        private void checkFallback() {
            if (found || fallbackUsed || !isAttachedToWindow()) return;
            if (stateHasSortRow && stateAt >= attachedAt - 1500) {
                if (!waitLogged) { waitLogged = true; ModuleLog.probe(TAG + "search icon waits for the sort row to scroll into view"); }
                return;
            }
            if (stateUpdates == startUpdates && ++waits < 8) { postDelayed(this::checkFallback, 1500); return; }
            useFallback("no sort row in the list");
        }

        private void useFallback(String why) {
            if (fallbackUsed) return;
            fallbackUsed = true;
            ModuleLog.detail(TAG + "search icon in the header: " + why);
            post(() -> headerIcon(content, headerIcon));
        }

        private void rescanIn(long delay) {
            if (rescanPending) return;
            rescanPending = true;
            postDelayed(rescan, Math.max(16, delay));
        }

        private void track() throws Exception {
            View label = labelView.get();
            if (label != null && label.isAttachedToWindow() && label.isShown() && showsSortLabel(label)) {
                place(label);
                return;
            }
            labelView = new java.lang.ref.WeakReference<>(null);
            View l = list.get();
            android.view.accessibility.AccessibilityNodeProvider p = null;
            android.view.accessibility.AccessibilityNodeInfo n = null;
            if (l != null && l.isAttachedToWindow() && nodeId != NONE) {
                p = l.getAccessibilityNodeProvider();
                n = p != null ? p.createAccessibilityNodeInfo(nodeId) : null;
                if (n != null && !isSortLabel(n)) n = null;
            }
            if (n == null) {
                nodeId = NONE;
                long now = android.os.SystemClock.uptimeMillis();
                if (now < nextScan) { show(false); rescanIn(nextScan - now); return; }
                nextScan = now + SCAN_INTERVAL;
                n = scanAll();
                l = list.get();
                p = l != null ? l.getAccessibilityNodeProvider() : null;
                if (n == null || p == null) {
                    label = scanViews();
                    if (label == null) { show(false); return; }
                    labelView = new java.lang.ref.WeakReference<>(label);
                    fullHeight = 0;
                    clip = scrollerBounds(label);
                    setClipBounds(clip);
                    place(label);
                    return;
                }
                fullHeight = 0;
                clip = scrollBounds(p, n, l);
                setClipBounds(clip);
            }
            n.getBoundsInScreen(bounds);
            place(n.getText());
        }

        private void place(Object what) {
            if (what instanceof View label) {
                label.getLocationOnScreen(listLoc);
                bounds.set(listLoc[0], listLoc[1], listLoc[0] + label.getWidth(), listLoc[1] + label.getHeight());
            }
            getLocationOnScreen(loc);
            bounds.offset(-loc[0], -loc[1]);
            if (bounds.isEmpty()) { show(false); return; }
            fullHeight = Math.max(fullHeight, bounds.height());
            int cy = bounds.centerY();
            if (bounds.height() < fullHeight && clip != null) {
                if (bounds.top <= clip.top) cy = bounds.bottom - fullHeight / 2;
                else if (bounds.bottom >= clip.bottom) cy = bounds.top + fullHeight / 2;
            }
            float y = cy - iconSize / 2f;
            if (icon.getTranslationY() != y) icon.setTranslationY(y);
            show(true);
            if (!found) {
                found = true;
                ModuleLog.probe(TAG + "search icon on sort row " + (what instanceof View v ? v.getClass().getSimpleName() : "'" + what + "'")
                        + " at " + bounds.toShortString()
                        + " after " + (android.os.SystemClock.uptimeMillis() - attachedAt) + " ms");
                if (headerIcon.getParent() instanceof ViewGroup g) g.removeView(headerIcon);
            }
        }

        private void show(boolean visible) {
            int v = visible ? VISIBLE : INVISIBLE;
            if (icon.getVisibility() != v) icon.setVisibility(v);
        }

        private View scanViews() {
            View best = null;
            int bestTop = Integer.MAX_VALUE;
            java.util.ArrayDeque<View> stack = new java.util.ArrayDeque<>();
            stack.push(content);
            int visited = 0;
            while (!stack.isEmpty() && visited++ < 600) {
                View v = stack.pop();
                if (!v.isShown() || v instanceof SortRowOverlay || v.getClass().getName().endsWith("AndroidComposeView")) continue;
                if (showsSortLabel(v)) {
                    View scroller = scroller(v);
                    View edgeOf = scroller != null ? scroller : content;
                    edgeOf.getLocationOnScreen(listLoc);
                    v.getLocationOnScreen(loc);
                    boolean rtl = edgeOf.getLayoutDirection() == View.LAYOUT_DIRECTION_RTL;
                    int lead = rtl ? listLoc[0] + edgeOf.getWidth() - (loc[0] + v.getWidth()) : loc[0] - listLoc[0];
                    if (lead < edge && loc[1] < bestTop) {
                        best = v;
                        bestTop = loc[1];
                    }
                }
                if (v instanceof ViewGroup g) for (int i = g.getChildCount() - 1; i >= 0; i--) stack.push(g.getChildAt(i));
            }
            if (best != null) {
                View scroller = scroller(best);
                lists.clear();
                lists.add(new java.lang.ref.WeakReference<>(scroller != null ? scroller : content));
            }
            return best;
        }

        private boolean showsSortLabel(View v) {
            for (CharSequence t : viewTexts(v)) {
                String s = t.toString().trim();
                for (String label : sortLabelSet) if (s.equals(label)) return true;
            }
            return false;
        }

        private View scroller(View v) {
            for (View p = v.getParent() instanceof View pv ? pv : null; p != null && p != content;
                 p = p.getParent() instanceof View pv ? pv : null) {
                for (Class<?> k = p.getClass(); k != null && k != View.class; k = k.getSuperclass())
                    if (k.getName().equals("androidx.recyclerview.widget.RecyclerView")) return p;
            }
            return null;
        }

        private android.graphics.Rect scrollerBounds(View label) {
            View s = scroller(label);
            View area = s != null ? s : content;
            getLocationOnScreen(loc);
            area.getLocationOnScreen(listLoc);
            return new android.graphics.Rect(listLoc[0] - loc[0], listLoc[1] - loc[1],
                    listLoc[0] - loc[0] + area.getWidth(), listLoc[1] - loc[1] + area.getHeight());
        }

        private android.view.accessibility.AccessibilityNodeInfo scanAll() throws Exception {
            lists.clear();
            boolean anyProvider = false;
            int bestTop = Integer.MAX_VALUE;
            android.view.accessibility.AccessibilityNodeInfo best = null;
            for (View v : composeViews(content)) {
                lists.add(new java.lang.ref.WeakReference<>(v));
                android.view.accessibility.AccessibilityNodeProvider p = v.getAccessibilityNodeProvider();
                if (p == null) continue;
                anyProvider = true;
                int id = scan(p, v);
                android.view.accessibility.AccessibilityNodeInfo n = id != NONE ? p.createAccessibilityNodeInfo(id) : null;
                if (n == null) continue;
                n.getBoundsInScreen(bounds);
                if (bounds.top < bestTop) {
                    bestTop = bounds.top;
                    best = n;
                    nodeId = id;
                    list = new java.lang.ref.WeakReference<>(v);
                }
            }
            if (!anyProvider && !lists.isEmpty()) useFallback("list has no accessibility provider");
            return best;
        }

        private int scan(android.view.accessibility.AccessibilityNodeProvider p, View l) throws Exception {
            l.getLocationOnScreen(listLoc);
            boolean rtl = l.getLayoutDirection() == View.LAYOUT_DIRECTION_RTL;
            int nodes = 0;
            java.util.ArrayDeque<Integer> stack = new java.util.ArrayDeque<>();
            stack.push(View.NO_ID);
            Set<Integer> seen = new java.util.HashSet<>();
            while (!stack.isEmpty() && nodes < 150) {
                int id = stack.pop();
                if (!seen.add(id)) continue;
                android.view.accessibility.AccessibilityNodeInfo n = p.createAccessibilityNodeInfo(id);
                if (n == null) continue;
                nodes++;
                if (id != View.NO_ID && isSortLabel(n)) {
                    n.getBoundsInScreen(bounds);
                    int lead = rtl ? listLoc[0] + l.getWidth() - bounds.right : bounds.left - listLoc[0];
                    if (!bounds.isEmpty() && lead < edge) return id;
                }
                for (int i = n.getChildCount() - 1; i >= 0; i--) stack.push(A11yIds.childId(n, i));
            }
            return NONE;
        }

        private boolean isSortLabel(android.view.accessibility.AccessibilityNodeInfo n) {
            CharSequence t = n.getText() != null ? n.getText() : n.getContentDescription();
            if (t == null) return false;
            String s = t.toString().trim();
            for (String label : sortLabelSet) if (s.equals(label) || s.startsWith(label + ",")) return true;
            return false;
        }

        private android.graphics.Rect scrollBounds(android.view.accessibility.AccessibilityNodeProvider p,
                                                   android.view.accessibility.AccessibilityNodeInfo n, View l) {
            android.graphics.Rect r = new android.graphics.Rect();
            getLocationOnScreen(loc);
            try {
                android.view.accessibility.AccessibilityNodeInfo cur = n;
                for (int up = 0; up < 8 && cur != null; up++) {
                    int parent = A11yIds.parentId(cur);
                    cur = p.createAccessibilityNodeInfo(parent);
                    if (cur != null && cur.isScrollable()) {
                        cur.getBoundsInScreen(r);
                        r.offset(-loc[0], -loc[1]);
                        if (!r.isEmpty()) return r;
                    }
                }
            } catch (Throwable ignored) {}
            l.getLocationOnScreen(listLoc);
            r.set(listLoc[0] - loc[0], listLoc[1] - loc[1], listLoc[0] - loc[0] + l.getWidth(), listLoc[1] - loc[1] + l.getHeight());
            return r;
        }
    }

    private static final Map<Class<?>, Object[]> TEXT_ACCESS = new java.util.concurrent.ConcurrentHashMap<>();

    static List<CharSequence> viewTexts(View v) {
        List<CharSequence> out = new ArrayList<>();
        try {
            if (v instanceof TextView tv) {
                if (tv.getText() != null) out.add(tv.getText());
                return out;
            }
            Object[] access = TEXT_ACCESS.computeIfAbsent(v.getClass(), CommentSearchHook::textAccess);
            if (access.length == 0) return out;
            if (access[0] instanceof Method m) {
                if (m.invoke(v) instanceof List<?> texts)
                    for (Object t : texts) if (t instanceof CharSequence cs) out.add(cs);
            } else {
                for (Object f : access) {
                    Object value = ((Field) f).get(v);
                    if (value instanceof CharSequence cs) { out.add(cs); break; }
                    if (value instanceof android.text.Layout layout && layout.getText() != null) { out.add(layout.getText()); break; }
                }
            }
        } catch (Throwable ignored) {}
        return out;
    }

    private static Object[] textAccess(Class<?> type) {
        try {
            return new Object[]{type.getMethod("getTextContentText")};
        } catch (NoSuchMethodException ignored) {}
        if (!type.getName().endsWith(".RCTextView")) return new Object[0];
        List<Object> fields = new ArrayList<>();
        for (Class<?> k = type; k != null && k != View.class; k = k.getSuperclass()) {
            for (Field f : k.getDeclaredFields()) {
                if (Modifier.isStatic(f.getModifiers())) continue;
                if (f.getType() == CharSequence.class || f.getType() == android.text.Layout.class) {
                    f.setAccessible(true);
                    fields.add(f);
                }
            }
        }
        return fields.toArray();
    }

    private static List<View> composeViews(View root) {
        List<View> out = new ArrayList<>();
        java.util.ArrayDeque<View> st = new java.util.ArrayDeque<>();
        st.push(root);
        while (!st.isEmpty()) {
            View v = st.pop();
            if (!v.isShown() || v instanceof SortRowOverlay) continue;
            if (v.getClass().getName().equals("androidx.compose.ui.platform.AndroidComposeView")) out.add(v);
            if (v instanceof ViewGroup g) for (int i = 0; i < g.getChildCount(); i++) st.push(g.getChildAt(i));
        }
        out.sort((a, b) -> Integer.compare(b.getHeight(), a.getHeight()));
        return out;
    }

    private static void showSearch(Context c) {
        Activity act = null;
        for (Context k = c; k != null; k = k instanceof android.content.ContextWrapper w ? w.getBaseContext() : null)
            if (k instanceof Activity a) { act = a; break; }
        if (act == null) act = UIHookManager.topActivity();
        Object state = lastState.get();
        if (state != null) latest = collect(state);
        searchDialog(act != null ? act : c, new ArrayList<>(latest));
    }

    static android.app.Dialog searchDialog(Context ctx, List<String[]> all) {
        final float d = ctx.getResources().getDisplayMetrics().density;
        final IePalette palette = IePalette.of(ctx);
        final int cBg = palette.dialog;
        final int cField = palette.field;
        final int cText = palette.text;
        final int cSub = palette.sub;
        final int cAccent = palette.accent;
        final int cAccentText = IePalette.readable(cAccent, cBg, IePalette.TEXT);
        final int cHighlight = IePalette.withAlpha(cAccent, palette.dark ? 0x66 : 0x40);
        final float metaSp = 12;
        final android.graphics.drawable.Drawable heart = IeDialog.igIcon(ctx, "instagram_heart_outline_24", cSub);
        if (heart != null) {
            int size = Math.round(android.util.TypedValue.applyDimension(android.util.TypedValue.COMPLEX_UNIT_SP,
                    metaSp, ctx.getResources().getDisplayMetrics()));
            heart.setBounds(0, 0, size, size);
        }
        final java.text.NumberFormat counts = java.text.NumberFormat.getIntegerInstance();
        final String gifLabel = "🖼 " + I18n.t(ctx, R.string.ig_comment_media_gif);
        final String imageLabel = "🖼 " + I18n.t(ctx, R.string.ig_comment_media_image);

        final String[][] head = {header(all, sheetPost.get(), postRow)};
        final boolean[] more = {false};
        final List<Object[]> shown = new ArrayList<>();
        final Set<String> expanded = new java.util.HashSet<>();
        final int[] matches = {0};
        final String[] query = {""};

        android.app.Dialog dlg = new android.app.Dialog(ctx);
        dlg.requestWindowFeature(android.view.Window.FEATURE_NO_TITLE);

        LinearLayout card = new LinearLayout(ctx);
        card.setOrientation(LinearLayout.VERTICAL);
        int pad = (int) (18 * d);
        card.setPadding(pad, (int) (9 * d), pad, (int) (10 * d));
        card.setBackground(round(cBg, 22 * d));

        LinearLayout header = new LinearLayout(ctx);
        header.setGravity(Gravity.CENTER_VERTICAL);
        TextView title = new TextView(ctx);
        title.setText(I18n.t(ctx, R.string.ig_comment_search));
        title.setTextColor(cText);
        title.setTextSize(19);
        title.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        header.addView(title, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        View close = IeDialog.icon(ctx, "instagram_x_outline_24", "✕", 18, cSub);
        close.setContentDescription(I18n.t(ctx, R.string.ig_dialog_close));
        close.setOnClickListener(v -> dlg.dismiss());
        header.addView(close, new LinearLayout.LayoutParams((int) (48 * d), (int) (48 * d)));
        LinearLayout.LayoutParams hlp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        hlp.setMarginEnd(-(int) (13 * d));
        card.addView(header, hlp);

        LinearLayout field = new LinearLayout(ctx);
        field.setGravity(Gravity.CENTER_VERTICAL);
        field.setBackground(round(cField, 14 * d));
        field.setPadding((int) (12 * d), 0, (int) (4 * d), 0);
        field.addView(glyph(ctx, "instagram_search_outline_24", "🔍", 15, cSub, (int) (22 * d), 0));
        EditText et = new EditText(ctx);
        et.setSingleLine(true);
        et.setBackground(null);
        et.setTextColor(cText);
        et.setHintTextColor(cSub);
        et.setTextSize(15);
        et.setHint(I18n.t(ctx, R.string.ig_comment_search_field_hint));
        et.setPadding((int) (8 * d), (int) (12 * d), (int) (8 * d), (int) (12 * d));
        field.addView(et, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        View clear = glyph(ctx, "instagram_x_outline_24", "✕", 14, cSub, (int) (36 * d), (int) (9 * d));
        clear.setContentDescription(I18n.t(ctx, R.string.ig_comment_search_clear));
        clear.setVisibility(View.GONE);
        clear.setOnClickListener(v -> et.setText(""));
        field.addView(clear);
        LinearLayout.LayoutParams flp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        flp.topMargin = (int) (8 * d);
        card.addView(field, flp);

        LinearLayout status = new LinearLayout(ctx);
        status.setGravity(Gravity.CENTER_VERTICAL);
        TextView count = new TextView(ctx);
        count.setTextColor(cSub);
        count.setTextSize(13);
        status.addView(count, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        TextView loadBtn = new TextView(ctx);
        loadBtn.setText(I18n.t(ctx, R.string.ig_comment_search_load_all));
        loadBtn.setTextColor(palette.onAccent);
        loadBtn.setTextSize(13);
        loadBtn.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        loadBtn.setPadding((int) (14 * d), (int) (7 * d), (int) (14 * d), (int) (7 * d));
        loadBtn.setBackground(round(cAccent, 16 * d));
        TextView exportBtn = new TextView(ctx);
        exportBtn.setText(I18n.t(ctx, R.string.ig_comment_export));
        exportBtn.setTextColor(cAccentText);
        exportBtn.setTextSize(13);
        exportBtn.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        exportBtn.setPadding((int) (14 * d), (int) (6 * d), (int) (14 * d), (int) (6 * d));
        GradientDrawable outline = round(Color.TRANSPARENT, 16 * d);
        outline.setStroke(Math.max(1, (int) (1.5f * d)), cAccent);
        exportBtn.setBackground(outline);
        exportBtn.setOnClickListener(v -> {
            if (!CommentSearchPolicy.exportable(all)) {
                ps.reso.instaeclipse.utils.toast.IeToast.makeText(ctx, I18n.t(ctx, R.string.ig_comment_export_none), Toast.LENGTH_SHORT).show();
                return;
            }
            List<String[]> rows = new ArrayList<>(all);
            String[] rowsHead = head[0];
            Object post = sheetPost.get();
            IeDialog exDlg = new IeDialog(ctx).title(I18n.t(ctx, R.string.ig_comment_export));
            android.widget.CheckBox postBox = exDlg.checkBox(I18n.t(ctx, R.string.ig_comment_export_post_media), exportPostMedia);
            exDlg.secondary(I18n.t(ctx, R.string.ig_dialog_cancel), null);
            exDlg.primary(I18n.t(ctx, R.string.ig_comment_export), () -> {
                exportPostMedia = postBox.isChecked();
                ps.reso.instaeclipse.utils.toast.IeToast.makeText(ctx, I18n.t(ctx, R.string.ig_comment_export_working), Toast.LENGTH_SHORT).show();
                exportComments(ctx, rows, exportPostMedia, rowsHead, post);
            });
            exDlg.show();
        });
        LinearLayout.LayoutParams elp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        elp.setMarginEnd((int) (8 * d));
        status.addView(exportBtn, elp);
        status.addView(loadBtn);
        LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        slp.topMargin = (int) (10 * d);
        card.addView(status, slp);

        android.widget.ProgressBar bar = new android.widget.ProgressBar(ctx, null, android.R.attr.progressBarStyleHorizontal);
        bar.setIndeterminate(true);
        bar.setVisibility(View.INVISIBLE);
        bar.setIndeterminateTintList(android.content.res.ColorStateList.valueOf(cAccent));
        card.addView(bar, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, (int) (6 * d)));

        ListView lv = new ListView(ctx);
        lv.setDivider(new android.graphics.drawable.ColorDrawable(palette.line));
        lv.setDividerHeight(Math.max(1, (int) d / 2));
        lv.setSelector(new android.graphics.drawable.ColorDrawable(Color.TRANSPARENT));
        lv.setVerticalScrollBarEnabled(false);
        android.widget.BaseAdapter ad = new android.widget.BaseAdapter() {
            public int getCount() { return shown.size(); }
            public Object getItem(int i) { return shown.get(i); }
            public long getItemId(int i) { return i; }
            public int getViewTypeCount() { return 2; }
            public int getItemViewType(int i) { return (int) shown.get(i)[0]; }
            public boolean isEnabled(int i) { return true; }
            public View getView(int i, View conv, ViewGroup parent) {
                Object[] e = shown.get(i);
                String[] r = (String[]) e[1];
                if ((int) e[0] == 1) {
                    RepliesRow t = conv != null && conv.getTag() instanceof RepliesRow tag ? tag : new RepliesRow(ctx, d, cSub);
                    t.label.setText((String) e[3]);
                    return t.row;
                }
                ResultRow row = conv != null && conv.getTag() instanceof ResultRow tag ? tag : new ResultRow(ctx, d, cText, cSub, metaSp);
                CharSequence placeholder = CommentSearchPolicy.mediaOnly(r) ? (CommentSearchPolicy.gif(r) ? gifLabel : imageLabel) : null;
                row.bind(r, (boolean) e[2], query[0], cHighlight, meta(r, heart, counts), placeholder);
                return row.row;
            }
        };
        lv.setAdapter(ad);
        TextView empty = new TextView(ctx);
        empty.setText(I18n.t(ctx, R.string.ig_comment_search_empty));
        empty.setTextColor(cSub);
        empty.setTextSize(14);
        empty.setGravity(Gravity.CENTER);
        empty.setPadding(0, (int) (40 * d), 0, (int) (40 * d));
        FrameLayout results = new FrameLayout(ctx);
        results.addView(lv);
        results.addView(empty);
        int h = (int) (ctx.getResources().getDisplayMetrics().heightPixels * 0.55f);
        card.addView(results, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, h));

        TextView tip = new TextView(ctx);
        tip.setText(I18n.t(ctx, R.string.ig_comment_search_tip));
        tip.setTextColor(cSub);
        tip.setTextSize(12);
        tip.setGravity(Gravity.CENTER);
        tip.setPadding(0, (int) (8 * d), 0, 0);
        card.addView(tip);

        Runnable refresh = () -> {
            query[0] = et.getText().toString().trim();
            String q = query[0].toLowerCase(Locale.ROOT);
            Map<String, String[]> byId = new java.util.HashMap<>();
            for (String[] r : all) if (!r[2].isEmpty()) byId.put(r[2], r);
            Map<String, List<String[]>> kids = new java.util.HashMap<>();
            List<String[]> tops = new ArrayList<>();
            for (String[] r : all) {
                if (!r[3].isEmpty() && byId.containsKey(r[3])) kids.computeIfAbsent(r[3], k -> new ArrayList<>()).add(r);
                else tops.add(r);
            }
            for (List<String[]> l : kids.values()) l.sort((x, y) -> Long.compare(num(x[6]), num(y[6])));
            List<String[]> counted = CommentSearchPolicy.comments(all, head[0]);
            Set<String[]> countedRows = Collections.newSetFromMap(new IdentityHashMap<>());
            countedRows.addAll(counted);
            shown.clear();
            matches[0] = 0;
            for (String[] t : tops) {
                List<String[]> ch = kids.getOrDefault(t[2], Collections.emptyList());
                if (q.isEmpty()) {
                    shown.add(new Object[]{0, t, false, null});
                    if (ch.isEmpty()) continue;
                    if (expanded.contains(t[2])) {
                        for (String[] kid : ch) shown.add(new Object[]{0, kid, true, null});
                        shown.add(new Object[]{1, t, false, I18n.t(ctx, R.string.ig_comment_search_hide_replies)});
                    } else {
                        shown.add(new Object[]{1, t, false, I18n.t(ctx, R.string.ig_comment_search_view_replies, ch.size())});
                    }
                    continue;
                }
                boolean topHit = hit(t, q);
                List<String[]> hits = new ArrayList<>();
                for (String[] kid : ch) if (hit(kid, q)) hits.add(kid);
                if (!topHit && hits.isEmpty()) continue;
                if (topHit && countedRows.contains(t)) matches[0]++;
                for (String[] kid : hits) if (countedRows.contains(kid)) matches[0]++;
                shown.add(new Object[]{0, t, false, null});
                for (String[] kid : hits) shown.add(new Object[]{0, kid, true, null});
            }
            ad.notifyDataSetChanged();
            empty.setVisibility(shown.isEmpty() ? View.VISIBLE : View.GONE);
            clear.setVisibility(q.isEmpty() ? View.GONE : View.VISIBLE);
            exportBtn.setAlpha(CommentSearchPolicy.exportable(all) ? 1f : 0.4f);
            int total = counted.size();
            count.setText(q.isEmpty() ? I18n.t(ctx, more[0] ? R.string.ig_comment_search_maybe_more : R.string.ig_comment_search_count, total)
                    : I18n.t(ctx, R.string.ig_comment_search_matches, total, matches[0]));
        };
        Runnable pull = () -> {
            all.clear();
            all.addAll(latest);
            head[0] = header(all, sheetPost.get(), postRow);
            refresh.run();
        };
        Runnable idle = () -> {
            focusable(dlg, true);
            bar.setVisibility(View.INVISIBLE);
            loadBtn.setText(I18n.t(ctx, R.string.ig_comment_search_load_all));
            pull.run();
        };
        et.addTextChangedListener(new TextWatcher() {
            public void beforeTextChanged(CharSequence s, int a, int b, int n) {}
            public void onTextChanged(CharSequence s, int a, int b, int n) {}
            public void afterTextChanged(Editable s) { refresh.run(); }
        });
        loadBtn.setOnClickListener(v -> {
            if (loading != null) { stopLoading(); idle.run(); return; }
            more[0] = false;
            bar.setVisibility(View.VISIBLE);
            loadBtn.setText(I18n.t(ctx, R.string.ig_comment_search_stop));
            focusable(dlg, false);
            loadAll(n -> {
                if (n != all.size()) pull.run();
            }, maybeMore -> {
                more[0] = maybeMore;
                idle.run();
            });
        });
        lv.setOnItemClickListener((p, v, i, id) -> {
            Object[] e = shown.get(i);
            String[] r = (String[]) e[1];
            if ((int) e[0] == 1) {
                if (!expanded.remove(r[2])) expanded.add(r[2]);
                refresh.run();
                return;
            }
            String text = CommentSearchPolicy.copyText(r);
            if (text.isEmpty()) return;
            CopyFeedback.copy(ctx, "comment", text,
                    CommentSearchPolicy.mediaOnly(r) ? R.string.ig_toast_link_copied : R.string.ig_toast_comment_copied);
        });
        refresh.run();

        dlg.setContentView(card);
        dlg.setOnDismissListener(x -> stopLoading());
        dlg.show();
        android.view.Window w = dlg.getWindow();
        if (w != null) {
            w.setBackgroundDrawable(new android.graphics.drawable.ColorDrawable(Color.TRANSPARENT));
            w.setLayout((int) (ctx.getResources().getDisplayMetrics().widthPixels * 0.92f), ViewGroup.LayoutParams.WRAP_CONTENT);
            w.setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
        }
        return dlg;
    }

    private static View glyph(Context ctx, String name, String fallback, float sp, int tint, int box, int pad) {
        View v = IeDialog.icon(ctx, name, fallback, sp, tint);
        if (v instanceof android.widget.ImageView iv) {
            iv.setScaleType(android.widget.ImageView.ScaleType.FIT_CENTER);
            iv.setPadding(pad, pad, pad, pad);
        } else if (v instanceof TextView tv) {
            tv.setMaxLines(1);
            tv.setAutoSizeTextTypeUniformWithConfiguration(6, Math.max(7, Math.round(sp)), 1, android.util.TypedValue.COMPLEX_UNIT_SP);
        }
        v.setLayoutParams(new LinearLayout.LayoutParams(box, box));
        return v;
    }

    private static final class RepliesRow {
        final LinearLayout row;
        final TextView label;

        RepliesRow(Context ctx, float d, int cSub) {
            row = new LinearLayout(ctx);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setPadding((int) (48 * d), (int) (2 * d), 0, (int) (10 * d));
            View line = new View(ctx);
            line.setBackgroundColor(cSub);
            row.addView(line, new LinearLayout.LayoutParams((int) (24 * d), Math.max(1, (int) d)));
            label = new TextView(ctx);
            label.setTextColor(cSub);
            label.setTextSize(13);
            label.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
            label.setPadding((int) (10 * d), 0, 0, 0);
            row.addView(label);
            row.setTag(this);
        }
    }

    private static final class ResultRow {
        final LinearLayout row;
        final TextView avatar, user, meta, text;
        final GradientDrawable circle = new GradientDrawable();
        final float d;
        final int cText, cSub;

        ResultRow(Context ctx, float d, int cText, int cSub, float metaSp) {
            this.d = d;
            this.cText = cText;
            this.cSub = cSub;
            row = new LinearLayout(ctx);
            avatar = new TextView(ctx);
            avatar.setGravity(Gravity.CENTER);
            avatar.setTextColor(Color.WHITE);
            avatar.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
            circle.setShape(GradientDrawable.OVAL);
            avatar.setBackground(circle);
            row.addView(avatar, new LinearLayout.LayoutParams(0, 0));
            LinearLayout col = new LinearLayout(ctx);
            col.setOrientation(LinearLayout.VERTICAL);
            col.setPadding((int) (12 * d), 0, 0, 0);
            LinearLayout top = new LinearLayout(ctx);
            top.setGravity(Gravity.CENTER_VERTICAL);
            user = new TextView(ctx);
            user.setTextColor(cText);
            user.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
            user.setTextSize(13.5f);
            user.setSingleLine(true);
            top.addView(user);
            meta = new TextView(ctx);
            meta.setTextColor(cSub);
            meta.setTextSize(metaSp);
            meta.setSingleLine(true);
            top.addView(meta);
            col.addView(top);
            text = new TextView(ctx);
            text.setTextSize(14);
            text.setLineSpacing(2 * d, 1f);
            text.setPadding(0, (int) (2 * d), 0, 0);
            col.addView(text);
            row.addView(col, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
            row.setTag(this);
        }

        void bind(String[] r, boolean reply, String query, int highlight, CharSequence metaText, CharSequence placeholder) {
            int vertical = (int) ((reply ? 7 : 11) * d);
            row.setPadding(reply ? (int) (46 * d) : 0, vertical, 0, vertical);
            avatar.setText(HtmlExport.initial(r[0]));
            avatar.setTextSize(reply ? 12 : 14);
            circle.setColor(HtmlExport.avatarColor(r[0]));
            int size = (int) ((reply ? 28 : 36) * d);
            ViewGroup.LayoutParams lp = avatar.getLayoutParams();
            if (lp.width != size) {
                lp.width = size;
                lp.height = size;
                avatar.setLayoutParams(lp);
            }
            user.setText(highlight(r[0], query, highlight));
            meta.setVisibility(metaText.length() == 0 ? View.GONE : View.VISIBLE);
            meta.setText(metaText.length() == 0 ? metaText : new android.text.SpannableStringBuilder("  ").append(metaText));
            text.setTextColor(placeholder != null ? cSub : cText);
            text.setText(placeholder != null ? placeholder : highlight(r[1], query, highlight));
        }
    }

    private static void focusable(android.app.Dialog dlg, boolean on) {
        android.view.Window w = dlg.getWindow();
        if (w == null) return;
        if (on) {
            w.clearFlags(android.view.WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE);
            return;
        }
        View f = w.getCurrentFocus();
        if (f != null) {
            ((android.view.inputmethod.InputMethodManager) dlg.getContext().getSystemService(Context.INPUT_METHOD_SERVICE))
                    .hideSoftInputFromWindow(f.getWindowToken(), 0);
            f.clearFocus();
        }
        w.addFlags(android.view.WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE);
    }

    private static boolean hit(String[] r, String q) {
        return r[0].toLowerCase(Locale.ROOT).contains(q) || r[1].toLowerCase(Locale.ROOT).contains(q);
    }

    private static long num(String s) {
        try { return Long.parseLong(s); } catch (Throwable t) { return 0; }
    }

    private static CharSequence meta(String[] r, android.graphics.drawable.Drawable heart, java.text.NumberFormat counts) {
        android.text.SpannableStringBuilder b = new android.text.SpannableStringBuilder();
        long likes = num(r[4]);
        if (likes > 0) {
            b.append(counts.format(likes)).append(' ');
            int at = b.length();
            b.append('♥');
            if (heart != null) {
                int align = android.os.Build.VERSION.SDK_INT >= 29
                        ? android.text.style.DynamicDrawableSpan.ALIGN_CENTER : android.text.style.DynamicDrawableSpan.ALIGN_BASELINE;
                b.setSpan(new android.text.style.ImageSpan(heart, align), at, at + 1, android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            }
        }
        long created = num(r[6]);
        if (created > 0) {
            if (b.length() > 0) b.append(" · ");
            b.append(CommentSearchPolicy.age(System.currentTimeMillis() / 1000 - created, Locale.getDefault()));
        }
        return b;
    }

    private static String plural(Context ctx, int id, int n) {
        try {
            return I18n.moduleResources(ctx).getQuantityString(id, n, n);
        } catch (Throwable t) {
            return String.valueOf(n);
        }
    }

    private static void exportComments(Context ctx, List<String[]> rows, boolean includePostMedia, String[] head, Object post) {
        new Thread(() -> {
            try {
                String author = head[0], caption = head[1];
                List<String[]> comments = CommentSearchPolicy.comments(rows, head);
                String mediaId = CommentSearchPolicy.mediaId(rows);
                String code = MediaIds.shortcode(mediaId);
                String link = code.isEmpty() ? "" : "https://www.instagram.com/p/" + code + "/";

                List<String[]> media = new ArrayList<>();
                int[] ctr = {0};
                StringBuilder postGallery = new StringBuilder();
                if (includePostMedia && !mediaId.isEmpty()) {
                    try {
                        List<String> purls = postMediaUrls(ctx, mediaId, post);
                        int pi = 0;
                        for (String u : purls) {
                            if (u == null || !u.startsWith("http")) continue;
                            boolean vid = u.contains(".mp4");
                            String ext = vid ? ".mp4" : CommentSearchPolicy.mediaExt(u, "image");
                            String name = String.format(Locale.US, "post_%02d%s", ++pi, ext);
                            media.add(new String[]{u, name, vid ? "video/mp4" : CommentSearchPolicy.mediaMime(ext)});
                            postGallery.append(vid
                                    ? "<video class=media controls preload=none src=\"" + name + "\" onerror=\"this.remove()\"></video>"
                                    : "<img class=media loading=lazy src=\"" + name + "\" onerror=\"this.remove()\">");
                        }
                        ModuleLog.detail(TAG + "post media: " + pi + " item(s) for " + mediaId);
                    } catch (Throwable t) { ModuleLog.warn(TAG + "The export will not include the post's photos or videos: " + t); }
                }
                Map<String, List<String[]>> kids = new java.util.HashMap<>();
                Set<String> ids = new java.util.HashSet<>();
                for (String[] r : comments) ids.add(r[2]);
                List<String[]> tops = new ArrayList<>();
                for (String[] r : comments) {
                    if (!r[3].isEmpty() && ids.contains(r[3])) kids.computeIfAbsent(r[3], k -> new ArrayList<>()).add(r);
                    else tops.add(r);
                }
                for (List<String[]> l : kids.values()) l.sort((x, y) -> Long.compare(num(x[6]), num(y[6])));
                int replies = comments.size() - tops.size();
                java.text.SimpleDateFormat fmt = ps.reso.instaeclipse.utils.text.TimestampFormat.dialogDateFormat(ctx);
                java.text.NumberFormat counts = java.text.NumberFormat.getIntegerInstance();
                String now = fmt.format(new java.util.Date());
                String title = author.isEmpty() ? I18n.t(ctx, R.string.ig_comment_export_title)
                        : I18n.t(ctx, R.string.ig_comment_export_on_post, author);
                String[] labels = {I18n.t(ctx, R.string.ig_comment_media_gif), I18n.t(ctx, R.string.ig_comment_media_image)};
                String[] gone = {I18n.t(ctx, R.string.ig_comment_media_unavailable, labels[0]),
                        I18n.t(ctx, R.string.ig_comment_media_unavailable, labels[1])};

                StringBuilder h = new StringBuilder(64 * 1024);
                h.append("<!doctype html><html><head><meta charset=utf-8>")
                 .append("<meta name=viewport content=\"width=device-width,initial-scale=1\">")
                 .append("<title>").append(HtmlExport.esc(title)).append("</title><style>")
                 .append(":root{--bg:#fafafa;--card:#fff;--text:#111;--sub:#737373;--line:#ececec;--accent:#0095f6;--mark:#cfe8fd}")
                 .append("@media(prefers-color-scheme:dark){:root{--bg:#0b0b0c;--card:#18181a;--text:#f2f2f2;--sub:#9a9aa0;--line:#2a2a2d;--accent:#3aa8ff;--mark:#0b4a7a}}")
                 .append("*{box-sizing:border-box}body{margin:0;background:var(--bg);color:var(--text);font:15px/1.45 -apple-system,system-ui,Segoe UI,Roboto,sans-serif}")
                 .append(".wrap{max-width:720px;margin:0 auto;padding:16px}")
                 .append(".hero{background:linear-gradient(135deg,#feda75,#fa7e1e 25%,#d62976 50%,#962fbf 75%,#4f5bd5);border-radius:20px;padding:2px;margin-bottom:14px}")
                 .append(".heroin{background:var(--card);border-radius:18px;padding:18px}")
                 .append(".hero h1{margin:0 0 4px;font-size:20px}.hero a{color:var(--accent);text-decoration:none;word-break:break-all}")
                 .append(".cap{margin-top:10px;color:var(--text);white-space:normal;max-height:9em;overflow:auto}")
                 .append(".stats{display:flex;gap:8px;flex-wrap:wrap;margin-top:12px}.chip{background:var(--bg);border:1px solid var(--line);border-radius:999px;padding:4px 12px;font-size:13px;color:var(--sub)}")
                 .append(".search{position:sticky;top:0;z-index:2;padding:8px 0;background:var(--bg)}")
                 .append(".search input{width:100%;padding:12px 16px;border-radius:14px;border:1px solid var(--line);background:var(--card);color:var(--text);font-size:15px;outline:none}")
                 .append(".c{display:flex;gap:12px;padding:12px 14px;background:var(--card);border:1px solid var(--line);border-radius:16px;margin-top:10px}")
                 .append(".av{flex:none;width:38px;height:38px;border-radius:50%;display:flex;align-items:center;justify-content:center;color:#fff;font-weight:700}")
                 .append(".body{min-width:0;flex:1}.u{font-weight:700}.m{color:var(--sub);font-size:12px;margin-left:6px}.t{margin-top:2px;overflow-wrap:anywhere}")
                 .append(".replies{margin:6px 0 0 50px;border-left:2px solid var(--line);padding-left:10px}")
                 .append(".replies .c{margin-top:6px;padding:10px 12px;border-radius:14px}.replies .av{width:28px;height:28px;font-size:12px}")
                 .append("details>summary{cursor:pointer;color:var(--sub);font-size:13px;font-weight:600;margin:6px 0 0 50px;list-style:none}")
                 .append("details>summary::before{content:'— ';}mark{background:var(--mark);color:inherit;border-radius:3px}")
                 .append(".foot{color:var(--sub);font-size:12px;text-align:center;margin:20px 0 8px}.hide{display:none!important}")
                 .append(".media{display:block;margin-top:8px;max-width:min(260px,100%);max-height:320px;border-radius:12px;border:1px solid var(--line)}")
                 .append(".postmedia{display:flex;flex-wrap:wrap;gap:8px;margin-top:12px}.postmedia .media{margin-top:0;max-width:min(200px,46%)}")
                 .append(".tag{display:inline-block;margin-top:6px;padding:2px 10px;border-radius:999px;background:var(--bg);border:1px solid var(--line);color:var(--sub);font-size:12px}")
                 .append("</style></head><body><div class=wrap>");
                h.append(ps.reso.instaeclipse.utils.core.ExportBrand.htmlBanner(I18n.t(ctx, R.string.ig_comment_export_banner)));
                h.append("<div class=hero><div class=heroin><h1>💬 ").append(HtmlExport.esc(title)).append("</h1>");
                if (!link.isEmpty()) h.append("<a href=\"").append(link).append("\">").append(link).append("</a>");
                if (!caption.isEmpty()) h.append("<div class=cap>").append(HtmlExport.esc(caption)).append("</div>");
                if (postGallery.length() > 0) h.append("<div class=postmedia>").append(postGallery).append("</div>");
                h.append("<div class=stats><span class=chip>")
                 .append(HtmlExport.esc(plural(ctx, R.plurals.ig_comment_export_comments, tops.size())))
                 .append("</span><span class=chip>").append(HtmlExport.esc(plural(ctx, R.plurals.ig_comment_export_replies, replies)))
                 .append("</span><span class=chip>").append(HtmlExport.esc(I18n.t(ctx, R.string.ig_comment_export_exported, now)))
                 .append("</span></div></div></div>");
                h.append(HtmlExport.searchBox(I18n.t(ctx, R.string.ig_comment_search_field_hint))).append("<div id=list>");
                for (String[] t : tops) {
                    String top = commentHtml(t, fmt, counts, media, ctr, labels, gone);
                    List<String[]> ch = kids.getOrDefault(t[2], Collections.emptyList());
                    List<String> replyHtml = new ArrayList<>();
                    for (String[] r : ch) replyHtml.add(commentHtml(r, fmt, counts, media, ctr, labels, gone));
                    h.append(HtmlExport.thread(top, plural(ctx, R.plurals.ig_comment_export_replies, ch.size()), replyHtml));
                }
                h.append("</div><div class=foot>").append(HtmlExport.esc(I18n.t(ctx, R.string.ig_comment_export_loaded_only))).append("</div>")
                 .append(ps.reso.instaeclipse.utils.core.ExportBrand.htmlFooter()).append("</div>")
                 .append("<script>").append(HtmlExport.SEARCH_SCRIPT).append("</script>")
                 .append("</body></html>");

                String safe = DownloadFiles.safeSegment(author, "post");
                String stamp = new java.text.SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(new java.util.Date());
                String folder = "comments_" + safe + (code.isEmpty() ? "" : "_" + code) + "_" + stamp;
                String file = folder + ".html";
                final int cCount = comments.size();

                String page = h.toString();

                String saf = FeatureFlags.downloaderCustomUri;
                boolean delegated = false;
                if (saf != null && !saf.isEmpty()) {
                    try {
                        android.content.Intent it = new android.content.Intent();
                        it.setClassName("ps.reso.instaeclipse",
                                "ps.reso.instaeclipse.mods.media.DownloadSaveService");
                        it.putExtra("chatExportSubdir", "comments/" + folder);
                        it.putExtra("htmlFilename", file);
                        it.putExtra("htmlB64", android.util.Base64.encodeToString(
                                page.getBytes(java.nio.charset.StandardCharsets.UTF_8), android.util.Base64.NO_WRAP));
                        it.putExtra("saveUri", saf);
                        if (!media.isEmpty()) {
                            String[] us = new String[media.size()], ns = new String[media.size()], ms = new String[media.size()];
                            for (int i = 0; i < media.size(); i++) { us[i] = media.get(i)[0]; ns[i] = media.get(i)[1]; ms[i] = media.get(i)[2]; }
                            it.putExtra("mediaUrls", us);
                            it.putExtra("mediaNames", ns);
                            it.putExtra("mediaMimes", ms);
                        }
                        delegated = ps.reso.instaeclipse.utils.ipc.ExportDelegate.delegate(ctx, it, TAG,
                                () -> writeExportInProcess(ctx, page, media, file, cCount));
                        if (!delegated) {
                            ModuleLog.detail(TAG + "comment export outside the companion's limits (" + cCount + " comments, "
                                    + media.size() + " media, " + page.length() + " html chars), writing locally");
                        } else {
                            ModuleLog.line(TAG + "Exporting " + cCount + " comments and "
                                    + media.size() + " media files through the InstaEclipse app to comments/" + folder);
                        }
                    } catch (Throwable t) {
                        ModuleLog.warn(TAG + "Could not hand the export to the InstaEclipse app, saving it from Instagram instead (media links expire later): " + t);
                    }
                }

                if (!delegated) writeExportInProcess(ctx, page, media, file, cCount);
            } catch (Throwable t) {
                ModuleLog.error(TAG + "Could not export the comments", t);
                MAIN.post(() -> ps.reso.instaeclipse.utils.toast.IeToast.makeText(ctx, I18n.t(ctx, R.string.ig_toast_download_failed, String.valueOf(t.getMessage())), Toast.LENGTH_SHORT).show());
            }
        }).start();
    }

    private static void writeExportInProcess(Context ctx, String page, List<String[]> media, String file, int count) {
        try {
            Map<String, String> urls = new java.util.HashMap<>();
            for (String[] m : media) urls.put(m[1], m[0]);
            String html = HtmlExport.embed(page, urls);
            try (java.io.OutputStream out = ps.reso.instaeclipse.mods.media.FeedVideoDownloadHook.openCommentsExport(ctx, file)) {
                out.write(html.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            }
            ModuleLog.line(TAG + "Exported " + count + " comments to " + file);
            MAIN.post(() -> ps.reso.instaeclipse.utils.toast.IeToast.makeText(ctx, I18n.t(ctx, R.string.ig_comment_export_done, count, file), Toast.LENGTH_LONG).show());
        } catch (Throwable t) {
            ModuleLog.error(TAG + "Could not save the comments export", t);
            MAIN.post(() -> ps.reso.instaeclipse.utils.toast.IeToast.makeText(ctx, I18n.t(ctx, R.string.ig_toast_download_failed, String.valueOf(t.getMessage())), Toast.LENGTH_SHORT).show());
        }
    }

    private static String commentHtml(String[] r, java.text.SimpleDateFormat fmt, java.text.NumberFormat counts,
                                      List<String[]> media, int[] ctr, String[] labels, String[] gone) {
        StringBuilder meta = new StringBuilder();
        long likes = num(r[4]), created = num(r[6]);
        if (likes > 0) meta.append(HtmlExport.esc(counts.format(likes))).append(" ♥");
        if (created > 0) meta.append(likes > 0 ? " · " : "").append(HtmlExport.esc(fmt.format(new java.util.Date(created * 1000))));
        String extra = "";
        if (r.length > 9 && !r[8].isEmpty()) {
            int which = CommentSearchPolicy.gif(r) ? 0 : 1;
            String kind = r[9].isEmpty() ? "image" : r[9];
            String ext = CommentSearchPolicy.mediaExt(r[8], kind);
            String name = String.format(Locale.US, "c%04d_%s%s", ++ctr[0], kind, ext);
            media.add(new String[]{r[8], name, CommentSearchPolicy.mediaMime(ext)});
            extra = "<img class=media loading=lazy referrerpolicy=no-referrer alt=\"" + HtmlExport.esc(labels[which])
                    + "\" data-gone=\"" + HtmlExport.esc("🖼 " + gone[which]) + "\" src=\"" + name
                    + "\" onerror=\"const s=document.createElement('span');s.className='tag';s.textContent=this.dataset.gone;this.replaceWith(s)\">";
        }
        return HtmlExport.comment(r[0], meta.toString(), r[1], extra);
    }

    private static List<String> postMediaUrls(Context ctx, String mediaId, Object post) {
        if (mediaId == null || mediaId.isEmpty()) return new ArrayList<>();
        if (post != null && CommentSearchPolicy.sameMedia(mediaIdOf(post), mediaId)) {
            try {
                List<String> urls = ps.reso.instaeclipse.mods.media.FeedVideoDownloadHook.extractAllUrlsFromMedia(ctx, post);
                if (urls != null && !urls.isEmpty()) return urls;
            } catch (Throwable t) {
                ModuleLog.detail(TAG + "post media from the sheet's post: " + t);
            }
        }
        for (Object root : new Object[]{lastFragment.get(), lastState.get()}) {
            if (root == null) continue;
            List<String> urls = walkForMedia(ctx, root, mediaId);
            if (!urls.isEmpty()) return urls;
        }
        return new ArrayList<>();
    }

    private static List<String> walkForMedia(Context ctx, Object root, String mediaId) {
        Set<Object> seen = Collections.newSetFromMap(new IdentityHashMap<>());
        java.util.ArrayDeque<Object> q = new java.util.ArrayDeque<>();
        q.add(root);
        Map<Class<?>, Method> idGetters = new java.util.HashMap<>();
        int candidates = 0;
        while (!q.isEmpty() && seen.size() < 12000) {
            Object o = q.poll();
            if (o == null || !seen.add(o)) continue;
            try {
                if (o instanceof List<?> l) { for (Object it : l) q.add(it); continue; }
                if (o instanceof java.util.Map<?, ?> mp) { for (Object v : mp.values()) q.add(v); continue; }
                String n = o.getClass().getName();
                if (!n.startsWith("X.") && !n.startsWith("com.instagram.")) continue;
                if (!idGetters.containsKey(o.getClass())) {
                    Method g = null;
                    try { g = o.getClass().getMethod("getId"); if (g.getReturnType() != String.class) g = null; }
                    catch (Throwable ignored) {}
                    idGetters.put(o.getClass(), g);
                }
                Method g = idGetters.get(o.getClass());
                if (g != null) {
                    Object id = g.invoke(o);
                    if (id instanceof String s && !s.isEmpty()) {
                        candidates++;
                        if (CommentSearchPolicy.sameMedia(s, mediaId)) {
                            List<String> urls;
                            try { urls = ps.reso.instaeclipse.mods.media.FeedVideoDownloadHook.extractAllUrlsFromMedia(ctx, o); }
                            catch (Throwable ex) { urls = null; }
                            ModuleLog.detail(TAG + "post media candidate " + o.getClass().getName() + " id=" + s + " urls=" + (urls == null ? 0 : urls.size()));
                            if (urls != null && !urls.isEmpty()) return urls;
                        }
                    }
                }
                for (Class<?> k = o.getClass(); k != null && k != Object.class; k = k.getSuperclass())
                    for (Field f : ps.reso.instaeclipse.utils.core.SafeReflect.fields(k)) {
                        if (Modifier.isStatic(f.getModifiers()) || f.getType().isPrimitive() || f.getType() == String.class) continue;
                        try { f.setAccessible(true); Object v = f.get(o); if (v != null) q.add(v); } catch (Throwable ignored) {}
                    }
            } catch (Throwable ignored) {}
        }
        ModuleLog.detail(TAG + "walkForMedia: " + seen.size() + " nodes, " + candidates + " id-bearing, no match for " + mediaId);
        return new ArrayList<>();
    }

    private static GradientDrawable round(int color, float radius) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(color);
        g.setCornerRadius(radius);
        return g;
    }

    private static CharSequence highlight(String text, String query, int color) {
        if (query.isEmpty()) return text;
        android.text.SpannableString s = new android.text.SpannableString(text);
        String lt = text.toLowerCase(Locale.ROOT), lq = query.toLowerCase(Locale.ROOT);
        for (int i = lt.indexOf(lq); i >= 0 && lt.length() == text.length(); i = lt.indexOf(lq, i + lq.length())) {
            s.setSpan(new android.text.style.BackgroundColorSpan(color), i, i + lq.length(), android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            s.setSpan(new android.text.style.StyleSpan(android.graphics.Typeface.BOLD), i, i + lq.length(), android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        }
        return s;
    }

    private static void stopLoading() {
        if (loading != null) MAIN.removeCallbacks(loading);
        loading = null;
    }

    private static void loadAll(java.util.function.IntConsumer progress, java.util.function.Consumer<Boolean> done) {
        View root = sheet.get();
        if (!replyLinksBuilt && root != null) {
            replyLinks = buildReplyLinks(root.getContext());
            replyLinksBuilt = true;
        }
        if (root != null && expandSheet(root)) {
            Runnable start = new Runnable() {
                @Override
                public void run() {
                    if (loading != this) return;
                    loading = null;
                    startLoadAll(progress, done);
                }
            };
            loading = start;
            MAIN.postDelayed(start, 700);
            return;
        }
        startLoadAll(progress, done);
    }

    @android.annotation.SuppressLint("DiscouragedApi")
    private static List<java.util.regex.Pattern> buildReplyLinks(Context ctx) {
        List<String> texts = new ArrayList<>();
        try {
            android.content.res.Resources res = ctx.getResources();
            for (String name : new String[]{"view_x_more_replies", "view_x_previous_replies"}) {
                int id = res.getIdentifier(name, "plurals", ctx.getPackageName());
                if (id == 0) continue;
                for (int n : new int[]{1, 2, 5, 21, 101, 1234}) texts.add(res.getQuantityString(id, n, n));
            }
        } catch (Throwable t) {
            ModuleLog.detail(TAG + "reply link texts: " + t);
        }
        List<java.util.regex.Pattern> links = CommentSearchPolicy.replyMatchers(texts);
        ModuleLog.detail(TAG + "reply links: " + texts.size() + " texts, " + links.size() + " patterns");
        return links;
    }

    private static boolean expandSheet(View sheetRoot) {
        try {
            View decor = sheetRoot.getRootView();
            int id = ResIds.id(decor.getContext(), "bottom_sheet_drag_handle_frame");
            View handle = id != 0 ? decor.findViewById(id) : null;
            if (handle == null || !handle.isShown()) return false;
            int[] at = new int[2];
            handle.getLocationInWindow(at);
            float x = at[0] + handle.getWidth() / 2f, fromY = at[1] + handle.getHeight() / 2f;
            float toY = decor.getHeight() * 0.06f;
            if (fromY < decor.getHeight() * 0.25f) return false;
            final long down = android.os.SystemClock.uptimeMillis();
            final int steps = 12;
            decor.dispatchTouchEvent(android.view.MotionEvent.obtain(down, down, android.view.MotionEvent.ACTION_DOWN, x, fromY, 0));
            for (int i = 1; i <= steps; i++) {
                final float y = fromY + (toY - fromY) * i / steps;
                final boolean last = i == steps;
                MAIN.postDelayed(() -> {
                    long now = android.os.SystemClock.uptimeMillis();
                    android.view.MotionEvent move = android.view.MotionEvent.obtain(down, now, android.view.MotionEvent.ACTION_MOVE, x, y, 0);
                    decor.dispatchTouchEvent(move);
                    move.recycle();
                    if (last) {
                        android.view.MotionEvent up = android.view.MotionEvent.obtain(down, now + 10, android.view.MotionEvent.ACTION_UP, x, y, 0);
                        decor.dispatchTouchEvent(up);
                        up.recycle();
                    }
                }, i * 16L);
            }
            ModuleLog.detail(TAG + "load all: opening the sheet to full height");
            return true;
        } catch (Throwable t) {
            ModuleLog.detail(TAG + "expand sheet: " + t);
            return false;
        }
    }

    private static void startLoadAll(java.util.function.IntConsumer progress, java.util.function.Consumer<Boolean> done) {
        View root = sheet.get();
        View list = root != null ? scrollable(root) : null;
        ModuleLog.detail(TAG + "load all: list=" + (list != null ? list.getClass().getName() + " h=" + list.getHeight() : null));
        if (list == null) {
            ModuleLog.warn(TAG + "Could not load all comments: the comment list was not found, only the comments shown so far are searched");
            done.accept(false);
            return;
        }
        final long started = System.currentTimeMillis();
        final long[] lastChange = {started};
        final int[] lastCount = {latest.size()};
        final boolean[] grew = {false};
        loading = new Runnable() {
            @Override
            public void run() {
                if (loading != this) return;
                if (!list.isAttachedToWindow() || System.currentTimeMillis() - started > 5 * 60_000) {
                    loading = null;
                    done.accept(false);
                    return;
                }
                int opened = expandReplies(list);
                if (opened > 0) lastChange[0] = System.currentTimeMillis();
                else scrollDown(list);
                Object state = lastState.get();
                if (state != null) latest = collect(state);
                int count = latest.size();
                progress.accept(count);
                if (count != lastCount[0] || list.canScrollVertically(1)) {
                    if (count != lastCount[0]) {
                        lastChange[0] = System.currentTimeMillis();
                        grew[0] = count > lastCount[0];
                    }
                    lastCount[0] = count;
                }
                boolean rv = isRecyclerView(list.getClass());
                long idle = System.currentTimeMillis() - lastChange[0];
                CommentSearchPolicy.Load end = CommentSearchPolicy.loadState(rv, idle, !list.canScrollVertically(1), grew[0]);
                if (end != CommentSearchPolicy.Load.GOING) {
                    loading = null;
                    boolean maybeMore = end == CommentSearchPolicy.Load.MAYBE_MORE;
                    ModuleLog.line(TAG + (maybeMore ? "Loaded comments until no new ones came for a while, there may be more: " : "Loaded all comments: ") + count);
                    done.accept(maybeMore);
                    return;
                }
                MAIN.postDelayed(this, rv ? 300 : 450);
            }
        };
        MAIN.post(loading);
    }

    private static View scrollable(View root) {
        View rv = null, any = null, compose = null;
        java.util.ArrayDeque<View> st = new java.util.ArrayDeque<>();
        st.push(root);
        while (!st.isEmpty()) {
            View v = st.pop();
            if (!v.isShown()) continue;
            if (isRecyclerView(v.getClass()) && (rv == null || v.getHeight() > rv.getHeight())) rv = v;
            if ((v.canScrollVertically(1) || v.canScrollVertically(-1)) && (any == null || v.getHeight() > any.getHeight())) any = v;
            if (v.getClass().getName().equals("androidx.compose.ui.platform.AndroidComposeView")
                    && (compose == null || v.getHeight() > compose.getHeight())) compose = v;
            if (v instanceof ViewGroup g) for (int i = 0; i < g.getChildCount(); i++) st.push(g.getChildAt(i));
        }
        return rv != null ? rv : any != null ? any : compose;
    }

    private static boolean isRecyclerView(Class<?> c) {
        for (; c != null; c = c.getSuperclass()) if (c.getName().equals("androidx.recyclerview.widget.RecyclerView")) return true;
        return false;
    }

    private static volatile List<java.util.regex.Pattern> replyLinks = CommentSearchPolicy.replyMatchers(Collections.emptyList());
    private static volatile boolean replyLinksBuilt;
    private static boolean a11yLogged;

    private static int expandReplies(View list) {
        try {
            android.view.accessibility.AccessibilityNodeProvider p = list.getAccessibilityNodeProvider();
            if (p == null) { logA11y("no node provider"); return 0; }
            int clicked = 0, nodes = 0;
            java.util.ArrayDeque<Integer> q = new java.util.ArrayDeque<>();
            q.add(View.NO_ID);
            Set<Integer> seen = new java.util.HashSet<>();
            while (!q.isEmpty() && nodes < 3000) {
                int id = q.poll();
                if (!seen.add(id)) continue;
                android.view.accessibility.AccessibilityNodeInfo n = p.createAccessibilityNodeInfo(id);
                if (n == null) continue;
                nodes++;
                CharSequence t = n.getText() != null ? n.getText() : n.getContentDescription();
                if (id != View.NO_ID && CommentSearchPolicy.isReplyLink(replyLinks, t)) {
                    int target = id;
                    android.view.accessibility.AccessibilityNodeInfo cur = n;
                    for (int up = 0; up < 5 && cur != null && !cur.isClickable(); up++) {
                        target = A11yIds.parentId(cur);
                        cur = p.createAccessibilityNodeInfo(target);
                    }
                    boolean ok = cur != null && cur.isClickable()
                            && p.performAction(target, android.view.accessibility.AccessibilityNodeInfo.ACTION_CLICK, null);
                    logA11y("click '" + t + "' via " + (target == id ? "itself" : "parent " + target) + " -> " + ok);
                    if (ok) clicked++;
                }
                for (int i = 0; i < n.getChildCount(); i++) q.add(A11yIds.childId(n, i));
            }
            if (!a11yLogged) { a11yLogged = true; logA11y("nodes=" + nodes + " clicked=" + clicked); }
            if (clicked > 0) ModuleLog.probe(TAG + "load all: opened " + clicked + " reply thread(s)");
            return clicked;
        } catch (Throwable t) {
            logA11y(String.valueOf(t.getCause() != null ? t.getCause() : t));
            return 0;
        }
    }

    private static void logA11y(String msg) {
        ModuleLog.probe(TAG + "replies: " + msg);
    }

    private static void scrollDown(View list) {
        if (isRecyclerView(list.getClass())) {
            list.scrollBy(0, list.getHeight() * 3);
            return;
        }
        long t = android.os.SystemClock.uptimeMillis();
        float x = list.getWidth() / 2f, y0 = list.getHeight() * 0.8f, y1 = list.getHeight() * 0.2f;
        android.view.MotionEvent down = android.view.MotionEvent.obtain(t, t, android.view.MotionEvent.ACTION_DOWN, x, y0, 0);
        list.dispatchTouchEvent(down);
        for (int i = 1; i <= 6; i++) {
            android.view.MotionEvent mv = android.view.MotionEvent.obtain(t, t + i * 10L,
                    android.view.MotionEvent.ACTION_MOVE, x, y0 + (y1 - y0) * i / 6f, 0);
            list.dispatchTouchEvent(mv);
            mv.recycle();
        }
        android.view.MotionEvent up = android.view.MotionEvent.obtain(t, t + 70, android.view.MotionEvent.ACTION_UP, x, y1, 0);
        list.dispatchTouchEvent(up);
        down.recycle();
        up.recycle();
    }
}
