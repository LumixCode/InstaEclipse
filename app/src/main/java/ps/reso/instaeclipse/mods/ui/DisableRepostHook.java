package ps.reso.instaeclipse.mods.ui;

import android.annotation.SuppressLint;
import android.content.Context;
import android.content.res.Resources;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.luckypray.dexkit.query.FindClass;
import org.luckypray.dexkit.query.FindMethod;
import org.luckypray.dexkit.query.matchers.ClassMatcher;
import org.luckypray.dexkit.query.matchers.MethodMatcher;

import ps.reso.instaeclipse.xposedcompat.XC_MethodHook;
import ps.reso.instaeclipse.xposedcompat.XposedBridge;
import ps.reso.instaeclipse.utils.core.DexKitCache;
import ps.reso.instaeclipse.utils.feature.FeatureFlags;
import ps.reso.instaeclipse.utils.feature.FeatureStatusTracker;
import ps.reso.instaeclipse.utils.log.ModuleLog;
import ps.reso.instaeclipse.utils.core.DexKitQueryCache;
import ps.reso.instaeclipse.utils.core.DexKitQueryCache.Found;
import ps.reso.instaeclipse.utils.core.DexKitQueryCache.FoundClass;

public class DisableRepostHook {

    private static final String[] NEUTRALIZE_ANCHORS = {
            "android_purge_26_q3_RepostActionHandler_handleRepostButtonTapped",
            "android_purge_26_q3_OnBoardingExperiencesRepostActionHandlerProxy_handleRepostButtonTapped",
            "android_purge_26_q3_RepostActionHandler_performRepostAction",
    };

    private static final String POST_REPOST_COROUTINE =
            "com.instagram.reposts.data.RepostsRepository$postRepost$2";

    private static final String[] REELS_BUTTON_ANCHORS = {
            "android_purge_26_q3_ClipsAnimatedRepostButtonComponent_render",
            "android_purge_26_q3_ClipsRepostButtonComponent_render",
    };
    private static final String BUTTON_CACHE = "DisableRepost_buttons_v2";
    private static final String UFI_STATE_CACHE = "DisableRepost_ufiState_v1";
    private static final String REPOST_BUTTON_LABEL = ", isRepostButtonEnabled=";

    public void install(Context context, ClassLoader classLoader) {
        int total = installActionHandler(classLoader);
        total += installFeedCommit(classLoader);
        total += installButtonRemoval(context, classLoader);
        total += installUfiState(classLoader);

        if (total > 0) FeatureStatusTracker.setHooked("DisableRepost");
        if (total > 0) ModuleLog.ok("(IE|Repost) ready");
        else ModuleLog.error("(IE|Repost) Will not work: the repost buttons and actions were not found in this Instagram version");
        ModuleLog.detail("(IE|Repost) hooked " + total + " method(s)");
    }

