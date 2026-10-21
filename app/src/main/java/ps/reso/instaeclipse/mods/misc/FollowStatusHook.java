package ps.reso.instaeclipse.mods.misc;

import ps.reso.instaeclipse.xposedcompat.AndroidAppHelper;
import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;

import org.luckypray.dexkit.DexKitBridge;
import org.luckypray.dexkit.query.FindMethod;
import org.luckypray.dexkit.query.matchers.MethodMatcher;
import org.luckypray.dexkit.result.MethodData;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import ps.reso.instaeclipse.xposedcompat.XC_MethodHook;
import ps.reso.instaeclipse.xposedcompat.XposedBridge;
import ps.reso.instaeclipse.R;
import ps.reso.instaeclipse.utils.core.DexKitCache;
import ps.reso.instaeclipse.utils.core.DexKitQueryCache;
import ps.reso.instaeclipse.utils.core.JitGuard;
import ps.reso.instaeclipse.utils.feature.FeatureStatusTracker;
import ps.reso.instaeclipse.utils.i18n.I18n;
import ps.reso.instaeclipse.utils.log.ModuleLog;
import ps.reso.instaeclipse.utils.toast.CustomToast;
import ps.reso.instaeclipse.utils.tracker.FollowIndicatorTracker;
import ps.reso.instaeclipse.utils.feature.FeatureFlags;

/**
 * Follower toast: "follows you" / "doesn't follow you" when a profile opens.
 *
 * Every hook is installed once at startup. IGNetworkInterceptor only reports the user id of each
 * /friendships/show/ request (handleRequest). IG parses the answer into its friendship-status model
 * and applies it to the User it belongs to through one method (IG 451: X.01MY.A0K(status, User,
 * String)); that method's hook reads followed_by and shows the toast for a requested id.
 *
 * The followed_by field is found by its JSON key: the per-field parser (IG 451: X.06M7.A00, the
 * static (parser, status, String) method using "followed_by") is hooked until the field the
 * "followed_by" key fills is known. The name is cached, so later launches skip that hook.
 *
 * Earlier versions hooked the request's callback classes at runtime. On IG 451 one of them is the
 * app-wide RequestCallbackDispatcher (X.03ta), and hooking it mid-flight corrupted the heap on
 * LSPatch 1.2 (blank reel comments, crashes). Never hook classes from inside a request hook.
 */
public final class FollowStatusHook {
    private static final String KEY = "FollowerToast";
    private static final String TAG = "(IE|FollowerToast) ";
    private static final String USER = "com.instagram.user.model.User";
    private static final String FOLLOWED_BY = "followed_by";
    private static final String FIELD_KEY = "FollowToast_followedBy_v1";
    /** How long a /friendships/show/ request waits for IG to apply its answer. */
    private static final long PENDING_MS = 60_000;

    /** user id → uptime of its /friendships/show/ request, until IG applies the answer */
    private static final Map<String, Long> pending = new ConcurrentHashMap<>();
    /** the status model's Boolean followed_by field; null until known */
    private static volatile Field followedBy;
    /** Boolean fields still null when the parser starts on "followed_by" (learning only) */
    private static final ThreadLocal<List<Field>> unset = new ThreadLocal<>();

