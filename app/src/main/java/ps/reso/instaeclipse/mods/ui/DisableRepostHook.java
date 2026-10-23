package ps.reso.instaeclipse.mods.ui;

import android.annotation.SuppressLint;
import android.content.Context;
import android.content.res.Resources;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.luckypray.dexkit.DexKitBridge;
import org.luckypray.dexkit.query.FindMethod;
import org.luckypray.dexkit.query.matchers.MethodMatcher;
import org.luckypray.dexkit.result.MethodData;

import ps.reso.instaeclipse.xposedcompat.XC_MethodHook;
import ps.reso.instaeclipse.xposedcompat.XposedBridge;
import ps.reso.instaeclipse.utils.core.DexKitCache;
import ps.reso.instaeclipse.utils.feature.FeatureFlags;
import ps.reso.instaeclipse.utils.feature.FeatureStatusTracker;
import ps.reso.instaeclipse.utils.log.ModuleLog;
import ps.reso.instaeclipse.utils.core.DexKitQueryCache;

/**
 * Disable Repost (feed + reels/clips + profile grid).
 *
 * Repost is created optimistically/client-side, so dropping the network request does NOT stop
 * it — IG applies the repost locally regardless. We neutralise IG's OWN repost code paths.
 * There are two independent paths on 447.0.0.21.81, so this neutralises both and also removes
 * the buttons:
 *
 * 1) REELS + PROFILE-GRID — the shared RepostActionHandler. We swallow its void entry points,
 *    anchored on IG's stable {@code android_purge_26_q3_*} trace-scope markers (never obfuscated
 *    X.* names), so the tap does nothing:
 *      • RepostActionHandler.handleRepostButtonTapped(...)                         (tap entry)
 *      • OnBoardingExperiencesRepostActionHandlerProxy.handleRepostButtonTapped(...) (proxy)
 *      • RepostActionHandler.performRepostAction(...)                              (executor)
 *    All void → {@code setResult(null)} is a clean skip.
 *
 * 2) HOME FEED (timeline dense-UFI) — a SEPARATE path that does NOT route through
 *    RepostActionHandler (proven on-device: the handler hook fires yet the feed repost still
 *    commits). Empirical instrumentation showed the feed tap drives
 *    {@code RepostsRepository.postRepost}, whose coroutine {@code RepostsRepository$postRepost$2}
 *    performs BOTH the optimistic "Reposted"/Undo UI apply (OptimisticPostOperation) AND the
 *    network commit (OptimisticNetworkOperation — the {@code xdt_update_media_note_repost_text}
 *    mutation). We skip the repository's VOID launcher that builds+launches that coroutine, so
 *    nothing runs: no optimistic apply, no network. Being VOID, {@code setResult(null)} is a
 *    clean, sentinel-safe skip — unlike the coroutine body itself, whose {@code invokeSuspend}
 *    must return the exact Kotlin COROUTINE_SUSPENDED / Unit sentinel or the machinery crashes.
 *
 *    The launcher is resolved WITHOUT obfuscated names: load the stably-named coroutine class
 *    {@code com.instagram.reposts.data.RepostsRepository$postRepost$2}, take its owning
 *    RepostsRepository class (enclosing class, else its RepostsRepository-typed constructor
 *    param), and hook that class's unique {@code void(<params>, kotlin.jvm.functions.Function1)}
 *    launcher. likeRepost launches from other classes, so this can't touch it; removeRepost is a
 *    different method, so undo still works.
 *
 * 3) THE BUTTONS — the feed and reels repost buttons (and the reels repost count) are removed
 *    from the UFI, see {@link #installButtonRemoval}.
 *
 * Everything is gated behind {@link FeatureFlags#disableRepost} and wrapped in try/catch: a miss
 * logs and no-ops, it never crashes IG.
 */
public class DisableRepostHook {

    // Reels + profile-grid repost: shared RepostActionHandler tap/exec entry points (all void).
    private static final String[] NEUTRALIZE_ANCHORS = {
            "android_purge_26_q3_RepostActionHandler_handleRepostButtonTapped",
            "android_purge_26_q3_OnBoardingExperiencesRepostActionHandlerProxy_handleRepostButtonTapped",
            "android_purge_26_q3_RepostActionHandler_performRepostAction",
    };

    // Stably-named Kotlin coroutine that RepostsRepository.postRepost launches.
    private static final String POST_REPOST_COROUTINE =
            "com.instagram.reposts.data.RepostsRepository$postRepost$2";

    // Reels UFI repost button renders (the animated wrapper and the icon it holds).
    private static final String[] REELS_BUTTON_ANCHORS = {
            "android_purge_26_q3_ClipsAnimatedRepostButtonComponent_render",
            "android_purge_26_q3_ClipsRepostButtonComponent_render",
    };
    private static final String BUTTON_CACHE = "DisableRepost_buttons_v2";

