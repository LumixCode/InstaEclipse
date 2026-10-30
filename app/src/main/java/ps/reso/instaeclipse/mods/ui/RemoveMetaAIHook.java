package ps.reso.instaeclipse.mods.ui;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.res.Resources;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.luckypray.dexkit.query.FindClass;
import org.luckypray.dexkit.query.FindMethod;
import org.luckypray.dexkit.query.matchers.ClassMatcher;
import org.luckypray.dexkit.query.matchers.MethodMatcher;

import ps.reso.instaeclipse.xposedcompat.XC_MethodHook;
import ps.reso.instaeclipse.xposedcompat.XposedBridge;
import ps.reso.instaeclipse.xposedcompat.XposedHelpers;
import ps.reso.instaeclipse.utils.core.DexKitCache;
import ps.reso.instaeclipse.utils.core.HookTargetGuard;
import ps.reso.instaeclipse.utils.feature.FeatureFlags;
import ps.reso.instaeclipse.utils.feature.FeatureStatusTracker;
import ps.reso.instaeclipse.utils.log.ModuleLog;
import ps.reso.instaeclipse.utils.core.DexKitQueryCache;
import ps.reso.instaeclipse.utils.core.DexKitQueryCache.Found;
import ps.reso.instaeclipse.utils.core.DexKitQueryCache.FoundClass;

public class RemoveMetaAIHook {

    private static final String ADD_BUTTON_CACHE = "RemoveMetaAI_addButton";

    private static volatile boolean idsResolved = false;
    private static final Set<Integer> targetLayoutIds = new HashSet<>();
    private static long lastMenuLog = 0;

    @SuppressLint("DiscouragedApi")
    private static void ensureIds(View anyView) {
        if (idsResolved) return;
        try {
            Resources res = anyView.getResources();
            String pkg = anyView.getContext().getPackageName();
            for (String name : MetaAiLayouts.TARGET_LAYOUTS) {
                int id = res.getIdentifier(name, "layout", pkg);
                if (id != 0) targetLayoutIds.add(id);
            }
            idsResolved = true;
            ModuleLog.detail("(IE|RemoveMetaAI) resolved " + targetLayoutIds.size() + " Meta AI layouts");
        } catch (Throwable ignored) {}
    }

