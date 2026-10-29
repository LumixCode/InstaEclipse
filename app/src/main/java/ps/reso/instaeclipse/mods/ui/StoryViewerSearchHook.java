package ps.reso.instaeclipse.mods.ui;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.Context;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.text.Editable;
import android.text.TextWatcher;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;

import org.luckypray.dexkit.query.FindMethod;
import org.luckypray.dexkit.query.matchers.MethodMatcher;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.WeakHashMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

import ps.reso.instaeclipse.xposedcompat.XC_MethodHook;
import ps.reso.instaeclipse.xposedcompat.XposedBridge;
import ps.reso.instaeclipse.R;
import ps.reso.instaeclipse.utils.feature.FeatureFlags;
import ps.reso.instaeclipse.utils.feature.FeatureStatusTracker;
import ps.reso.instaeclipse.utils.i18n.I18n;
import ps.reso.instaeclipse.utils.log.ModuleLog;
import ps.reso.instaeclipse.utils.ui.IePalette;
import ps.reso.instaeclipse.utils.ui.ResIds;
import ps.reso.instaeclipse.utils.users.UserUtils;
import ps.reso.instaeclipse.utils.core.DexKitQueryCache;
import ps.reso.instaeclipse.utils.core.DexKitQueryCache.Found;

public class StoryViewerSearchHook {

    private static final String TAG = "(IE|ViewerSearch) ";
    private static final Map<Object, String> QUERIES = new WeakHashMap<>();
    private static final Map<Object, Boolean> INJECTED = new WeakHashMap<>();
    static final Method NONE;
    static { try { NONE = Object.class.getMethod("toString"); } catch (NoSuchMethodException e) { throw new RuntimeException(e); } }
    static final Map<Class<?>, Method> ADAPTER_GETTERS = new ConcurrentHashMap<>();
    private static Method rebuild;
    private static Class<?> recordClass;

