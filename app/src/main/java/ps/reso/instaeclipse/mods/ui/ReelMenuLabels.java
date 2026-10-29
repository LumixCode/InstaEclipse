package ps.reso.instaeclipse.mods.ui;

import android.content.Context;
import android.view.View;

import org.luckypray.dexkit.query.FindMethod;
import org.luckypray.dexkit.query.matchers.MethodMatcher;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import ps.reso.instaeclipse.xposedcompat.XC_MethodHook;
import ps.reso.instaeclipse.xposedcompat.XposedBridge;
import ps.reso.instaeclipse.utils.core.DexKitCache;
import ps.reso.instaeclipse.utils.core.DexKitQueryCache;
import ps.reso.instaeclipse.utils.core.DexKitQueryCache.Found;
import ps.reso.instaeclipse.utils.i18n.I18n;
import ps.reso.instaeclipse.utils.log.ModuleLog;

public final class ReelMenuLabels {

    public static final Map<String, Integer> EXTRA_REEL_LABELS = new ConcurrentHashMap<>();

    private static final String RESOLVER_ANCHOR = "Unsupported text row for Clips Viewer Overflow menu.";

    private static Class<?> mediaOptionEnumClass;
    private static Object copyLinkOptionForLabels;

    private ReelMenuLabels() {}

    public static void install(ClassLoader classLoader) {
        try {
            mediaOptionEnumClass = classLoader.loadClass(
                    "com.instagram.feed.media.mediaoption.MediaOption$Option");
            Object[] values = (Object[]) mediaOptionEnumClass.getMethod("values").invoke(null);
            for (Object v : values) if (v.toString().equals("COPY_LINK")) copyLinkOptionForLabels = v;
        } catch (Throwable t) {
            ModuleLog.warn("(IE|ReelLabels) The rows InstaEclipse adds to the reel menu may show the wrong names: "
                    + t.getClass().getSimpleName() + ": " + t.getMessage());
            return;
        }
        Method resolver = labelResolver(classLoader);
        if (resolver == null) {
            ModuleLog.warn("(IE|ReelLabels) The rows InstaEclipse adds to the reel menu may show the wrong names (not found in this Instagram version)");
            ModuleLog.detail("(IE|ReelLabels) reel label resolver not found");
            return;
        }
        hookReelLabelResolver(resolver);
        try {
            installReelLabelOverrideHook(classLoader, resolver);
        } catch (Throwable t) {
            ModuleLog.warn("(IE|ReelLabels) The rows InstaEclipse adds to the reel menu may show the wrong names: "
                    + t.getClass().getSimpleName() + ": " + t.getMessage());
        }
    }

    private static Method labelResolver(ClassLoader classLoader) {
        try {
            Method resolver = DexKitCache.isCacheValid()
                    ? DexKitCache.loadMethod("ReelLabelResolver", classLoader) : null;
            if (resolver != null) return resolver;
            List<Found> found = DexKitQueryCache.methods(FindMethod.create().matcher(MethodMatcher.create()
                    .usingEqStrings(List.of(RESOLVER_ANCHOR))));
            if (found.isEmpty()) return null;
            resolver = found.get(0).getMethodInstance(classLoader);
            DexKitCache.saveMethod("ReelLabelResolver", resolver);
            return resolver;
        } catch (Throwable t) {
            ModuleLog.detail("(IE|ReelLabels) reel label resolver lookup failed: " + t.getClass().getSimpleName() + ": " + t.getMessage());
            return null;
        }
    }

