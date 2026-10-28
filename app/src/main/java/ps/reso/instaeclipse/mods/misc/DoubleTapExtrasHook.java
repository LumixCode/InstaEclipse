package ps.reso.instaeclipse.mods.misc;

import org.luckypray.dexkit.query.FindMethod;
import org.luckypray.dexkit.query.matchers.MethodMatcher;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import ps.reso.instaeclipse.xposedcompat.XC_MethodHook;
import ps.reso.instaeclipse.xposedcompat.XposedBridge;
import ps.reso.instaeclipse.utils.core.DexKitCache;
import ps.reso.instaeclipse.utils.core.HookTargetGuard;
import ps.reso.instaeclipse.utils.feature.FeatureFlags;
import ps.reso.instaeclipse.utils.log.ModuleLog;
import ps.reso.instaeclipse.utils.core.DexKitQueryCache;
import ps.reso.instaeclipse.utils.core.DexKitQueryCache.Found;

/**
 * Extends "Disable double-tap to like" ({@link DisableDoubleTapLikeHook}: feed and reels) to
 * comments and DMs.
 * <ul>
 *   <li>Comments: the comment rows' {@code onDoubleTap} listeners log "fb_comment_double_tap";
 *   they are made to consume the gesture without liking.</li>
 *   <li>DMs: every path that sends the double-tap reaction reads the configured double-tap emoji
 *   through one {@code String (UserSession, int)} getter (it falls back to "❤"). Its void callers
 *   are skipped when they run under an {@code onDoubleTap} frame, so reactions picked from the
 *   long-press menu still go through.</li>
 *   <li>DMs drawn with Compose (IG 452) take double-taps through Compose's tap detector, so no
 *   {@code onDoubleTap} frame exists. There the double-tap like has its own static dispatcher
 *   (X.0B4l.A00 on 452: sends the reaction with source "double_tap" and bumps
 *   "should_show_like_direct_message_count"), which is skipped outright.</li>
 * </ul>
 */
public class DoubleTapExtrasHook {

    private static final String TAG = "(InstaEclipse | DoubleTapLike): ";

    private static final XC_MethodHook CONSUME = new XC_MethodHook() {
        @Override
        protected void beforeHookedMethod(MethodHookParam param) { ps.reso.instaeclipse.utils.core.JitGuard.tick(param.method);
            if (FeatureFlags.disableDoubleTapLike) param.setResult(true);
        }
    };

    private static final XC_MethodHook SKIP = new XC_MethodHook() {
        @Override
        protected void beforeHookedMethod(MethodHookParam param) { ps.reso.instaeclipse.utils.core.JitGuard.tick(param.method);
            if (FeatureFlags.disableDoubleTapLike) param.setResult(null);
        }
    };

    private static final XC_MethodHook SKIP_UNDER_DOUBLE_TAP = new XC_MethodHook() {
        @Override
        protected void beforeHookedMethod(MethodHookParam param) { ps.reso.instaeclipse.utils.core.JitGuard.tick(param.method);
            if (!FeatureFlags.disableDoubleTapLike) return;
            for (StackTraceElement e : Thread.currentThread().getStackTrace()) {
                if ("onDoubleTap".equals(e.getMethodName()) || "onDoubleTapEvent".equals(e.getMethodName())) {
                    param.setResult(null);
                    return;
                }
            }
        }
    };

    public void install(ClassLoader cl) {
        List<Method> comments = cached("Extras_DoubleTapComments_v2", cl, () -> {
            Set<Method> out = new LinkedHashSet<>();
            for (Found md : DexKitQueryCache.methods(FindMethod.create().matcher(MethodMatcher.create()
                    .name("onDoubleTap").returnType("boolean").usingStrings("fb_comment_double_tap")))) {
                out.add(md.getMethodInstance(cl));
            }
            for (Found md : DexKitQueryCache.methods(FindMethod.create().matcher(MethodMatcher.create()
                    .name("onDoubleTap").returnType("boolean").usingEqStrings("like_comment")))) {
                out.add(md.getMethodInstance(cl));
            }
            return new ArrayList<>(out);
        });
        for (Method m : comments) XposedBridge.hookMethod(m, CONSUME);

        List<Method> direct = cached("Extras_DoubleTapDirect_v2", cl, () -> {
            List<Method> out = new ArrayList<>();
            for (Found getter : DexKitQueryCache.methods(FindMethod.create().matcher(MethodMatcher.create()
                    .returnType("java.lang.String").paramTypes("com.instagram.common.session.UserSession", "int")
                    .usingStrings("❤")))) {
                for (Found caller : DoubleTapCache.senders(DexKitQueryCache.callers(getter),
                        c -> c.isMethod() && "void".equals(c.getReturnTypeName()), c -> mergedLambda(c, cl))) {
                    out.add(caller.getMethodInstance(cl));
                }
            }
            return out;
        });
        for (Method m : direct) XposedBridge.hookMethod(m, SKIP_UNDER_DOUBLE_TAP);

        List<Method> composeDirect = cached("Extras_DoubleTapDirectCompose", cl, () -> {
            List<Method> out = new ArrayList<>();
            for (Found md : DexKitQueryCache.methods(FindMethod.create().matcher(MethodMatcher.create()
                    .returnType("void").usingStrings("double_tap", "should_show_like_direct_message_count")))) {
                if (md.isMethod()) out.add(md.getMethodInstance(cl));
            }
            return out;
        });
        for (Method m : composeDirect) XposedBridge.hookMethod(m, SKIP);

        ModuleLog.line(TAG + (comments.isEmpty() && direct.isEmpty() && composeDirect.isEmpty()
                ? "⚠️ Double-tap likes on comments and in chats cannot be blocked in this Instagram version"
                : "✅ Ready for comments and chats"));
        ModuleLog.detail(TAG + "comments " + comments.size() + ", DM senders " + direct.size()
                + ", DM double-tap " + composeDirect.size());
    }

    private interface ListFinder {
        List<Method> find() throws Throwable;
    }

    private static List<Method> cached(String key, ClassLoader cl, ListFinder finder) {
        if (DexKitCache.isCacheValid()) {
            List<Method> hit = DexKitCache.loadMethods(key, cl);
            if (hit != null && !hit.isEmpty()) return hit;
            if ("missing".equals(DexKitCache.loadString(key))) return new ArrayList<>();
        }
        List<Method> found = new ArrayList<>();
        boolean returned = false;
        try {
            found = finder.find();
            returned = true;
        } catch (Throwable t) {
            ModuleLog.detail(TAG + key + " lookup failed: " + t);
        }
        if (DoubleTapCache.shouldStoreMissing(returned, found.size())) DexKitCache.saveString(key, "missing");
        else if (!found.isEmpty()) DexKitCache.saveMethods(key, found);
        return found;
    }

    private static boolean mergedLambda(Found method, ClassLoader cl) {
        try {
            return HookTargetGuard.isMergedLambda(cl.loadClass(method.getClassName()), method.data().getOpCodes());
        } catch (Throwable t) {
            return false;
        }
    }
}