    public void install(ClassLoader cl) {
        try {
            Class<?> adapter = null;
            for (Found md : DexKitQueryCache.methods(FindMethod.create().matcher(MethodMatcher.create()
                    .usingStrings("search_result_rendered")))) {
                adapter = cl.loadClass(md.getClassName());
                break;
            }
            for (Found md : DexKitQueryCache.methods(FindMethod.create().matcher(MethodMatcher.create()
                    .usingStrings("XDTViewerFeedbackDict")))) {
                recordClass = cl.loadClass(md.getClassName());
                break;
            }
            if (adapter == null) {
                ModuleLog.error(TAG + "Will not work: the story viewers list was not found in this Instagram version");
                ModuleLog.detail(TAG + "viewer adapter not found");
                return;
            }
            Method build = null;
            for (Method m : ps.reso.instaeclipse.utils.core.SafeReflect.methods(adapter)) {
                Class<?>[] p = m.getParameterTypes();
                if (m.getReturnType() == void.class && p.length == 4 && p[0] == List.class
                        && p[1] == boolean.class && p[2] == boolean.class && p[3] == boolean.class) build = m;
            }
            for (Found md : DexKitQueryCache.methods(FindMethod.create().matcher(MethodMatcher.create()
                    .declaredClass(adapter.getName()).paramCount(0).returnType("void")
                    .usingStrings("dismissed_reel_viewers_list_megaphone")))) {
                rebuild = md.getMethodInstance(cl);
                break;
            }
            if (build == null) {
                ModuleLog.error(TAG + "Will not work: the story viewers list was not found in this Instagram version");
                ModuleLog.detail(TAG + "row builder not found");
                return;
            }
            build.setAccessible(true);
            if (rebuild != null) rebuild.setAccessible(true);

            XposedBridge.hookMethod(build, new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    if (!FeatureFlags.storyViewerSearch) return;
                    Object ad = param.thisObject;
                    injectSearchBox(ad);
                    String q = QUERIES.get(ad);
                    if (q == null || q.isEmpty() || !(param.args[0] instanceof List<?> list)) return;
                    List<Object> out = new ArrayList<>();
                    for (Object item : list) {
                        if (recordClass != null && !recordClass.isInstance(item)) { out.add(item); continue; }
                        String u = username(item);
                        if (u != null && u.toLowerCase(Locale.ROOT).contains(q)) out.add(item);
                    }
                    param.args[0] = out;
                }
            });
            ModuleLog.ok(TAG + "ready");
            ModuleLog.detail(TAG + "adapter " + adapter.getName() + "." + build.getName()
                    + " rebuild=" + (rebuild != null ? rebuild.getName() : "none"));
            FeatureStatusTracker.setHooked("StoryViewerSearch");
        } catch (Throwable t) {
            ModuleLog.error(TAG + "Will not work: setup failed", t);
        }
    }

    private static String username(Object record) {
        try {
            for (Class<?> k = record.getClass(); k != null && k != Object.class; k = k.getSuperclass())
                for (Field f : ps.reso.instaeclipse.utils.core.SafeReflect.fields(k)) {
                    if (Modifier.isStatic(f.getModifiers()) || !f.getType().getName().equals("com.instagram.user.model.User")) continue;
                    f.setAccessible(true);
                    return UserUtils.callUsernameGetter(f.get(record));
                }
        } catch (Throwable ignored) {}
        return null;
    }

    private static void injectSearchBox(Object adapter) {
        if (INJECTED.containsKey(adapter)) return;
        Activity top = UIHookManager.topActivity();
        Activity main = UIHookManager.getCurrentActivity();
        Activity runner = top != null ? top : main;
        if (runner == null) return;
        runner.runOnUiThread(() -> {
            if (INJECTED.containsKey(adapter)) return;
            View list = findListIn(top, adapter);
            if (list == null && main != top) list = findListIn(main, adapter);
            if (list == null || !(list.getParent() instanceof ViewGroup parent)) return;
            INJECTED.put(adapter, Boolean.TRUE);
            Context ctx = list.getContext();
            View box = searchBox(ctx, q -> {
                QUERIES.put(adapter, q);
                try { if (rebuild != null) rebuild.invoke(adapter); } catch (Throwable t) {
                    ModuleLog.warn(TAG + "Could not refresh the viewers list: " + t.getClass().getSimpleName() + ": " + t.getMessage());
                }
            });
            float d = ctx.getResources().getDisplayMetrics().density;
            ViewGroup.MarginLayoutParams lp = new ViewGroup.MarginLayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            int m = (int) (12 * d);
            lp.setMargins(m, m / 2, m, m / 2);
            try {
                parent.addView(box, parent.indexOfChild(list), lp);
                ModuleLog.probe(TAG + "search box added in " + parent.getClass().getSimpleName());
            } catch (Throwable t) {
                ModuleLog.warn(TAG + "Could not add the search box: " + t.getClass().getSimpleName() + ": " + t.getMessage());
            }
        });
    }

    private static View findListIn(Activity activity, Object adapter) {
        if (activity == null || activity.isFinishing() || activity.isDestroyed() || activity.getWindow() == null) return null;
        return findListFor(activity.getWindow().getDecorView(), adapter);
    }

    static ViewGroup searchBox(Context ctx, Consumer<String> onQuery) {
        float d = ctx.getResources().getDisplayMetrics().density;
        IePalette palette = IePalette.of(ctx);
        int field = fieldColor(ctx, palette);
        int text = IePalette.readable(palette.text, field, IePalette.TEXT);
        int hint = IePalette.readable(palette.sub, field, IePalette.TEXT);

        LinearLayout box = new LinearLayout(ctx);
        box.setOrientation(LinearLayout.HORIZONTAL);
        box.setGravity(Gravity.CENTER_VERTICAL);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(field);
        bg.setCornerRadius(10 * d);
        box.setBackground(bg);

        EditText et = new EditText(ctx);
        et.setHint(I18n.t(ctx, R.string.ig_viewer_search_hint));
        et.setSingleLine(true);
        et.setTextColor(text);
        et.setHintTextColor(hint);
        et.setBackground(null);
        int ph = (int) (14 * d), pv = (int) (10 * d);
        et.setPaddingRelative(ph, pv, 0, pv);
        box.addView(et, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        ImageView clear = new ImageView(ctx);
        Drawable x = clearIcon(ctx);
        if (x != null) {
            x = x.mutate();
            x.setTint(hint);
            clear.setImageDrawable(x);
        }
        clear.setScaleType(ImageView.ScaleType.CENTER_INSIDE);
        int pad = (int) (12 * d);
        clear.setPadding(pad, pad, pad, pad);
        TypedValue ripple = new TypedValue();
        if (ctx.getTheme().resolveAttribute(android.R.attr.selectableItemBackgroundBorderless, ripple, true) && ripple.resourceId != 0) {
            try { clear.setBackground(ctx.getDrawable(ripple.resourceId)); } catch (Throwable ignored) {}
        }
        String label = I18n.t(ctx, R.string.loc_picker_clear_search);
        clear.setContentDescription(label);
        clear.setTooltipText(label);
        clear.setVisibility(View.INVISIBLE);
        clear.setOnClickListener(v -> et.setText(""));
        int size = (int) (48 * d);
        box.addView(clear, new LinearLayout.LayoutParams(size, size));

        et.addTextChangedListener(new TextWatcher() {
            public void beforeTextChanged(CharSequence s, int a, int b, int c) {}
            public void onTextChanged(CharSequence s, int a, int b, int c) {}
            public void afterTextChanged(Editable s) {
                clear.setVisibility(s.length() > 0 ? View.VISIBLE : View.INVISIBLE);
                onQuery.accept(s.toString().trim().toLowerCase(Locale.ROOT));
            }
        });
        return box;
    }

    private static int fieldColor(Context ctx, IePalette palette) {
        int attr = ResIds.attr(ctx, "igds_color_highlight_background");
        TypedValue value = new TypedValue();
        try {
            if (attr != 0 && ctx.getTheme().resolveAttribute(attr, value, true)) {
                if (value.type >= TypedValue.TYPE_FIRST_COLOR_INT && value.type <= TypedValue.TYPE_LAST_COLOR_INT) return value.data;
                if (value.resourceId != 0) return ctx.getColorStateList(value.resourceId).getDefaultColor();
            }
        } catch (Throwable ignored) {}
        return palette.field;
    }

    @SuppressLint("UseCompatLoadingForDrawables")
    private static Drawable clearIcon(Context ctx) {
        for (String name : new String[]{"instagram_x_pano_outline_24", "instagram_x_outline_24"}) {
            int id = ResIds.drawable(ctx, name);
            if (id == 0) continue;
            try { return ctx.getDrawable(id); } catch (Throwable ignored) {}
        }
        try { return ctx.getDrawable(android.R.drawable.ic_menu_close_clear_cancel); } catch (Throwable t) { return null; }
    }

    static View findListFor(View root, Object adapter) {
        java.util.ArrayDeque<ViewGroup> st = new java.util.ArrayDeque<>();
        if (root instanceof ViewGroup g) st.push(g);
        while (!st.isEmpty()) {
            ViewGroup v = st.pop();
            Method ga = ADAPTER_GETTERS.computeIfAbsent(v.getClass(), StoryViewerSearchHook::adapterGetter);
            if (ga != NONE) {
                try {
                    if (ga.invoke(v) == adapter) return v;
                } catch (Throwable ignored) {}
            }
            for (int i = 0; i < v.getChildCount(); i++) if (v.getChildAt(i) instanceof ViewGroup child) st.push(child);
        }
        return null;
    }

    private static Method adapterGetter(Class<?> type) {
        try {
            return type.getMethod("getAdapter");
        } catch (Throwable t) {
            return NONE;
        }
    }
}