    public void install(ClassLoader classLoader) {
        XC_MethodHook inflateHook = new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam param) {
                if (!FeatureFlags.removeMetaAI) return;
                try {
                    if (!(param.args[0] instanceof Integer)) return;
                    int resource = (int) param.args[0];
                    View result = (View) param.getResult();
                    if (result == null) return;
                    if (ModuleLog.verbose) try {
                        String rn = result.getResources().getResourceEntryName(resource);
                        if (rn != null && (rn.contains("meta") || rn.contains("_ai_") || rn.endsWith("_ai")))
                            ModuleLog.probe("(IE|RemoveMetaAI|PROBE) inflated: " + rn);
                    } catch (Throwable ignored) {}
                    ensureIds(result);
                    if (targetLayoutIds.isEmpty() || !targetLayoutIds.contains(resource)) return;

                    View target = result;
                    boolean attached = param.args.length >= 3
                            ? Boolean.TRUE.equals(param.args[2]) : param.args[1] != null;
                    if (attached && param.args[1] != null && result instanceof ViewGroup) {
                        ViewGroup root = (ViewGroup) result;
                        if (root.getChildCount() > 0) target = root.getChildAt(root.getChildCount() - 1);
                    }
                    collapse(target);
                    FeatureStatusTracker.setHooked("RemoveMetaAI");
                } catch (Throwable ignored) {}
            }
        };

        try {
            XposedHelpers.findAndHookMethod(LayoutInflater.class, "inflate",
                    int.class, ViewGroup.class, boolean.class, inflateHook);
        } catch (Throwable t) {
            ModuleLog.warn("(IE|RemoveMetaAI) Some Meta AI banners in chats and search may still show: "
                    + t.getClass().getSimpleName() + ": " + t.getMessage());
            ModuleLog.detail("(IE|RemoveMetaAI) inflate(3) hook failed");
        }
        try {
            XposedHelpers.findAndHookMethod(LayoutInflater.class, "inflate",
                    int.class, ViewGroup.class, inflateHook);
        } catch (Throwable t) {
            ModuleLog.warn("(IE|RemoveMetaAI) Some Meta AI banners in chats and search may still show: "
                    + t.getClass().getSimpleName() + ": " + t.getMessage());
            ModuleLog.detail("(IE|RemoveMetaAI) inflate(2) hook failed");
        }
        FeatureStatusTracker.setHooked("RemoveMetaAI");
        ModuleLog.ok("(IE|RemoveMetaAI) ready");
    }

    public void installReels(ClassLoader classLoader) {
        installReelsCardEligibility(classLoader);
        installVideoAttribution(classLoader);
        installReelsOverflowGate(classLoader);
        installMenuOptionFilter(classLoader);
        installAddButtonFilter(classLoader);
        installReelContentDeepDive(classLoader);
    }

    private void installReelContentDeepDive(ClassLoader cl) {
        XC_MethodHook skip = new XC_MethodHook() {
            @Override protected void beforeHookedMethod(MethodHookParam p) {
                if (FeatureFlags.removeMetaAI) p.setResult(null);
            }
        };
        hookByMarker(cl, skip,
                "android_purge_26_q3_ClipsContentDeepDivePromptFetcher_fetchContentDeepDivePromptDataForMediaIds",
                "cdd-fetch", null);

        XC_MethodHook skipIfVoid = new XC_MethodHook() {
            @Override protected void beforeHookedMethod(MethodHookParam p) {
                if (!FeatureFlags.removeMetaAI) return;
                try {
                    if (((java.lang.reflect.Method) p.method).getReturnType() == void.class) p.setResult(null);
                } catch (Throwable ignored) {}
            }
        };
        hookByMarker(cl, skipIfVoid,
                "android_purge_26_q3_ClipsOrganicMediaItemViewMoreOptionsController_maybeAddGenAIInfoRow",
                "reel-menu-genai-row", null);
    }

    private void hookByMarker(ClassLoader cl, XC_MethodHook hook,
                              String marker, String label, String returnTypeFilter) {
        int n = 0;
        try {
            String key = "RemoveMetaAI_" + marker + (returnTypeFilter != null ? "|" + returnTypeFilter : "");
            List<Method> targets = DexKitCache.isCacheValid() ? DexKitCache.loadMethods(key, cl) : null;
            if (targets == null) {
                targets = new ArrayList<>();
                MethodMatcher mm = MethodMatcher.create().usingStrings(marker);
                if (returnTypeFilter != null) mm = mm.returnType(returnTypeFilter);
                for (Found md : DexKitQueryCache.methods(FindMethod.create().matcher(mm))) {
                    try {
                        Method m = md.getMethodInstance(cl);
                        String shared = sharedReason(md, m, marker);
                        if (shared != null) {
                            ModuleLog.detail("(IE|RemoveMetaAI) " + label + ": skipped "
                                    + md.getClassName() + "." + md.getName() + " (" + shared + ")");
                            continue;
                        }
                        targets.add(m);
                    } catch (Throwable ignored) {}
                }
                DexKitCache.saveMethods(key, targets);
            }
            for (Method m : targets) {
                try {
                    XposedBridge.hookMethod(m, hook);
                    n++;
                } catch (Throwable ignored) {}
            }
        } catch (Throwable t) {
            ModuleLog.warn("(IE|RemoveMetaAI) Could not look for one of the Meta AI elements; it may still show");
            ModuleLog.detail("(IE|RemoveMetaAI) " + label + ": " + t.getClass().getSimpleName() + ": " + t.getMessage());
        }
        ModuleLog.detail("(IE|RemoveMetaAI) " + label + ": " + n + " method(s)");
    }

    private static String sharedReason(Found md, Method m, String marker) {
        Class<?> owner = m.getDeclaringClass();
        if (HookTargetGuard.isMergedLambda(owner, md.data().getOpCodes())) return "R8-merged lambda";
        if (!HookTargetGuard.ownsMarker(md.data().getUsingStrings(), marker)) return "marker inlined into another method";
        if (!HookTargetGuard.ownClass(owner)) return "abstract or non-final class";
        return null;
    }

    private void installReelsCardEligibility(ClassLoader cl) {
        XC_MethodHook skip = new XC_MethodHook() {
            @Override protected void beforeHookedMethod(MethodHookParam p) {
                if (FeatureFlags.removeMetaAI) p.setResult(null);
            }
        };
        String[] anchors = {
                "android_purge_26_q3_MetaAiClipsEligibilityFetcher_fetchEligibility",
                "android_purge_26_q3_MetaAiClipsEligibilityFetcher_onClipsItemsRequestFinished",
                "android_purge_26_q3_MetaAiClipsEligibilityFetcher_onClipsItemsRequestSuccess",
        };
        int n = 0;
        java.util.Set<String> hooked = new HashSet<>();
        for (String a : anchors) {
            try {
                for (Found md : DexKitQueryCache.hookable(DexKitQueryCache.methods(FindMethod.create()
                        .matcher(MethodMatcher.create().usingStrings(a))), cl)) {
                    String key = md.getDescriptor();
                    if (!hooked.add(key)) continue;
                    try { XposedBridge.hookMethod(md.getMethodInstance(cl), skip); n++; }
                    catch (Throwable ignored) {}
                }
            } catch (Throwable t) {
                ModuleLog.warn("(IE|RemoveMetaAI) The Meta AI card in Reels may still show: search failed");
                ModuleLog.detail("(IE|RemoveMetaAI) reels-card anchor " + a + ": " + t.getClass().getSimpleName() + ": " + t.getMessage());
            }
        }
        ModuleLog.detail("(IE|RemoveMetaAI) reels-card eligibility: " + n + " method(s)");
    }

    private void installVideoAttribution(ClassLoader cl) {
        XC_MethodHook blank = new XC_MethodHook() {
            @Override protected void afterHookedMethod(MethodHookParam p) {
                if (!FeatureFlags.removeMetaAI) return;
                Object r = p.getResult();
                if (r == null) return;
                try {
                    for (java.lang.reflect.Field f : ps.reso.instaeclipse.utils.core.SafeReflect.fields(r.getClass())) {
                        if (f.getType() != String.class) continue;
                        if (java.lang.reflect.Modifier.isStatic(f.getModifiers())) continue;
                        f.setAccessible(true);
                        if (f.get(r) != null) f.set(r, "");
                    }
                } catch (Throwable ignored) {}
            }
        };
        hookByMarker(cl, blank,
                "android_purge_26_q3_MetaAIVideoAttributionSubtitleUseCase_getUiState",
                "video-attribution", null);
    }

    private static final String REEL_MENU_MARKER =
            "android_purge_26_q3_ClipsOrganicMediaItemViewMoreOptionsController_maybeAddGenAIInfoRow";
    private static final String SESSION = "com.instagram.common.session.UserSession";
    private static final int GATE_MIN_SIBLINGS = 2;

    private void installReelsOverflowGate(ClassLoader classLoader) {
        XC_MethodHook forceFalse = new XC_MethodHook() {
            @Override protected void beforeHookedMethod(MethodHookParam param) {
                if (FeatureFlags.removeMetaAI) param.setResult(false);
            }
        };
        int n = hookGate(classLoader, forceFalse);
        ModuleLog.detail("(IE|RemoveMetaAI) reels-overflow gate: " + n + " method(s)");
    }

    private int hookGate(ClassLoader cl, XC_MethodHook hook) {
        int n = 0;
        try {
            for (Method gate : findOverflowGate(cl)) {
                try { XposedBridge.hookMethod(gate, hook); n++; }
                catch (Throwable ignored) {}
            }
        } catch (Throwable t) {
            ModuleLog.detail("(IE|RemoveMetaAI) overflow gate search failed: " + t.getClass().getSimpleName() + ": " + t.getMessage());
        }
        return n;
    }

    private static List<Method> findOverflowGate(ClassLoader cl) throws NoSuchMethodException {
        Set<Method> gates = new HashSet<>();
        for (Found marker : DexKitQueryCache.methods(FindMethod.create().matcher(
                MethodMatcher.create().usingStrings(REEL_MENU_MARKER)))) {
            for (Found gate : DexKitQueryCache.methods(FindMethod.create().matcher(MethodMatcher.create()
                    .returnType("boolean").paramTypes(SESSION)
                    .addCaller(MethodMatcher.create().declaredClass(marker.getClassName()))))) {
                Method method = gate.getMethodInstance(cl);
                if (!Modifier.isStatic(method.getModifiers()) && sessionSiblings(gate) >= GATE_MIN_SIBLINGS) gates.add(method);
            }
        }
        return gates.size() == 1 ? new ArrayList<>(gates) : List.of();
    }

    private static int sessionSiblings(Found gate) {
        int siblings = 0;
        for (Found caller : DexKitQueryCache.callers(gate)) {
            if (caller.getClassName().equals(gate.getClassName())
                    && caller.getParamTypeNames().equals(List.of(SESSION))) siblings++;
        }
        return siblings;
    }

    private void installMenuOptionFilter(ClassLoader cl) {
        final String od = "Lcom/instagram/feed/media/mediaoption/MediaOption$Option;";
        XC_MethodHook filter = new XC_MethodHook() {
            @Override protected void afterHookedMethod(MethodHookParam param) {
                if (!FeatureFlags.removeMetaAI) return;
                Object r = param.getResult();
                if (!(r instanceof List)) return;
                try {
                    List<?> src = (List<?>) r;
                    long now = System.currentTimeMillis();
                    if (ModuleLog.verbose && now - lastMenuLog > 1500) {
                        lastMenuLog = now;
                        StringBuilder sb = new StringBuilder();
                        for (Object o : src) sb.append(o).append(",");
                        ModuleLog.probe("(IE|RemoveMetaAI|PROBE) menu opts=[" + sb + "]");
                    }
                    List<Object> keep = new java.util.ArrayList<>(src.size());
                    boolean changed = false;
                    for (Object o : src) {
                        if (o != null && isMetaAiOption(o.toString())) { changed = true; continue; }
                        keep.add(o);
                    }
                    if (!changed) return;
                    try { ((List<Object>) src).clear(); ((List<Object>) src).addAll(keep); }
                    catch (Throwable mutateFail) { param.setResult(keep); }
                } catch (Throwable ignored) {}
            }
        };
        hookBuilder(cl, filter, FindMethod.create().matcher(MethodMatcher.create()
                .returnType("java.util.List").addUsingField(od + "->GEN_AI_INFO:" + od)), "list-GEN_AI_INFO");
        hookBuilder(cl, filter, FindMethod.create().matcher(MethodMatcher.create()
                .returnType("java.util.ArrayList")
                .addUsingField(od + "->PLAYBACK_CONTROLS:" + od)
                .addUsingField(od + "->UNSAVE:" + od)), "reel-options");
    }

    private void installAddButtonFilter(ClassLoader cl) {
        try {
            Class<?> option = cl.loadClass("com.instagram.feed.media.mediaoption.MediaOption$Option");
            Method addButton = DexKitCache.isCacheValid() ? DexKitCache.loadMethod(ADD_BUTTON_CACHE, cl) : null;
            if (addButton == null) {
                addButton = findAddButton(cl, option);
                if (addButton == null) {
                    ModuleLog.warn("(IE|RemoveMetaAI) The Meta AI row may still show in some post menus (not found in this Instagram version)");
                    ModuleLog.detail("(IE|RemoveMetaAI) overflow add-button not found");
                    return;
                }
                DexKitCache.saveMethod(ADD_BUTTON_CACHE, addButton);
            }
            int optionIndex = Arrays.asList(addButton.getParameterTypes()).indexOf(option);
            if (optionIndex < 0) return;
            XposedBridge.hookMethod(addButton, new XC_MethodHook() {
                @Override protected void beforeHookedMethod(MethodHookParam param) {
                    Object opt = param.args[optionIndex];
                    if (FeatureFlags.removeMetaAI && opt != null && isMetaAiOption(opt.toString())) {
                        param.setResult(null);
                    }
                }
            });
            ModuleLog.detail("(IE|RemoveMetaAI) overflow add-button filter: 1 method(s)");
        } catch (Throwable t) {
            ModuleLog.warn("(IE|RemoveMetaAI) The Meta AI row may still show in some post menus: "
                    + t.getClass().getSimpleName() + ": " + t.getMessage());
        }
    }

    private static Method findAddButton(ClassLoader cl, Class<?> option) {
        List<FoundClass> creators = DexKitQueryCache.classes(FindClass.create().matcher(
                ClassMatcher.create().usingStrings("MediaOptionsOverflowMenuCreator")));
        if (creators.isEmpty()) return null;
        for (Found md : DexKitQueryCache.methods(FindMethod.create().matcher(MethodMatcher.create()
                .declaredClass(creators.get(0).getName()).returnType("void")))) {
            try {
                Method m = md.getMethodInstance(cl);
                if (!Modifier.isStatic(m.getModifiers()) || m.getParameterCount() < 4) continue;
                boolean takesList = false;
                for (Class<?> p : m.getParameterTypes()) takesList |= ArrayList.class.isAssignableFrom(p);
                if (takesList && Arrays.asList(m.getParameterTypes()).contains(option)) {
                    m.setAccessible(true);
                    return m;
                }
            } catch (Throwable ignored) {}
        }
        return null;
    }

    private void hookBuilder(ClassLoader cl, XC_MethodHook hook, FindMethod q, String label) {
        try {
            int n = 0;
            for (Found md : DexKitQueryCache.hookable(DexKitQueryCache.methods(q), cl)) {
                try { XposedBridge.hookMethod(md.getMethodInstance(cl), hook); n++; } catch (Throwable ignored) {}
            }
            ModuleLog.detail("(IE|RemoveMetaAI) " + label + " filter: " + n + " method(s)");
        } catch (Throwable t) {
            ModuleLog.warn("(IE|RemoveMetaAI) The Meta AI row may still show in some post or reel menus: search failed");
            ModuleLog.detail("(IE|RemoveMetaAI) " + label + ": " + t.getClass().getSimpleName() + ": " + t.getMessage());
        }
    }

    private static boolean isMetaAiOption(String name) {
        String n = name.toUpperCase();
        return n.contains("GEN_AI") || n.contains("GENAI") || n.contains("META_AI")
                || n.contains("METAAI") || n.contains("ASK_META")
                || n.contains("CONTENT_DEEP_DIVE");
    }

    private static void collapse(View v) {
        if (v == null) return;
        v.setVisibility(View.GONE);
        ViewGroup.LayoutParams lp = v.getLayoutParams();
        if (lp != null) { lp.height = 0; lp.width = 0; v.setLayoutParams(lp); }
    }

    private static void collapseIfShown(View v) {
        if (v != null && v.getVisibility() != View.GONE) collapse(v);
    }

    private static final java.util.Set<View> watchedSheetDecors =
            java.util.Collections.newSetFromMap(new java.util.WeakHashMap<>());
    private static final long SHEET_SWEEP_MS = 150;
    private static final android.os.Handler MAIN = new android.os.Handler(android.os.Looper.getMainLooper());
    private static final java.util.Set<View> pendingSheetSweeps =
            java.util.Collections.newSetFromMap(new java.util.WeakHashMap<>());

    public static void watchActionSheet(Activity a) {
        if (a == null || !FeatureFlags.removeMetaAI) return;
        try {
            final View decor = a.getWindow() != null ? a.getWindow().getDecorView() : null;
            if (decor == null || !watchedSheetDecors.add(decor)) return;
            decor.getViewTreeObserver().addOnGlobalLayoutListener(() -> {
                if (!FeatureFlags.removeMetaAI || !pendingSheetSweeps.add(decor)) return;
                sweepActionSheet(decor);
                MAIN.postDelayed(() -> {
                    pendingSheetSweeps.remove(decor);
                    if (FeatureFlags.removeMetaAI) sweepActionSheet(decor);
                }, SHEET_SWEEP_MS);
            });
        } catch (Throwable ignored) {}
    }

    private static void sweepActionSheet(View root) {
        try {
            int composerId = ps.reso.instaeclipse.utils.ui.ResIds.id(root.getContext(), "row_thread_composer_container");
            int sheetId = ps.reso.instaeclipse.utils.ui.ResIds.id(root.getContext(), "action_sheet_container");
            if (!ComposerSweepGuard.sweeps(composerId, sheetId)) return;
            java.util.List<View> composers = new java.util.ArrayList<>();
            findViewsById(root, composerId, composers);
            for (View composer : composers) {
                if (findAncestorById(composer, sheetId) == null) continue;
                View recycler = findAncestorOfClass(composer, "RecyclerView");
                if (!(recycler instanceof ViewGroup rv)) { collapseIfShown(composer); continue; }
                View item = composer;
                while (item.getParent() != rv && item.getParent() instanceof View p) item = p;
                if (item.getParent() != rv) { collapseIfShown(composer); continue; }
                int idx = rv.indexOfChild(item);
                collapseIfShown(item);
                for (int i = idx - 1; i >= 0 && i >= idx - 3; i--) {
                    View sib = rv.getChildAt(i);
                    if (sib == null || sib.getVisibility() == View.GONE) continue;
                    if (isInteractive(sib) || !containsVisibleText(sib)) break;
                    collapse(sib);
                }
                FeatureStatusTracker.setHooked("RemoveMetaAI");
            }
        } catch (Throwable ignored) {}
    }

    private static void findViewsById(View v, int id, java.util.List<View> out) {
        if (v == null) return;
        if (v.getId() == id) out.add(v);
        if (v instanceof ViewGroup vg)
            for (int i = 0; i < vg.getChildCount(); i++) findViewsById(vg.getChildAt(i), id, out);
    }

    private static View findAncestorById(View v, int id) {
        for (android.view.ViewParent p = v.getParent(); p instanceof View pv; p = pv.getParent())
            if (pv.getId() == id) return pv;
        return null;
    }

    private static View findAncestorOfClass(View v, String simpleNameContains) {
        for (android.view.ViewParent p = v.getParent(); p instanceof View pv; p = pv.getParent())
            if (pv.getClass().getName().contains(simpleNameContains)) return pv;
        return null;
    }

    private static boolean isInteractive(View v) {
        if (v == null) return false;
        if ((v.isClickable() || v.isLongClickable()) && !(v instanceof android.widget.TextView)) return true;
        if (v instanceof android.widget.Button || v instanceof android.widget.ImageButton
                || v instanceof android.widget.Switch) return true;
        if (v instanceof ViewGroup vg)
            for (int i = 0; i < vg.getChildCount(); i++) if (isInteractive(vg.getChildAt(i))) return true;
        return false;
    }

    private static boolean containsVisibleText(View v) {
        if (v == null || v.getVisibility() == View.GONE) return false;
        if (v instanceof android.widget.TextView tv) {
            CharSequence t = tv.getText();
            return t != null && t.length() > 0;
        }
        if (v instanceof ViewGroup vg)
            for (int i = 0; i < vg.getChildCount(); i++) if (containsVisibleText(vg.getChildAt(i))) return true;
        return false;
    }
}
