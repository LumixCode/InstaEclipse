package ps.reso.instaeclipse.mods.ghost;

import org.luckypray.dexkit.query.FindMethod;
import org.luckypray.dexkit.query.matchers.MethodMatcher;
import org.luckypray.dexkit.result.MethodData;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import ps.reso.instaeclipse.utils.core.DexKitCache;
import ps.reso.instaeclipse.utils.core.DexKitQueryCache;
import ps.reso.instaeclipse.utils.core.DexKitQueryCache.Found;
import ps.reso.instaeclipse.utils.feature.FeatureStatusTracker;
import ps.reso.instaeclipse.utils.log.ModuleLog;
import ps.reso.instaeclipse.xposedcompat.XC_MethodHook;
import ps.reso.instaeclipse.xposedcompat.XposedBridge;

public final class AutoStorySeenHook {

    private static final String TAG = "(IE|AutoStorySeen) ";
    private static final String REPLY_KEY = "AutoStorySeen_reply_v1";
    private static final String COMMENT_KEY = "AutoStorySeen_comment_v1";
    private static final String REPLY_ANCHOR = "reel_send_message";
    private static final String COMMENT_ANCHOR = "media/story_comment/post/";
    private static final String MEDIA = "com.instagram.feed.media.Media";

    public void install(ClassLoader loader) {
        boolean replies = false, comments = false;
        try {
            List<Method> reply = replyTargets(loader);
            hookReply(reply.get(0), reply.get(1));
            replies = true;
            ModuleLog.detail(TAG + "story replies hooked: " + name(reply.get(0)) + ", story " + name(reply.get(1)));
        } catch (Throwable t) {
            ModuleLog.warn(TAG + "Replies and reactions will not mark a story as seen: " + t);
        }
        try {
            Method comment = commentTarget(loader);
            hookComment(comment);
            comments = true;
            ModuleLog.detail(TAG + "story comments hooked: " + name(comment));
        } catch (Throwable t) {
            ModuleLog.warn(TAG + "Story comments will not mark a story as seen: " + t);
        }
        if (replies || comments) {
            FeatureStatusTracker.setHooked("AutoStorySeen");
            ModuleLog.ok(TAG + "ready");
        } else if (ManualStorySeenHook.autoEnabled()) {
            FeatureStatusTracker.setBroken("AutoStorySeen");
        }
    }

    private static List<Method> replyTargets(ClassLoader loader) throws Throwable {
        List<Method> cached = DexKitCache.isCacheValid() ? DexKitCache.loadMethods(REPLY_KEY, loader) : null;
        if (cached != null && cached.size() == 2) return cached;
        Found log = only(DexKitQueryCache.methods(FindMethod.create().matcher(MethodMatcher.create()
                .usingEqStrings(REPLY_ANCHOR).returnType("void"))), "story reply log");
        if (log.getParamCount() == 0) throw new IllegalStateException("story reply log takes no story");
        String model = log.getParamTypeNames().get(0);
        Method story = null;
        for (MethodData call : DexKitQueryCache.invokes(log)) {
            if (!call.isMethod() || !ManualStorySeenHook.REEL_ITEM.equals(call.getReturnTypeName())
                    || !List.of(ManualStorySeenHook.SESSION).equals(call.getParamTypeNames())) continue;
            Method m = call.getMethodInstance(loader);
            if (!m.getDeclaringClass().getName().equals(model)) continue;
            if (story != null && !story.equals(m)) throw new IllegalStateException("two current stories: " + name(story) + ", " + name(m));
            story = m;
        }
        if (story == null) throw new IllegalStateException("current story not found in " + log);
        List<Method> targets = new ArrayList<>();
        targets.add(log.getMethodInstance(loader));
        targets.add(story);
        DexKitCache.saveMethods(REPLY_KEY, targets);
        return targets;
    }

    private static Method commentTarget(ClassLoader loader) throws Throwable {
        List<Method> cached = DexKitCache.isCacheValid() ? DexKitCache.loadMethods(COMMENT_KEY, loader) : null;
        if (cached != null && cached.size() == 1) return cached.get(0);
        Method post = only(DexKitQueryCache.methods(FindMethod.create().matcher(MethodMatcher.create()
                .usingEqStrings(COMMENT_ANCHOR))), "story comment post").getMethodInstance(loader);
        DexKitCache.saveMethods(COMMENT_KEY, Collections.singletonList(post));
        return post;
    }

    private static Found only(List<Found> found, String what) {
        List<Found> methods = new ArrayList<>();
        for (Found md : found) if (md.isMethod()) methods.add(md);
        if (methods.size() != 1) throw new IllegalStateException(what + ": " + methods.size() + " candidates");
        return methods.get(0);
    }

    private static void hookReply(Method log, Method story) {
        story.setAccessible(true);
        XposedBridge.hookMethod(log, new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) {
                if (!ManualStorySeenHook.autoEnabled()) return;
                try {
                    Object session = ManualStorySeenHook.typedValue(param.thisObject, ManualStorySeenHook.SESSION, 1);
                    Object item = session == null ? null : story.invoke(param.args[0], session);
                    ManualStorySeenHook.interacted(ManualStorySeenHook.mediaId(item), "reply or reaction");
                } catch (Throwable t) {
                    ModuleLog.detail(TAG + "story reply: " + t);
                }
            }
        });
    }

    private static void hookComment(Method post) {
        final int mediaAt = mediaAt(post);
        XposedBridge.hookMethod(post, new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) {
                if (!ManualStorySeenHook.autoEnabled()) return;
                try {
                    ManualStorySeenHook.interacted(ManualStorySeenHook.mediaId(param.args[mediaAt]), "story comment");
                } catch (Throwable t) {
                    ModuleLog.detail(TAG + "story comment: " + t);
                }
            }
        });
    }

    private static int mediaAt(Method m) {
        int at = -1;
        Class<?>[] types = m.getParameterTypes();
        for (int i = 0; i < types.length; i++) {
            if (!types[i].getName().equals(MEDIA)) continue;
            if (at != -1) throw new IllegalStateException("two medias in " + name(m));
            at = i;
        }
        if (at == -1) throw new IllegalStateException("no media in " + name(m));
        return at;
    }

    private static String name(Method m) {
        return m.getDeclaringClass().getName() + "." + m.getName();
    }
}