    public void install(DexKitBridge bridge, ClassLoader loader) {
        try {
            Method parseField = resolve(bridge, loader, "FollowToast_parseField_v1", MethodMatcher.create()
                    .paramCount(3).returnType(void.class)
                    .usingStrings(FOLLOWED_BY, "outgoing_request", "incoming_request"));
            Class<?>[] params = parseField.getParameterTypes();
            if (!Modifier.isStatic(parseField.getModifiers()) || params[2] != String.class || params[1].isPrimitive())
                throw new IllegalStateException("Friendship field parser has an unexpected shape");
            Class<?> status = params[1];
            Method apply = resolve(bridge, loader, "FollowToast_apply_v1", MethodMatcher.create()
                    .paramTypes(status.getName(), USER, String.class.getName()).returnType(void.class));
            followedBy = cachedField(status);
            if (followedBy == null) learnField(parseField, status);
            XposedBridge.hookMethod(apply, new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    JitGuard.tick(param.method);
                    if (!wanted()) return;
                    try {
                        onStatusApplied(param.args[0], param.args[1]);
                    } catch (Throwable ignored) {}
                }
            });
            FeatureStatusTracker.setHooked(KEY);
            ModuleLog.line(TAG + "hooked " + apply.getDeclaringClass().getName() + "." + apply.getName()
                    + (followedBy != null ? ", followed_by = " + followedBy.getName() : ", learning followed_by"));
        } catch (Throwable t) {
            if (FeatureFlags.showFollowerToast) FeatureStatusTracker.setBroken(KEY, R.string.ig_dialog_misc_show_follower_toast);
            ModuleLog.line(TAG + "unable to resolve IG's friendship status", t);
        }
    }

    /**
     * The answer is wanted: for the follower toast, or for the profile label's fallback
     * (ProfileFollowsYouHook), which has no other feed when the User model lacks followed_by.
     */
    public static boolean wanted() {
        return FeatureFlags.showFollowerToast
                || (FeatureFlags.showFollowStatusLabel && ps.reso.instaeclipse.mods.ui.ProfileFollowsYouHook.active);
    }

    /**
     * Called from IGNetworkInterceptor for every request while {@link #wanted}. Only records which
     * profile's /friendships/show/ was requested: no hooks, no callbacks.
     */
    public static void handleRequest(URI uri) {
        String path = uri.getPath();
        if (path == null || !path.startsWith("/api/v1/friendships/show/")) return;

        String[] parts = path.split("/");
        if (parts.length < 6 || parts[5].isEmpty()) return;

        String userId = parts[5];
        FollowIndicatorTracker.currentlyViewedUserId = userId;
        long now = SystemClock.uptimeMillis();
        pending.values().removeIf(at -> now - at > PENDING_MS);
        pending.put(userId, now);
    }

    /** IG applied a friendship status to a User: toast it when it answers a pending request. */
    private static void onStatusApplied(Object status, Object user) throws IllegalAccessException {
        Field field = followedBy;
        if (field == null || status == null || user == null || pending.isEmpty()) return;
        // User.toString() returns the id that IG put in the request path (446, 451).
        String userId = String.valueOf(user);
        Long requestedAt = pending.remove(userId);
        if (requestedAt == null || SystemClock.uptimeMillis() - requestedAt > PENDING_MS) return;
        if (!(field.get(status) instanceof Boolean value)) return;
        ps.reso.instaeclipse.mods.ui.ProfileFollowsYouHook.onFollowResult(userId, value);
        showFollowToast(userId, value);
    }

    private static Method resolve(DexKitBridge bridge, ClassLoader loader, String key,
                                  MethodMatcher matcher) throws Exception {
        Method method = DexKitCache.isCacheValid() ? DexKitCache.loadMethod(key, loader) : null;
        if (method == null) {
            List<MethodData> found = DexKitQueryCache.findMethod(bridge, FindMethod.create().matcher(matcher));
            if (found.size() != 1) throw new IllegalStateException(key + " matched " + found.size() + " methods");
            method = found.get(0).getMethodInstance(loader);
            DexKitCache.saveMethod(key, method);
        }
        method.setAccessible(true);
        return method;
    }

    private static Field cachedField(Class<?> status) {
        String name = DexKitCache.isCacheValid() ? DexKitCache.loadString(FIELD_KEY) : null;
        if (name == null) return null;
        try {
            Field field = status.getDeclaredField(name);
            if (field.getType() != Boolean.class || Modifier.isStatic(field.getModifiers())) return null;
            field.setAccessible(true);
            return field;
        } catch (NoSuchFieldException e) {
            return null;
        }
    }

    /**
     * Hooks the per-field parser (at startup, like every hook here) until it has seen which Boolean
     * field the "followed_by" key fills. Only runs while the answer is {@link #wanted}.
     */
    private static void learnField(Method parseField, Class<?> status) {
        List<Field> candidates = new ArrayList<>();
        for (Field field : status.getDeclaredFields()) {
            if (field.getType() != Boolean.class || Modifier.isStatic(field.getModifiers())) continue;
            field.setAccessible(true);
            candidates.add(field);
        }
        if (candidates.isEmpty()) throw new IllegalStateException("Friendship status has no Boolean fields");
        XposedBridge.hookMethod(parseField, new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) {
                JitGuard.tick(param.method);
                if (followedBy != null || !wanted()
                        || !FOLLOWED_BY.equals(param.args[2]) || !status.isInstance(param.args[1])) return;
                List<Field> open = new ArrayList<>();
                for (Field field : candidates) {
                    try {
                        if (field.get(param.args[1]) == null) open.add(field);
                    } catch (Throwable ignored) {}
                }
                unset.set(open);
            }

            @Override
            protected void afterHookedMethod(MethodHookParam param) {
                List<Field> open = unset.get();
                if (open == null) return;
                unset.remove();
                Field filled = null;
                for (Field field : open) {
                    try {
                        if (field.get(param.args[1]) == null) continue;
                    } catch (Throwable t) {
                        continue;
                    }
                    if (filled != null) return; // two fields filled: not a reliable answer
                    filled = field;
                }
                if (filled == null) return; // JSON null: wait for the next answer
                followedBy = filled;
                DexKitCache.saveString(FIELD_KEY, filled.getName());
                ModuleLog.line(TAG + "followed_by = " + status.getName() + "." + filled.getName());
            }
        });
    }

    /** Shows the follow-status toast on the main thread if userId is still pending. */
    private static void showFollowToast(String userId, boolean followsYou) {
        new Handler(Looper.getMainLooper()).post(() -> {
            try {
                String currentTarget = FollowIndicatorTracker.currentlyViewedUserId;
                if (currentTarget == null || !userId.equals(currentTarget)) return;

                Context ctx = AndroidAppHelper.currentApplication().getApplicationContext();
                String statusStr = followsYou
                        ? I18n.t(ctx, R.string.ig_toast_follows_you)
                        : I18n.t(ctx, R.string.ig_toast_not_follows_you);

                if (FeatureFlags.showFollowerToast)
                    CustomToast.showCustomToast(ctx, "(" + userId + ") " + statusStr);

                // Clear tracker so we don't process duplicate callback triggers
                FollowIndicatorTracker.currentlyViewedUserId = null;
            } catch (Throwable ignored) {}
        });
    }
}
