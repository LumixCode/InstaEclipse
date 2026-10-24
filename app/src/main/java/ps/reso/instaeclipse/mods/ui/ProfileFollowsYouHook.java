package ps.reso.instaeclipse.mods.ui;

import android.view.View;

import org.luckypray.dexkit.DexKitBridge;
import org.luckypray.dexkit.query.FindMethod;
import org.luckypray.dexkit.query.enums.MatchType;
import org.luckypray.dexkit.query.matchers.MethodMatcher;
import org.luckypray.dexkit.result.MethodData;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.Collections;
import java.util.List;

import ps.reso.instaeclipse.xposedcompat.XC_MethodHook;
import ps.reso.instaeclipse.xposedcompat.XposedBridge;
import ps.reso.instaeclipse.mods.misc.ProfileFollowStatusHook;
import ps.reso.instaeclipse.utils.core.DexKitCache;
import ps.reso.instaeclipse.utils.feature.FeatureFlags;
import ps.reso.instaeclipse.utils.feature.FeatureStatusTracker;
import ps.reso.instaeclipse.utils.log.ModuleLog;
import ps.reso.instaeclipse.utils.core.DexKitQueryCache;

/**
 * "Follows you" label on profiles (#239), instead of only the follower toast.
 *
 * After UserDetailFragment's onUserUpdated (static method using "UserDetailFragment.onUserUpdated":
 * A0d on 452, A0h on 451) the viewed User is read from the fragment's UserDetailViewModel and its
 * followed_by getter (User ()Boolean using "followed_by"; null = unknown/own profile → no label).
 */
public class ProfileFollowsYouHook {

    /** True once installed; IGNetworkInterceptor feeds /friendships/show/ results only then. */
    public static volatile boolean active;

    private static final String TAG = "(InstaEclipse | FollowsYou): ";
    private static final String FRAGMENT = "com.instagram.profile.fragment.UserDetailFragment";
    private static final String VIEW_MODEL = "com.instagram.profile.fragment.UserDetailViewModel";
    private static final String USER = "com.instagram.user.model.User";
    private static final String CACHE_UPDATED = "FollowsYou_onUserUpdated_v2";
    private static Method followedByGetter;
    private static volatile java.lang.ref.WeakReference<Object> lastFragment;

