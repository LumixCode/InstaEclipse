package ps.reso.instaeclipse.mods.ui;

import android.app.Activity;
import android.graphics.Color;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewTreeObserver;
import android.widget.ImageView;
import android.widget.LinearLayout;

import org.luckypray.dexkit.query.FindMethod;
import org.luckypray.dexkit.query.matchers.MethodMatcher;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.List;

import ps.reso.instaeclipse.xposedcompat.XC_MethodHook;
import ps.reso.instaeclipse.xposedcompat.XposedHelpers;
import ps.reso.instaeclipse.R;
import ps.reso.instaeclipse.utils.feature.FeatureFlags;
import ps.reso.instaeclipse.utils.feature.FeatureStatusTracker;
import ps.reso.instaeclipse.utils.i18n.I18n;
import ps.reso.instaeclipse.utils.log.ModuleLog;
import ps.reso.instaeclipse.utils.ui.ResIds;
import ps.reso.instaeclipse.utils.core.DexKitQueryCache;
import ps.reso.instaeclipse.utils.core.DexKitQueryCache.Found;

public class SavedOnProfileHook {
    private static final String TAG = "(IE|SavedProfile) ";
    private static final String HANDLER = "com.instagram.urlhandlers.saved.SavedUrlHandlerActivity";
    private static final String FRAGMENT = "com.instagram.profile.fragment.UserDetailFragment";
    private static final String SESSION = "com.instagram.common.session.UserSession";
    private static final String BTN_TAG = "ie_saved_profile_btn";

    private static Method factory, navigator, navPush, navCommit, navMake;
    private static java.lang.reflect.Constructor<?> navCtor;
    private static boolean logged;
    private static final java.util.Set<View> watched =
            java.util.Collections.newSetFromMap(new java.util.WeakHashMap<>());

