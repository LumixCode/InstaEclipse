package ps.reso.instaeclipse.mods.misc;

import ps.reso.instaeclipse.xposedcompat.AndroidAppHelper;
import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;

import org.luckypray.dexkit.query.FindMethod;
import org.luckypray.dexkit.query.matchers.MethodMatcher;

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
import ps.reso.instaeclipse.utils.core.DexKitQueryCache.Found;
import ps.reso.instaeclipse.utils.feature.FeatureStatusTracker;
import ps.reso.instaeclipse.utils.i18n.I18n;
import ps.reso.instaeclipse.utils.log.ModuleLog;
import ps.reso.instaeclipse.utils.toast.CustomToast;
import ps.reso.instaeclipse.utils.tracker.FollowIndicatorTracker;
import ps.reso.instaeclipse.utils.feature.FeatureFlags;
import ps.reso.instaeclipse.utils.users.UserUtils;

public final class FollowStatusHook {
    private static final String KEY = "FollowerToast";
    private static final String TAG = "(IE|FollowerToast) ";
    private static final String USER = "com.instagram.user.model.User";
    private static final String FOLLOWED_BY = "followed_by";
    private static final String FIELD_KEY = "FollowToast_followedBy_v1";
    private static final long PENDING_MS = 60_000;

    private static final Map<String, Long> pending = new ConcurrentHashMap<>();
    private static volatile Field followedBy;
    private static final ThreadLocal<List<Field>> unset = new ThreadLocal<>();

    public void install(ClassLoader loader) {
        try {
            Method parseField = resolve(loader, "FollowToast_parseField_v1", MethodMatcher.create()
                    .paramCount(3).returnType(void.class)
                    .usingStrings(FOLLOWED_BY, "outgoing_request", "incoming_request"));
            Class<?>[] params = parseField.getParameterTypes();
            if (!Modifier.isStatic(parseField.getModifiers()) || params[2] != String.class || params[1].isPrimitive())
                throw new IllegalStateException("Friendship field parser has an unexpected shape");
            Class<?> status = params[1];
            Method apply = resolve(loader, "FollowToast_apply_v1", MethodMatcher.create()
                    .paramTypes(status.getName(), USER, String.class.getName()).returnType(void.class));
            followedBy = cachedField(status);
            if (followedBy == null) learnField(parseField, status);
            XposedBridge.hookMethod(apply, new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    if (!wanted()) return;
                    try {
                        onStatusApplied(param.args[0], param.args[1]);
                    } catch (Throwable ignored) {}
                }
            });
            FeatureStatusTracker.setHooked(KEY);
            ModuleLog.ok(TAG + "Ready");
            ModuleLog.detail(TAG + "hooked " + apply.getDeclaringClass().getName() + "." + apply.getName()
                    + (followedBy != null ? ", followed_by = " + followedBy.getName() : ", learning followed_by"));
        } catch (Throwable t) {
            if (FeatureFlags.showFollowerToast) FeatureStatusTracker.setBroken(KEY, R.string.ig_dialog_misc_show_follower_toast);
            ModuleLog.error(TAG + "Could not turn on the follower toast: Instagram's follow status was not found", t);
        }
    }

    public static boolean wanted() {
        return FeatureFlags.showFollowerToast
                || (FeatureFlags.showFollowStatusLabel && ps.reso.instaeclipse.mods.ui.ProfileFollowsYouHook.active);
    }

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

    private static void onStatusApplied(Object status, Object user) throws IllegalAccessException {
        Field field = followedBy;
        if (field == null || status == null || user == null || pending.isEmpty()) return;
        String userId = String.valueOf(user);
        Long requestedAt = pending.remove(userId);
        if (requestedAt == null || SystemClock.uptimeMillis() - requestedAt > PENDING_MS) return;
        if (!(field.get(status) instanceof Boolean value)) return;
        ps.reso.instaeclipse.mods.ui.ProfileFollowsYouHook.onFollowResult(userId, value);
        showFollowToast(userId, UserUtils.callUsernameGetter(user), value);
    }

    private static Method resolve(ClassLoader loader, String key,
                                  MethodMatcher matcher) throws Exception {
        Method method = DexKitCache.isCacheValid() ? DexKitCache.loadMethod(key, loader) : null;
        if (method == null) {
            List<Found> found = DexKitQueryCache.methods(FindMethod.create().matcher(matcher));
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
                    if (filled != null) return;
                    filled = field;
                }
                if (filled == null) return;
                followedBy = filled;
                DexKitCache.saveString(FIELD_KEY, filled.getName());
                ModuleLog.detail(TAG + "followed_by = " + status.getName() + "." + filled.getName());
            }
        });
    }

    private static void showFollowToast(String userId, String username, boolean followsYou) {
        new Handler(Looper.getMainLooper()).post(() -> {
            try {
                String currentTarget = FollowIndicatorTracker.currentlyViewedUserId;
                if (currentTarget == null || !userId.equals(currentTarget)) return;

                Context ctx = AndroidAppHelper.currentApplication().getApplicationContext();
                String text = FollowToastText.text(username, followsYou,
                        I18n.t(ctx, R.string.ig_toast_user_follows_you), I18n.t(ctx, R.string.ig_toast_user_not_follows_you));

                if (FeatureFlags.showFollowerToast && text != null) CustomToast.showCustomToast(ctx, text);

                FollowIndicatorTracker.currentlyViewedUserId = null;
            } catch (Throwable ignored) {}
        });
    }
}