    public void install(Context context, DexKitBridge bridge, ClassLoader classLoader) {
        int total = installActionHandler(bridge, classLoader);
        total += installFeedCommit(classLoader);
        total += installButtonRemoval(context, bridge, classLoader);

        if (total > 0) FeatureStatusTracker.setHooked("DisableRepost");
        if (total > 0) ModuleLog.ok("(IE|Repost) ready");
        else ModuleLog.error("(IE|Repost) Will not work: the repost buttons and actions were not found in this Instagram version");
        ModuleLog.detail("(IE|Repost) hooked " + total + " method(s)");
    }

    // Reels + profile path — swallow the shared handler's void entry points entirely.
    private int installActionHandler(DexKitBridge bridge, ClassLoader classLoader) {
        XC_MethodHook skip = new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) { ps.reso.instaeclipse.utils.core.JitGuard.tick(param.method);
                if (FeatureFlags.disableRepost) {
                    param.setResult(null); // void method → skip original entirely
                    ModuleLog.line("(IE|Repost) Blocked a repost");
                }
            }
        };

        int total = 0;
        Set<String> hooked = new HashSet<>();
        for (String anchor : NEUTRALIZE_ANCHORS) {
            try {
                for (MethodData md : DexKitQueryCache.findMethod(bridge, FindMethod.create()
                        .matcher(MethodMatcher.create().usingStrings(anchor)))) {
                    String key = md.getDescriptor();
                    if (!hooked.add(key)) continue; // avoid double-hooking a shared method
                    try {
                        XposedBridge.hookMethod(md.getMethodInstance(classLoader), skip);
                        total++;
                    } catch (Throwable ignored) {}
                }
            } catch (Throwable t) {
                ModuleLog.warn("(IE|Repost) Reposting from Reels or profiles may not be blocked everywhere: search failed");
                ModuleLog.detail("(IE|Repost) anchor " + anchor + ": " + t.getClass().getSimpleName() + ": " + t.getMessage());
            }
        }
        return total;
    }

    // Home-feed path — skip the VOID RepostsRepository.postRepost launcher (sentinel-safe).
    private int installFeedCommit(ClassLoader classLoader) {
        XC_MethodHook skipPostRepost = new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) { ps.reso.instaeclipse.utils.core.JitGuard.tick(param.method);
                if (FeatureFlags.disableRepost) {
                    param.setResult(null); // void launcher → coroutine never built/launched
                    ModuleLog.line("(IE|Repost) Blocked a repost from the feed");
                }
            }
        };

        int total = 0;
        try {
            Class<?> lambda = classLoader.loadClass(POST_REPOST_COROUTINE);

            // Owning RepostsRepository class: prefer the enclosing class; if R8 stripped that
            // attribute, fall back to the constructor param whose class declares the launcher.
            Class<?> owner = null;
            Class<?> enclosing = lambda.getEnclosingClass();
            if (hasPostRepostLauncher(enclosing)) owner = enclosing;
            if (owner == null) {
                outer:
                for (Constructor<?> c : ps.reso.instaeclipse.utils.core.SafeReflect.constructors(lambda)) {
                    for (Class<?> pt : c.getParameterTypes()) {
                        if (hasPostRepostLauncher(pt)) { owner = pt; break outer; }
                    }
                }
            }

            if (owner == null) {
                ModuleLog.warn("(IE|Repost) Reposts from the feed are blocked only by hiding the button (repost code not found)");
                ModuleLog.detail("(IE|Repost) RepostsRepository owner not resolved");
                return 0;
            }

            for (Method m : ps.reso.instaeclipse.utils.core.SafeReflect.methods(owner)) {
                if (!isPostRepostLauncher(m)) continue;
                try {
                    m.setAccessible(true);
                    XposedBridge.hookMethod(m, skipPostRepost);
                    total++;
                } catch (Throwable ignored) {}
            }
            if (total == 0) ModuleLog.warn("(IE|Repost) Reposts from the feed are blocked only by hiding the button (repost code not found)");
            if (total == 0) ModuleLog.detail("(IE|Repost) postRepost launcher not found on " + owner.getName());
        } catch (Throwable t) {
            ModuleLog.warn("(IE|Repost) Reposts from the feed are blocked only by hiding the button: "
                    + t.getClass().getSimpleName() + ": " + t.getMessage());
        }
        ModuleLog.detail("(IE|Repost) feed postRepost launcher: " + total + " method(s)");
        return total;
    }

    // The repost buttons themselves. Feed and reels UFIs are Litho components: a component whose
    // render returns null takes no space, and the reels count helper already returns null when a
    // reel has no repost count. Results are replaced after the original runs, so Litho's hook state
    // stays consistent when the setting changes.
    private int installButtonRemoval(Context context, DexKitBridge bridge, ClassLoader classLoader) {
        List<Method> targets = DexKitCache.isCacheValid()
                ? DexKitCache.loadMethods(BUTTON_CACHE, classLoader) : null;
        if (targets == null || targets.isEmpty()) {
            targets = findButtonBuilders(context, bridge, classLoader);
            if (!targets.isEmpty()) DexKitCache.saveMethods(BUTTON_CACHE, targets);
        }
        targets.removeIf(m -> !ownRender(m));
        XC_MethodHook drawNothing = new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam param) { ps.reso.instaeclipse.utils.core.JitGuard.tick(param.method);
                if (FeatureFlags.disableRepost && !param.hasThrowable()) param.setResult(null);
            }
        };
        int total = 0;
        for (Method m : targets) {
            try {
                XposedBridge.hookMethod(m, drawNothing);
                total++;
            } catch (Throwable t) {
                ModuleLog.warn("(IE|Repost) A repost button could not be hidden");
                ModuleLog.detail("(IE|Repost) button hook " + m.getName() + ": " + t.getClass().getSimpleName() + ": " + t.getMessage());
            }
        }
        if (total == 0) ModuleLog.warn("(IE|Repost) Repost buttons cannot be hidden in this Instagram version; only the repost itself is blocked");
        ModuleLog.detail("(IE|Repost) repost buttons: " + total + " builder(s)");
        return total;
    }

    @SuppressLint("DiscouragedApi")
    private static List<Method> findButtonBuilders(Context context, DexKitBridge bridge, ClassLoader classLoader) {
        List<Method> found = new ArrayList<>();
        try {
            for (String anchor : REELS_BUTTON_ANCHORS) {
                for (MethodData md : bridge.findMethod(FindMethod.create()
                        .matcher(MethodMatcher.create().usingStrings(anchor)))) {
                    addBuilder(found, md, classLoader);
                }
            }
            Resources res = context.getResources();
            String pkg = context.getPackageName();
            // Feed: the component drawing the repost icon and its count (the legacy View binder
            // using the same id returns void and is skipped).
            int feedButton = res.getIdentifier("reposts_ufi_icon", "id", pkg);
            if (feedButton != 0) {
                for (MethodData md : bridge.findMethod(FindMethod.create()
                        .matcher(MethodMatcher.create().paramCount(1).usingNumbers(feedButton)))) {
                    addBuilder(found, md, classLoader);
                }
            }
            int reelCount = res.getIdentifier("repost_count", "id", pkg);
            if (reelCount != 0) {
                for (MethodData md : bridge.findMethod(FindMethod.create()
                        .matcher(MethodMatcher.create().usingStrings("ufi_count").usingNumbers(reelCount)))) {
                    addBuilder(found, md, classLoader);
                }
            }
        } catch (Throwable t) {
            ModuleLog.warn("(IE|Repost) Could not finish looking for the repost buttons; some may stay visible");
            ModuleLog.detail("(IE|Repost) button discovery: " + t.getClass().getSimpleName() + ": " + t.getMessage());
        }
        return found;
    }

    private static void addBuilder(List<Method> found, MethodData md, ClassLoader classLoader) {
        if (!md.isMethod()) return;
        try {
            Method m = md.getMethodInstance(classLoader);
            if (m.getReturnType() == void.class || m.getReturnType().isPrimitive() || found.contains(m)) return;
            if (!ownRender(m)) {
                ModuleLog.detail("(IE|Repost) shared render skipped: " + m.getDeclaringClass().getName() + "." + m.getName());
                return;
            }
            found.add(m);
        } catch (Throwable ignored) {}
    }

    /**
     * Only a component's own render may draw nothing. R8 can merge the renders of unrelated final
     * components into their abstract base (IG 451: the feed repost button shares one with the
     * carousel pager, post header and like animation), and nulling that blanks all of them.
     */
    private static boolean ownRender(Method m) {
        return ps.reso.instaeclipse.utils.core.HookTargetGuard.ownClass(m.getDeclaringClass());
    }

    /**
     * A RepostsRepository.postRepost launcher: {@code void} return, exactly two params, the second
     * being the Kotlin completion callback ({@code kotlin.jvm.functions.Function1} — kept
     * un-obfuscated in signatures). On RepostsRepository this uniquely identifies the postRepost
     * launcher (likeRepost is built from other classes; the 10-arg logging overloads don't match).
     */
    private static boolean isPostRepostLauncher(Method m) {
        if (m.getReturnType() != void.class) return false;
        Class<?>[] p = m.getParameterTypes();
        return p.length == 2 && "kotlin.jvm.functions.Function1".equals(p[1].getName());
    }

    private static boolean hasPostRepostLauncher(Class<?> c) {
        if (c == null || c.isPrimitive() || c.isInterface() || c.isArray()) return false;
        String n = c.getName();
        if (n.startsWith("java.") || n.startsWith("android") || n.startsWith("kotlin.")) return false;
        try {
            for (Method m : ps.reso.instaeclipse.utils.core.SafeReflect.methods(c)) {
                if (isPostRepostLauncher(m)) return true;
            }
        } catch (Throwable ignored) {}
        return false;
    }
}