    private static void hookReelLabelResolver(Method resolver) {
        try {
            XposedBridge.hookMethod(resolver, new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    if (EXTRA_REEL_LABELS.isEmpty() || copyLinkOptionForLabels == null) return;
                    for (int i = 0; i < param.args.length; i++) {
                        Object a = param.args[i];
                        if (a != null && mediaOptionEnumClass.isInstance(a) && EXTRA_REEL_LABELS.containsKey(a.toString()))
                            param.args[i] = copyLinkOptionForLabels;
                    }
                }
            });
        } catch (Throwable t) {
            ModuleLog.warn("(IE|ReelLabels) The rows InstaEclipse adds to the reel menu may show the wrong names: "
                    + t.getClass().getSimpleName() + ": " + t.getMessage());
        }
    }

    private static void installReelLabelOverrideHook(ClassLoader classLoader, Method labelResolver) {
        if (DexKitCache.isCacheValid()) {
            List<Method> cached = DexKitCache.loadMethods("ReelRowBuilders", classLoader);
            if (cached != null && !cached.isEmpty()) {
                for (Method m : cached) {
                    try { m.setAccessible(true); XposedBridge.hookMethod(m, BUILDER_LABEL); }
                    catch (Throwable ignored) {}
                }
                ModuleLog.ok("(IE|ReelLabels) ready");
                ModuleLog.detail("(IE|ReelLabels) reel label override (cached), "
                        + cached.size() + " builder(s)");
                return;
            }
        }

        List<Found> adderResults = DexKitQueryCache.methods(FindMethod.create()
                .matcher(MethodMatcher.create()
                        .returnType("void")
                        .addInvoke(MethodMatcher.create(labelResolver))));

        List<Method> hooked = new ArrayList<>();
        Set<Method> seen = new HashSet<>();
        for (Found md : adderResults) {
            Method rowAdder;
            try { rowAdder = md.getMethodInstance(classLoader); }
            catch (Throwable ignored) { continue; }

            boolean takesOption = false;
            for (Class<?> p : rowAdder.getParameterTypes())
                if (p == mediaOptionEnumClass) { takesOption = true; break; }
            if (!takesOption) continue;

            List<Found> builderResults;
            try {
                builderResults = DexKitQueryCache.methods(FindMethod.create()
                        .matcher(MethodMatcher.create()
                                .returnType("void")
                                .addCaller(MethodMatcher.create(rowAdder))));
            } catch (Throwable ignored) { continue; }

            for (Found bmd : builderResults) {
                Method bm;
                try { bm = bmd.getMethodInstance(classLoader); }
                catch (Throwable ignored) { continue; }
                if (!isRowBuilderShape(bm)) continue;
                if (!seen.add(bm)) continue;
                try {
                    bm.setAccessible(true);
                    XposedBridge.hookMethod(bm, BUILDER_LABEL);
                    hooked.add(bm);
                    ModuleLog.detail("(IE|ReelLabels) reel label override on "
                            + bm.getDeclaringClass().getName() + "." + bm.getName());
                } catch (Throwable ignored) {}
            }
        }

        if (hooked.isEmpty()) {
            ModuleLog.warn("(IE|ReelLabels) The rows InstaEclipse adds to the reel menu may show the wrong names (not found in this Instagram version)");
            ModuleLog.detail("(IE|ReelLabels) no reel row-builder methods found");
        } else {
            ModuleLog.ok("(IE|ReelLabels) ready");
            DexKitCache.saveMethods("ReelRowBuilders", hooked);
        }
    }

    private static boolean isRowBuilderShape(Method m) {
        if (m.getReturnType() != void.class) return false;
        Class<?>[] p = m.getParameterTypes();
        if (p.length < 4) return false;
        if (!Context.class.isAssignableFrom(p[0])) return false;
        if (!View.OnClickListener.class.isAssignableFrom(p[1])) return false;
        for (Class<?> c : p) if (c == String.class) return true;
        return false;
    }

    private static Object findCarrierOptionOnListener(Object listener) {
        if (listener == null) return null;
        Class<?> cls = listener.getClass();
        while (cls != null && cls != Object.class) {
            for (Field f : cls.getDeclaredFields()) {
                if (!mediaOptionEnumClass.isAssignableFrom(f.getType()) && f.getType() != Object.class)
                    continue;
                try {
                    f.setAccessible(true);
                    Object v = f.get(listener);
                    if (mediaOptionEnumClass.isInstance(v)) return v;
                } catch (Throwable ignored) {}
            }
            cls = cls.getSuperclass();
        }
        return null;
    }

    private static final XC_MethodHook BUILDER_LABEL = new XC_MethodHook() {
        @Override
        protected void beforeHookedMethod(MethodHookParam param) {
            if (EXTRA_REEL_LABELS.isEmpty()) return;
            try {
                Object[] args = param.args;
                if (args == null) return;

                Integer label = null;
                int lastListenerIdx = -1;
                for (int i = 0; i < args.length; i++) {
                    if (!(args[i] instanceof View.OnClickListener)) continue;
                    lastListenerIdx = i;
                    if (label == null) {
                        Object opt = findCarrierOptionOnListener(args[i]);
                        if (opt != null) label = EXTRA_REEL_LABELS.get(opt.toString());
                    }
                }
                if (label == null) return;

                int titleIdx = -1;
                for (int i = lastListenerIdx + 1; i < args.length; i++) {
                    if (args[i] instanceof CharSequence) { titleIdx = i; break; }
                }
                if (titleIdx < 0) return;

                Context ctx = (args[0] instanceof Context c) ? c : UIHookManager.getCurrentActivity();
                if (ctx == null) return;

                args[titleIdx] = I18n.t(ctx, label);
                MenuIcons.fixSoon();
            } catch (Throwable t) {
                ModuleLog.warn("(IE|ReelLabels) Could not rename a row in the reel menu: " + t.getClass().getSimpleName() + ": " + t.getMessage());
            }
        }
    };
}