    public void install(ClassLoader cl) {
        if (!FeatureFlags.savedOnProfile) return;
        try {
            List<Method> invoked = new ArrayList<>();
            for (Found md : DexKitQueryCache.methods(FindMethod.create().matcher(MethodMatcher.create()
                    .declaredClass(HANDLER).paramCount(3)))) {
                for (Found inv : DexKitQueryCache.invokesCached(md)) {
                    if (!inv.isMethod()) continue;
                    Method m;
                    try { m = inv.getMethodInstance(cl); } catch (Throwable t) { continue; }
                    Class<?>[] p = m.getParameterTypes();
                    Class<?> fragment = cl.loadClass("androidx.fragment.app.Fragment");
                    if (!Modifier.isStatic(m.getModifiers())) {
                        if (p.length == 2 && p[0] == android.os.Bundle.class && p[1] == fragment) navPush = m;
                        invoked.add(m);
                        continue;
                    }
                    if (p.length == 2 && Activity.class.isAssignableFrom(p[0]) && m.getReturnType() != void.class
                            && !m.getReturnType().isPrimitive()) { navMake = m; continue; }
                    if (savedFactory(p) && fragment.isAssignableFrom(m.getReturnType())) factory = m;
                    else if (p.length == 3 && p[0] == fragment && Activity.class.isAssignableFrom(p[1])
                            && m.getReturnType() == void.class) {
                        navigator = m;
                        for (Found n : DexKitQueryCache.invokesCached(inv)) {
                            try {
                                if (n.isConstructor()) navCtor = n.getConstructorInstance(cl);
                                else if (n.isMethod()) {
                                    Method nm = n.getMethodInstance(cl);
                                    Class<?>[] q = nm.getParameterTypes();
                                    if (q.length == 2 && q[0] == android.os.Bundle.class && q[1] == fragment) navPush = nm;
                                    else if (q.length == 0 && nm.getReturnType() == void.class) navCommit = nm;
                                }
                            } catch (Throwable ignored) {}
                        }
                    }
                }
            }
            if (navMake != null && navPush != null) navCommit = navCommit(invoked, navPush.getDeclaringClass());
            boolean built = navMake != null && navPush != null && navCommit != null
                    && navMake.getReturnType() == navPush.getDeclaringClass();
            if (!built) navMake = null;
            if (factory == null || (navigator == null && navMake == null)) {
                ModuleLog.error(TAG + "Will not work: the Saved screen was not found in this Instagram version");
                ModuleLog.detail(TAG + "factory=" + factory + " navigator=" + navigator + " navMake=" + navMake);
                return;
            }
            factory.setAccessible(true);
            if (navigator != null) navigator.setAccessible(true);
            if (navMake != null) { navMake.setAccessible(true); navPush.setAccessible(true); navCommit.setAccessible(true); }
            XposedHelpers.findAndHookMethod(cl.loadClass("androidx.fragment.app.Fragment"), "onResume", new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    if (!FeatureFlags.savedOnProfile || !isProfileFragment(param.thisObject)) return;
                    final Object frag = param.thisObject;
                    View root;
                    try { root = (View) frag.getClass().getMethod("getView").invoke(frag); } catch (Throwable t) { return; }
                    if (root == null) return;
                    root.postDelayed(() -> inject(frag, root), 500);
                    if (watched.add(root)) watch(frag, root);
                }
            });
            FeatureStatusTracker.setHooked("SavedOnProfile");
            ModuleLog.ok(TAG + "ready");
            ModuleLog.detail(TAG + factory.getDeclaringClass().getName() + "." + factory.getName()
                    + " / " + (navigator != null ? navigator : navMake));
        } catch (Throwable t) {
            ModuleLog.error(TAG + "Will not work: setup failed", t);
        }
    }

    private static boolean savedFactory(Class<?>[] p) {
        if (p.length < 4 || p[1] != String.class) return false;
        for (int i = 2; i < p.length; i++) if (p[i] != boolean.class) return false;
        return true;
    }

    private static void watch(Object frag, View root) {
        final boolean[] pending = {false};
        final boolean[] own = {false};
        final ViewTreeObserver.OnGlobalLayoutListener[] relayout = new ViewTreeObserver.OnGlobalLayoutListener[1];
        View.OnAttachStateChangeListener detach = new View.OnAttachStateChangeListener() {
            @Override public void onViewAttachedToWindow(View v) {}
            @Override public void onViewDetachedFromWindow(View v) {
                unwatch(v, relayout[0], this);
            }
        };
        relayout[0] = () -> {
            if (!FeatureFlags.savedOnProfile || pending[0]) return;
            pending[0] = true;
            root.post(() -> {
                pending[0] = false;
                if (!FeatureFlags.savedOnProfile) return;
                if (!own[0]) {
                    Boolean mine = ownProfile(root);
                    if (mine == null) return;
                    if (!mine) {
                        unwatch(root, relayout[0], detach);
                        return;
                    }
                    own[0] = true;
                }
                if (root.findViewWithTag(BTN_TAG) == null) inject(frag, root);
            });
        };
        root.getViewTreeObserver().addOnGlobalLayoutListener(relayout[0]);
        root.addOnAttachStateChangeListener(detach);
    }

    private static void unwatch(View root, ViewTreeObserver.OnGlobalLayoutListener relayout,
                                View.OnAttachStateChangeListener detach) {
        root.getViewTreeObserver().removeOnGlobalLayoutListener(relayout);
        root.removeOnAttachStateChangeListener(detach);
        watched.remove(root);
    }

    private static Boolean ownProfile(View root) {
        int createId = ResIds.id(root.getContext(), "profile_header_create_button");
        int rightId = ResIds.id(root.getContext(), "right_action_bar_buttons");
        if (createId == 0 || rightId == 0) return false;
        if (root.findViewById(createId) != null) return true;
        return root.findViewById(rightId) instanceof ViewGroup bar && bar.getChildCount() > 0 ? Boolean.FALSE : null;
    }

    static Method navCommit(List<Method> invoked, Class<?> navigatorClass) {
        Method found = null;
        for (Method m : invoked) {
            int mod = m.getModifiers();
            if (m.getDeclaringClass() != navigatorClass || m.getParameterCount() != 0 || m.getReturnType() != void.class
                    || Modifier.isStatic(mod) || !Modifier.isPublic(mod)) continue;
            if (found != null && !found.equals(m)) return null;
            found = m;
        }
        return found;
    }

    private static boolean isProfileFragment(Object f) {
        for (Class<?> c = f.getClass(); c != null && c != Object.class; c = c.getSuperclass())
            if (c.getName().equals(FRAGMENT)) return true;
        return false;
    }

    private static void inject(Object frag, View root) {
        try {
            android.content.Context ctx = root.getContext();
            android.content.res.Resources r = root.getResources();
            int createId = ResIds.id(ctx, "profile_header_create_button");
            int rightId = ResIds.id(ctx, "right_action_bar_buttons");
            if (createId == 0 || rightId == 0 || root.findViewById(createId) == null) return;
            if (!(root.findViewById(rightId) instanceof ViewGroup bar) || bar.findViewWithTag(BTN_TAG) != null) return;
            ImageView btn = new ImageView(root.getContext());
            btn.setTag(BTN_TAG);
            int icon = ResIds.drawable(ctx, "instagram_save_outline_24");
            if (icon != 0) {
                btn.setImageResource(icon);
                View sib = bar.getChildCount() > 0 ? bar.getChildAt(0) : null;
                if (sib instanceof ImageView iv && iv.getImageTintList() != null) btn.setImageTintList(iv.getImageTintList());
                else if (sib instanceof ImageView iv && iv.getColorFilter() != null) btn.setColorFilter(iv.getColorFilter());
                else btn.setColorFilter(Color.WHITE);
            }
            btn.setScaleType(ImageView.ScaleType.CENTER);
            btn.setContentDescription(I18n.t(root.getContext(), R.string.ig_saved_on_profile_button));
            int w = bar.getChildCount() > 0 && bar.getChildAt(0).getWidth() > 0 ? bar.getChildAt(0).getWidth()
                    : (int) (48 * r.getDisplayMetrics().density);
            btn.setLayoutParams(new LinearLayout.LayoutParams(w, ViewGroup.LayoutParams.MATCH_PARENT));
            btn.setOnClickListener(v -> openSaved(frag, v));
            bar.addView(btn, 0);
            if (!logged) { logged = true; ModuleLog.detail(TAG + "button added"); }
        } catch (Throwable t) {
            ModuleLog.warn(TAG + "Could not add the Saved button to your profile: " + t.getClass().getSimpleName() + ": " + t.getMessage());
        }
    }

    private static Object findSession(Object o, int depth) {
        java.util.List<Object> next = new java.util.ArrayList<>();
        for (Class<?> c = o.getClass(); c != null && c != Object.class; c = c.getSuperclass())
            for (Field f : ps.reso.instaeclipse.utils.core.SafeReflect.fields(c)) {
                if (Modifier.isStatic(f.getModifiers()) || f.getType().isPrimitive()) continue;
                try {
                    f.setAccessible(true);
                    Object v = f.get(o);
                    if (v == null) continue;
                    if (v.getClass().getName().equals(SESSION)) return v;
                    String n = v.getClass().getName();
                    if (n.startsWith("X.") || n.startsWith("com.instagram.")) next.add(v);
                } catch (Throwable ignored) {}
            }
        if (depth > 1) for (Object n : next) { Object s = findSession(n, depth - 1); if (s != null) return s; }
        return null;
    }

    private static void openSaved(Object frag, View v) {
        try {
            Object session = findSession(frag, 2);
            Activity act = null;
            for (android.content.Context k = v.getContext(); k != null && act == null;
                 k = k instanceof android.content.ContextWrapper w ? w.getBaseContext() : null)
                if (k instanceof Activity a) act = a;
            if (session == null || act == null) {
                ModuleLog.error(TAG + "Could not open Saved: your account or the screen was not found");
                ModuleLog.detail(TAG + "session=" + session + " activity=" + act);
                return;
            }
            Object[] args = new Object[factory.getParameterCount()];
            args[0] = session;
            java.util.Arrays.fill(args, 2, args.length, false);
            Object saved = factory.invoke(null, args);
            if (navMake != null) {
                Object nav = navMake.invoke(null, act, session);
                navPush.invoke(nav, null, saved);
                navCommit.invoke(nav);
            } else if (navCtor != null && navPush != null && navCommit != null
                    && navCtor.getParameterTypes().length == 2 && navCtor.getDeclaringClass() == navPush.getDeclaringClass()) {
                Object nav = navCtor.newInstance(act, session);
                navPush.invoke(nav, null, saved);
                navCommit.invoke(nav);
            } else {
                navigator.invoke(null, saved, act, session);
            }
        } catch (Throwable t) {
            ModuleLog.error(TAG + "Could not open Saved: " + t.getClass().getSimpleName() + ": " + t.getMessage());
        }
    }
}
