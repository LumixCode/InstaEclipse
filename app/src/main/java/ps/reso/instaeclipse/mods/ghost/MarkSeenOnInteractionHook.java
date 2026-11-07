package ps.reso.instaeclipse.mods.ghost;

import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;

import org.luckypray.dexkit.query.FindMethod;
import org.luckypray.dexkit.query.matchers.MethodMatcher;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.WeakHashMap;

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
    private static final String REEL_CONFIG_KEY = "ClipsViewerLauncher.KEY_CONFIG";
    private static final String REPLY_BAR = "com.instagram.clips.model.ClipsReplyBarData";
    private static final Set<Object> REELS = Collections.synchronizedSet(Collections.newSetFromMap(new WeakHashMap<>()));
    private static final Set<String> SHARES = Set.of("send_clips_share_message", "send_media_share_message");
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
            trackReels();
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
            if (!(XposedHelpers.callMethod(mutation, "getTypeName") instanceof String type)
                    || !INTERACTIONS.contains(type)) return;
            String thread = threadOf(mutation);
            if (thread == null) {
                ModuleLog.detail(TAG + "Skipped " + type + ": it has no chat");
                return;
            }
            if (REACTION.equals(type) && removesReaction(mutation)) {
                ModuleLog.detail(TAG + "Skipped " + type + ": it removes a reaction");
                return;
            }
            Handler main = new Handler(Looper.getMainLooper());
            if (isOpen(thread)) {
                ModuleLog.detail(TAG + type + " in the open chat " + thread + ": marking it as seen");
                main.post(MarkSeen::markOpenThreadSeen);
                return;
            }
            Set<String> targets = messageIds(mutation, thread);
            main.post(() -> fromReel(type, thread, targets));
        } catch (Throwable t) {
            ModuleLog.detail(TAG + "Could not check what was sent: " + t);
        }
    }

    private static void trackReels() {
        try {
            Class<?> fragment = Class.forName("androidx.fragment.app.Fragment", false, Module.hostClassLoader);
            XposedHelpers.findAndHookMethod(fragment, "onResume", new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    if (!FeatureFlags.markSeenOnInteraction || !FeatureFlags.isGhostSeen) return;
                    try {
                        if (HideChatsHook.invokeNoArg(param.thisObject, "getArguments") instanceof Bundle args
                                && args.containsKey(REEL_CONFIG_KEY)) {
                            REELS.add(param.thisObject);
                        }
                    } catch (RuntimeException ignored) {
                    }
                }
            });
            XposedHelpers.findAndHookMethod(fragment, "onPause", new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    if (!REELS.isEmpty()) REELS.remove(param.thisObject);
                }
            });
        } catch (Throwable t) {
            ModuleLog.warn(TAG + "Reacting or replying from a reel opened in a chat will not send Seen: " + t);
        }
    }

    private static void fromReel(String type, String thread, Set<String> targets) {
        try {
            List<Object> reels;
            synchronized (REELS) {
                reels = new ArrayList<>(REELS);
            }
            StringBuilder looked = new StringBuilder();
            for (Object reel : reels) {
                if (!Boolean.TRUE.equals(HideChatsHook.invokeNoArg(reel, "isResumed"))) continue;
                Set<String> ids = replyBarIds(reel);
                looked.append(", reply bar for ").append(numeric(ids));
                if (!ids.contains(thread)) continue;
                String why = notAReply(type, targets, ids);
                if (why != null) {
                    ModuleLog.detail(TAG + "Skipped " + type + " from a reel opened in chat " + thread + ": " + why);
                    return;
                }
                ModuleLog.detail(TAG + type + " from a reel opened in chat " + thread + " (answers "
                        + (targets.isEmpty() ? "no message" : String.join("/", targets)) + "): marking it as seen");
                MarkSeen.markThreadSeen(thread, HideChatsHook.invokeNoArg(reel, "getSession"));
                return;
            }
            ModuleLog.detail(TAG + "Skipped " + type + ": chat " + thread + " is not the open chat (reels open: "
                    + reels.size() + looked + ")");
        } catch (Throwable t) {
            ModuleLog.detail(TAG + "Could not check the open reel: " + t);
        }
    }

    private static String notAReply(String type, Set<String> targets, Set<String> replyBar) {
        if (SHARES.contains(type)) return "it sends them a reel instead of answering theirs";
        if (targets.isEmpty() || !Collections.disjoint(targets, replyBar)) return null;
        return "it answers message " + String.join("/", targets) + ", not the one this reel came from";
    }

    private static Set<String> messageIds(Object mutation, String thread) throws IllegalAccessException {
        Set<String> found = new LinkedHashSet<>();
        strings(mutation, found, 0, Collections.newSetFromMap(new IdentityHashMap<>()));
        Set<String> ids = new LinkedHashSet<>();
        for (String s : found) {
            if (!s.equals(thread) && isMessageId(s)) ids.add(s);
        }
        return ids;
    }

    private static boolean isMessageId(String s) {
        if (s.startsWith("mid.")) return true;
        return s.length() >= 30 && s.chars().allMatch(Character::isDigit);
    }

    private static Set<String> replyBarIds(Object reel) throws IllegalAccessException {
        Set<String> ids = new LinkedHashSet<>();
        if (!(HideChatsHook.invokeNoArg(reel, "getArguments") instanceof Bundle args)) return ids;
        args.setClassLoader(reel.getClass().getClassLoader());
        Object config = args.get(REEL_CONFIG_KEY);
        Object bar = config == null ? null : fieldOfType(config, REPLY_BAR);
        if (bar != null) strings(bar, ids, 0, Collections.newSetFromMap(new IdentityHashMap<>()));
        return ids;
    }

    private static Object fieldOfType(Object owner, String type) throws IllegalAccessException {
        for (Class<?> c = owner.getClass(); c != null && c != Object.class; c = c.getSuperclass()) {
            for (Field field : SafeReflect.fields(c)) {
                if (Modifier.isStatic(field.getModifiers()) || !type.equals(field.getType().getName())) continue;
                field.setAccessible(true);
                Object value = field.get(owner);
                if (value != null) return value;
            }
        }
        return null;
    }

    private static void strings(Object value, Set<String> out, int depth, Set<Object> seen) throws IllegalAccessException {
        if (value instanceof String s) {
            out.add(s);
            return;
        }
        if (value == null || depth > 3 || !seen.add(value)) return;
        if (value instanceof List<?> list) {
            for (Object item : list) strings(item, out, depth + 1, seen);
            return;
        }
        String name = value.getClass().getName();
        if (THREAD_KEY.equals(name)) {
            String id = UnsentIdentity.threadId(value.toString());
            if (id != null) out.add(id);
        }
        if (!name.startsWith("X.") && !name.startsWith("com.instagram.")) return;
        for (Class<?> c = value.getClass(); c != null && c != Object.class; c = c.getSuperclass()) {
            for (Field field : SafeReflect.fields(c)) {
                if (Modifier.isStatic(field.getModifiers()) || field.getType().isPrimitive()) continue;
                field.setAccessible(true);
                strings(field.get(value), out, depth + 1, seen);
            }
        }
    }

    private static String numeric(Set<String> ids) {
        StringBuilder out = new StringBuilder();
        for (String id : ids) {
            if (id.length() < 15 || !id.chars().allMatch(Character::isDigit)) continue;
            out.append(out.length() == 0 ? "" : "/").append(id);
        }
        return out.length() == 0 ? "no ids" : out.toString();
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