    private int installActionHandler(ClassLoader classLoader) {
        XC_MethodHook skip = new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) {
                if (FeatureFlags.disableRepost) {
                    param.setResult(null);
                    ModuleLog.line("(IE|Repost) Blocked a repost");
                }
            }
        };

        int total = 0;
        Set<String> hooked = new HashSet<>();
        for (String anchor : NEUTRALIZE_ANCHORS) {
            try {
                for (Found md : DexKitQueryCache.methods(FindMethod.create()
                        .matcher(MethodMatcher.create().usingStrings(anchor)))) {
                    String key = md.getDescriptor();
                    if (!hooked.add(key)) continue;
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

    private int installFeedCommit(ClassLoader classLoader) {
        XC_MethodHook skipPostRepost = new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) {
                if (FeatureFlags.disableRepost) {
                    param.setResult(null);
                    ModuleLog.line("(IE|Repost) Blocked a repost from the feed");
                }
            }
        };

        int total = 0;
        try {
            Class<?> lambda = classLoader.loadClass(POST_REPOST_COROUTINE);

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

    private int installButtonRemoval(Context context, ClassLoader classLoader) {
        List<Method> targets = DexKitCache.isCacheValid()
                ? DexKitCache.loadMethods(BUTTON_CACHE, classLoader) : null;
        if (targets == null || targets.isEmpty()) {
            targets = findButtonBuilders(context, classLoader);
            if (!targets.isEmpty()) DexKitCache.saveMethods(BUTTON_CACHE, targets);
        }
        targets.removeIf(m -> !ownRender(m));
        XC_MethodHook drawNothing = new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam param) {
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

    private int installUfiState(ClassLoader classLoader) {
        try {
            Field field = DexKitCache.isCacheValid() ? DexKitCache.loadField(UFI_STATE_CACHE, classLoader) : null;
            if (field == null) {
                field = findUfiStateField(classLoader);
                if (field == null) return 0;
                DexKitCache.saveField(UFI_STATE_CACHE, field);
            }
            field.setAccessible(true);
            final Field repostButton = field;
            int total = XposedBridge.hookAllConstructors(field.getDeclaringClass(), new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    if (!FeatureFlags.disableRepost) return;
                    try {
                        repostButton.setBoolean(param.thisObject, false);
                    } catch (Throwable ignored) {}
                }
            }).size();
            ModuleLog.detail("(IE|Repost) feed repost button state: " + field.getDeclaringClass().getName() + "." + field.getName()
                    + ", " + total + " constructor(s)");
            return total;
        } catch (Throwable t) {
            ModuleLog.warn("(IE|Repost) The repost button may stay visible on home-feed posts; reposting is still blocked");
            ModuleLog.detail("(IE|Repost) feed UFI state: " + t.getClass().getSimpleName() + ": " + t.getMessage());
            return 0;
        }
    }

    private static Field findUfiStateField(ClassLoader classLoader) throws Exception {
        List<FoundClass> found = DexKitQueryCache.classes(FindClass.create().matcher(ClassMatcher.create()
                .usingStrings(REPOST_BUTTON_LABEL)));
        if (found.size() != 1) {
            ModuleLog.warn("(IE|Repost) The repost button may stay visible on home-feed posts; reposting is still blocked");
            ModuleLog.detail("(IE|Repost) feed UFI state class " + (found.isEmpty() ? "not found" : "ambiguous (" + found.size() + ")"));
            return null;
        }
        Class<?> state = found.get(0).getInstance(classLoader);
        Field field = UfiStateField.fieldFor(blank(state), REPOST_BUTTON_LABEL);
        if (field == null) {
            ModuleLog.warn("(IE|Repost) The repost button may stay visible on home-feed posts; reposting is still blocked");
            ModuleLog.detail("(IE|Repost) no single boolean of " + state.getName() + " drives the repost button");
        }
        return field;
    }

    private static Object blank(Class<?> cls) throws Exception {
        Class<?> unsafeClass = Class.forName("sun.misc.Unsafe");
        Field theUnsafe = unsafeClass.getDeclaredField("theUnsafe");
        theUnsafe.setAccessible(true);
        return unsafeClass.getMethod("allocateInstance", Class.class).invoke(theUnsafe.get(null), cls);
    }

    @SuppressLint("DiscouragedApi")
    private static List<Method> findButtonBuilders(Context context, ClassLoader classLoader) {
        List<Method> found = new ArrayList<>();
        try {
            for (String anchor : REELS_BUTTON_ANCHORS) {
                for (Found md : DexKitQueryCache.methods(FindMethod.create()
                        .matcher(MethodMatcher.create().usingStrings(anchor)))) {
                    addBuilder(found, md, classLoader);
                }
            }
            Resources res = context.getResources();
            String pkg = context.getPackageName();
            int feedButton = res.getIdentifier("reposts_ufi_icon", "id", pkg);
            if (feedButton != 0) {
                for (Found md : DexKitQueryCache.methods(FindMethod.create()
                        .matcher(MethodMatcher.create().paramCount(1).usingNumbers(feedButton)))) {
                    addBuilder(found, md, classLoader);
                }
            }
            int reelCount = res.getIdentifier("repost_count", "id", pkg);
            if (reelCount != 0) {
                for (Found md : DexKitQueryCache.methods(FindMethod.create()
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

    private static void addBuilder(List<Method> found, Found md, ClassLoader classLoader) {
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

    private static boolean ownRender(Method m) {
        return ps.reso.instaeclipse.utils.core.HookTargetGuard.ownClass(m.getDeclaringClass());
    }

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
