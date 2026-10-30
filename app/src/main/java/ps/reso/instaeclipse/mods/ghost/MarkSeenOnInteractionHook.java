package ps.reso.instaeclipse.mods.ghost;

import android.os.Handler;
import android.os.Looper;

import org.luckypray.dexkit.query.FindMethod;
import org.luckypray.dexkit.query.matchers.MethodMatcher;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.List;
import java.util.Set;

import ps.reso.instaeclipse.Xposed.Module;
import ps.reso.instaeclipse.mods.ui.UIHookManager;
import ps.reso.instaeclipse.utils.core.DexKitCache;
import ps.reso.instaeclipse.utils.core.DexKitQueryCache;
import ps.reso.instaeclipse.utils.core.DexKitQueryCache.Found;
import ps.reso.instaeclipse.utils.core.SafeReflect;
import ps.reso.instaeclipse.utils.feature.FeatureFlags;
import ps.reso.instaeclipse.utils.feature.FeatureStatusTracker;
import ps.reso.instaeclipse.utils.ghost.CurrentThreadTracker;
import ps.reso.instaeclipse.utils.ghost.MarkSeen;
import ps.reso.instaeclipse.utils.ghost.UnsentIdentity;
import ps.reso.instaeclipse.utils.log.ModuleLog;
import ps.reso.instaeclipse.xposedcompat.XC_MethodHook;
import ps.reso.instaeclipse.xposedcompat.XposedBridge;
import ps.reso.instaeclipse.xposedcompat.XposedHelpers;

public class MarkSeenOnInteractionHook {

    private static final String TAG = "(IE|AutoSeen) ";
    private static final String STATUS = "MarkSeenOnInteraction";
    private static final String CACHE_KEY = "MarkSeenOnInteraction_dispatch";
    private static final String THREAD_KEY = "com.instagram.model.direct.DirectThreadKey";
    private static final String REACTION = "send_reaction";
    private static final Set<String> INTERACTIONS = Set.of(REACTION,
            "send_text_message", "send_link_message", "send_like_message",
            "config_animated_media_message", "send_store_sticker", "send_avatar_sticker", "send_ai_sticker",
            "send_cutout_sticker", "send_direct_music_sticker", "configure_riff_sticker_message",
            "configure_media_message", "configure_visual_message", "configure_voice_message",
            "send_clips_share_message", "send_media_share_message", "send_friendly_feed_message");

    public void install() {
        try {
            Method dispatch = dispatcher();
            if (dispatch == null) {
                ModuleLog.error(TAG + "Could not turn on sending Seen when you reply: not supported by this Instagram version (no 'MutationManager.dispatch' method)");
                if (FeatureFlags.markSeenOnInteraction) FeatureStatusTracker.setBroken(STATUS);
                return;
            }
            XposedBridge.hookMethod(dispatch, new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    if (!FeatureFlags.markSeenOnInteraction || !FeatureFlags.isGhostSeen) return;
                    if (Boolean.TRUE.equals(param.getResult())) onAccepted(param.args[0]);
                }
            });
            FeatureStatusTracker.setHooked(STATUS);
            ModuleLog.ok(TAG + "ready");
            ModuleLog.detail(TAG + "hooked: " + dispatch.getDeclaringClass().getName() + "." + dispatch.getName());
        } catch (Throwable t) {
            ModuleLog.error(TAG + "Could not turn on sending Seen when you reply", t);
        }
    }

    private static Method dispatcher() throws NoSuchMethodException {
        if (DexKitCache.isCacheValid()) {
            Method cached = DexKitCache.loadMethod(CACHE_KEY, Module.hostClassLoader);
            if (cached != null) return cached;
        }
        for (Found found : DexKitQueryCache.methods(FindMethod.create().matcher(MethodMatcher.create()
                .usingStrings("MutationManager.dispatch").returnType("boolean").paramCount(1)))) {
            if (!found.isMethod()) continue;
            Method method = found.getMethodInstance(Module.hostClassLoader);
            DexKitCache.saveMethod(CACHE_KEY, method);
            return method;
        }
        return null;
    }

    private static void onAccepted(Object mutation) {
        try {
            Object type = XposedHelpers.callMethod(mutation, "getTypeName");
            if (!(type instanceof String) || !INTERACTIONS.contains(type)) return;
            String thread = threadOf(mutation);
            if (thread == null || !isOpen(thread)) {
                ModuleLog.detail(TAG + "Skipped " + type + ": chat " + thread + " is not the open chat");
                return;
            }
            if (REACTION.equals(type) && removesReaction(mutation)) {
                ModuleLog.detail(TAG + "Skipped " + type + ": it removes a reaction");
                return;
            }
            ModuleLog.detail(TAG + type + " in the open chat " + thread + ": marking it as seen");
            new Handler(Looper.getMainLooper()).post(MarkSeen::markOpenThreadSeen);
        } catch (Throwable t) {
            ModuleLog.detail(TAG + "Could not check what was sent: " + t);
        }
    }

    private static boolean isOpen(String thread) {
        return thread.equals(CurrentThreadTracker.currentThreadId(UIHookManager.getCurrentActivity()))
                || thread.equals(CurrentThreadTracker.currentThreadId());
    }

    private static String threadOf(Object mutation) throws IllegalAccessException {
        for (Class<?> c = mutation.getClass(); c != null && c != Object.class; c = c.getSuperclass()) {
            for (Field field : SafeReflect.fields(c)) {
                if (Modifier.isStatic(field.getModifiers()) || field.getType().isPrimitive()) continue;
                field.setAccessible(true);
                Object value = field.get(mutation);
                if (value instanceof List<?> list && list.size() == 1) value = list.get(0);
                if (value != null && THREAD_KEY.equals(value.getClass().getName())) {
                    return UnsentIdentity.threadId(value.toString());
                }
            }
        }
        return null;
    }

    private static boolean removesReaction(Object mutation) throws IllegalAccessException {
        for (Field field : SafeReflect.fields(mutation.getClass())) {
            if (Modifier.isStatic(field.getModifiers()) || !field.getType().getName().startsWith("X.")) continue;
            field.setAccessible(true);
            Object value = field.get(mutation);
            if (value == null) continue;
            for (Field inner : SafeReflect.fields(value.getClass())) {
                if (Modifier.isStatic(inner.getModifiers()) || inner.getType() != String.class) continue;
                inner.setAccessible(true);
                if ("deleted".equals(inner.get(value))) return true;
            }
        }
        return false;
    }
}