    public void install(DexKitBridge bridge, ClassLoader cl) {
        try {
            Method updated = DexKitCache.isCacheValid() ? DexKitCache.loadMethod(CACHE_UPDATED, cl) : null;
            followedByGetter = DexKitCache.isCacheValid() ? DexKitCache.loadMethod("FollowsYou_followedBy", cl) : null;
            if (updated == null || followedByGetter == null) {
                List<MethodData> u = DexKitQueryCache.findMethod(bridge, FindMethod.create().matcher(MethodMatcher.create()
                        .declaredClass(FRAGMENT).modifiers(Modifier.STATIC, MatchType.Contains).paramCount(2)
                        .usingEqStrings(java.util.List.of("UserDetailFragment.onUserUpdated"))));
                List<MethodData> f = DexKitQueryCache.findMethod(bridge, FindMethod.create().matcher(MethodMatcher.create()
                        .declaredClass(USER).paramCount(0).returnType("java.lang.Boolean")
                        .usingEqStrings(java.util.List.of("followed_by"))));
                if (u.size() != 1 || f.size() != 1) {
                    ModuleLog.error(TAG + "The follow status label will not work: profile code not found in this Instagram version");
                    ModuleLog.detail(TAG + "onUserUpdated=" + u.size() + " followed_by=" + f.size());
                    return;
                }
                updated = u.get(0).getMethodInstance(cl);
                followedByGetter = f.get(0).getMethodInstance(cl);
                DexKitCache.saveMethod(CACHE_UPDATED, updated);
                DexKitCache.saveMethod("FollowsYou_followedBy", followedByGetter);
            }
            followedByGetter.setAccessible(true);
            XposedBridge.hookMethod(updated, new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) { ps.reso.instaeclipse.utils.core.JitGuard.tick(param.method);
                    if (!FeatureFlags.showFollowStatusLabel) return;
                    Object frag = param.thisObject;
                    if (frag == null) for (Object a : param.args) if (a != null && a.getClass().getName().equals(FRAGMENT)) frag = a;
                    if (frag == null) return;
                    lastFragment = new java.lang.ref.WeakReference<>(frag);
                    apply(frag, null);
                }
            });
            // Also on every resume of a profile screen (onUserUpdated doesn't fire on all profiles).
            Class<?> base = cl.loadClass("androidx.fragment.app.Fragment");
            ps.reso.instaeclipse.xposedcompat.XposedHelpers.findAndHookMethod(base, "onResume", new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) { ps.reso.instaeclipse.utils.core.JitGuard.tick(param.method);
                    if (!FeatureFlags.showFollowStatusLabel) return;
                    Object frag = param.thisObject;
                    for (Class<?> c = frag.getClass(); c != null && c != Object.class; c = c.getSuperclass()) {
                        if (c.getName().equals(FRAGMENT)) {
                            lastFragment = new java.lang.ref.WeakReference<>(frag);
                            View root;
                            try { root = (View) frag.getClass().getMethod("getView").invoke(frag); } catch (Throwable t) { return; }
                            if (root != null) root.postDelayed(() -> apply(frag, null), 700);
                            return;
                        }
                    }
                }
            });
            active = true;
            ModuleLog.ok(TAG + "ready");
            ModuleLog.detail(TAG + "hooked " + updated.getName() + " / followed_by " + followedByGetter.getName());
            FeatureStatusTracker.setHooked("FollowStatusLabel");
        } catch (Throwable t) {
            ModuleLog.error(TAG + "The follow status label will not work: setup failed", t);
        }
    }

    /**
     * Result of IG's /friendships/show/ call (parsed by FollowStatusHook while this label or the
     * follower toast is on): used when the User model doesn't carry followed_by yet (common for
     * accounts you don't follow).
     */
    /** userId -> followed_by from /friendships/show/, kept so a result that arrives before the
     *  profile screen registers is still applied. */
    private static final FollowsYouCache RESULTS = new FollowsYouCache();

    public static void onFollowResult(String userId, boolean followedBy) {
        if (!active || !FeatureFlags.showFollowStatusLabel || userId == null) return;
        RESULTS.put(userId, followedBy);
        java.lang.ref.WeakReference<Object> ref = lastFragment;
        Object frag = ref != null ? ref.get() : null;
        if (frag == null) return;
        new android.os.Handler(android.os.Looper.getMainLooper()).post(() -> {
            try {
                Object user = viewedUser(frag);
                if (user != null && userId.equals(String.valueOf(user))) apply(frag, followedBy);
            } catch (Throwable t) {
                ModuleLog.warn(TAG + "Could not show the follow status on this profile: "
                        + t.getClass().getSimpleName() + ": " + t.getMessage());
            }
        });
    }

    private static void apply(Object frag, Boolean known) {
        try {
            View root = (View) frag.getClass().getMethod("getView").invoke(frag);
            Object user = root != null ? viewedUser(frag) : null;
            if (user == null) return;
            String id = String.valueOf(user);
            Boolean followsMe = known != null ? known : followedBy(user);
            if (followsMe == null) followsMe = RESULTS.find(Collections.singleton(id));
            if (followsMe != null) ProfileFollowStatusHook.showFromNetwork(root, followsMe, id);
        } catch (Throwable t) {
            ModuleLog.warn(TAG + "Could not show the follow status on this profile: "
                    + t.getClass().getSimpleName() + ": " + t.getMessage());
        }
    }

    private static Object viewedUser(Object frag) throws IllegalAccessException {
        Object vm = fieldOfType(frag, VIEW_MODEL);
        return vm != null ? fieldOfType(vm, USER) : null;
    }

    private static Boolean followedBy(Object user) throws Exception {
        Object r = followedByGetter.invoke(user);
        return r instanceof Boolean b ? b : null;
    }

    private static Object fieldOfType(Object o, String type) throws IllegalAccessException {
        for (Class<?> c = o.getClass(); c != null && c != Object.class; c = c.getSuperclass()) {
            for (Field f : ps.reso.instaeclipse.utils.core.SafeReflect.fields(c)) {
                if (!f.getType().getName().equals(type)) continue;
                f.setAccessible(true);
                Object v = f.get(o);
                if (v != null) return v;
            }
        }
        return null;
    }
}
