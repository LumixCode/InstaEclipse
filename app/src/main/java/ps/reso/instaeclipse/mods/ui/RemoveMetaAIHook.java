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

import org.luckypray.dexkit.DexKitBridge;
import org.luckypray.dexkit.query.FindClass;
import org.luckypray.dexkit.query.FindMethod;
import org.luckypray.dexkit.query.matchers.ClassMatcher;
import org.luckypray.dexkit.query.matchers.MethodMatcher;
import org.luckypray.dexkit.result.ClassData;
import org.luckypray.dexkit.result.MethodData;

import ps.reso.instaeclipse.xposedcompat.XC_MethodHook;
import ps.reso.instaeclipse.xposedcompat.XposedBridge;
import ps.reso.instaeclipse.xposedcompat.XposedHelpers;
import ps.reso.instaeclipse.utils.core.DexKitCache;
import ps.reso.instaeclipse.utils.core.HookTargetGuard;
import ps.reso.instaeclipse.utils.feature.FeatureFlags;
import ps.reso.instaeclipse.utils.feature.FeatureStatusTracker;
import ps.reso.instaeclipse.utils.log.ModuleLog;
import ps.reso.instaeclipse.utils.core.DexKitQueryCache;

/**
 * Removes Meta AI entry points (GitHub #179) by collapsing the Meta AI XML layouts as they inflate.
 * Anchored on STABLE public resource names (never obfuscated X.* ids, no version gate); new names
 * first, legacy last; any name that doesn't resolve on the running build is skipped. Covers the DM
 * composer Meta AI buttons and the "Ask Meta AI" search-results plugin. The network side (stopping
 * Meta AI from replying) is handled in IGNetworkInterceptor under the same flag.
 */
public class RemoveMetaAIHook {

    private static final String ADD_BUTTON_CACHE = "RemoveMetaAI_addButton";

    private static final String[] TARGET_LAYOUTS = {
            // DM composer Meta AI buttons
            "direct_composer_meta_ai_invocation_button",
            "direct_composer_meta_ai_discovery_button",
            "direct_composer_bar_meta_ai_v2",
            "direct_composer_overflow_button_meta_ai_v2",
            "direct_composer_meta_ai_voice_button_v2",
            "direct_composer_meta_ai_voice_button",
            "direct_composer_meta_ai_composer_upsell",
            "direct_composer_meta_ai_nux_disclaimer",
            // In-thread AI summary pill + prompt pills / NUX banners
            "direct_in_thread_ai_summary_pill",
            "layout_meta_ai_large_ask_pill",
            "layout_meta_ai_prompt_pill",
            "layout_meta_ai_suggested_prompts",
            "layout_meta_ai_scrollable_prompts",
            "layout_meta_ai_scrollable_prompts_h_scroll",
            "layout_meta_ai_chat_history_nux_banner",
            "layout_meta_ai_in_thread_blocking_nux",
            // Search SERP "Ask Meta AI" HCM card (447 real names; the *_search_plugin_* names are stale)
            "layout_meta_ai_hcm",
            "layout_meta_ai_hcm_shimmer",
    };

    private static volatile boolean idsResolved = false;
    private static final Set<Integer> targetLayoutIds = new HashSet<>();
    private static long lastMenuLog = 0;

    @SuppressLint("DiscouragedApi")
    private static void ensureIds(View anyView) {
        if (idsResolved) return;
        try {
            Resources res = anyView.getResources();
            String pkg = anyView.getContext().getPackageName();
            for (String name : TARGET_LAYOUTS) {
                int id = res.getIdentifier(name, "layout", pkg);
                if (id != 0) targetLayoutIds.add(id);
            }
            idsResolved = true;
            ModuleLog.line("(IE|RemoveMetaAI) resolved " + targetLayoutIds.size() + " Meta AI layouts");
        } catch (Throwable ignored) {}
    }

    public void install(ClassLoader classLoader) {
        XC_MethodHook inflateHook = new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam param) { ps.reso.instaeclipse.utils.core.JitGuard.tick(param.method);
                if (!FeatureFlags.removeMetaAI) return;
                try {
                    if (!(param.args[0] instanceof Integer)) return;
                    int resource = (int) param.args[0];
                    View result = (View) param.getResult();
                    if (result == null) return;
                    // PROBE: surface any inflated layout whose name mentions meta/ai, to confirm the
                    // Meta AI surfaces are XML-inflated (vs Litho/Compose, which this hook can't catch).
                    if (ModuleLog.verbose) try {
                        String rn = result.getResources().getResourceEntryName(resource);
                        if (rn != null && (rn.contains("meta") || rn.contains("_ai_") || rn.endsWith("_ai")))
                            ModuleLog.probe("(IE|RemoveMetaAI|PROBE) inflated: " + rn);
                    } catch (Throwable ignored) {}
                    ensureIds(result);
                    if (targetLayoutIds.isEmpty() || !targetLayoutIds.contains(resource)) return;

                    // inflate(int, ViewGroup, boolean): when attachToRoot, the inflated layout is the
                    // root's last child; otherwise the returned view IS the inflated layout.
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
            ModuleLog.line("(IE|RemoveMetaAI) ⚠️ inflate(3) hook: " + t.getMessage());
        }
        try {
            XposedHelpers.findAndHookMethod(LayoutInflater.class, "inflate",
                    int.class, ViewGroup.class, inflateHook);
        } catch (Throwable t) {
            ModuleLog.line("(IE|RemoveMetaAI) ⚠️ inflate(2) hook: " + t.getMessage());
        }
        FeatureStatusTracker.setHooked("RemoveMetaAI");
        ModuleLog.line("(IE|RemoveMetaAI) ✅ installed");
    }

    /**
     * Removes the Meta AI surfaces in Reels and on video posts. Anchored on stable marker
     * strings verified against the exact installed build (447.0.0.21.81) — the earlier
     * "..._render"→null anchors sat on trace-scope companion stubs that the live Litho path
     * never invokes, so nothing fired. These target the data/eligibility layer instead, which
     * runs on the normal path and carries stable strings. Needs the DexKit bridge.
     */
    public void installReels(DexKitBridge bridge, ClassLoader classLoader) {
        installReelsCardEligibility(bridge, classLoader); // in-feed reels Meta AI card
        installVideoAttribution(bridge, classLoader);     // Meta AI attribution subtitle (video posts)
        installReelsOverflowGate(bridge, classLoader);    // reel ⋮ "Ask Meta AI" entrypoint
        installMenuOptionFilter(bridge, classLoader);     // GEN_AI MediaOption rows in overflow menus
        installAddButtonFilter(bridge, classLoader);      // ... and rows added one at a time
        installSearchSerpMetaAiHcm(bridge, classLoader);  // "Ask Meta AI" card in search results (446+)
        installReelContentDeepDive(bridge, classLoader);  // reel "Ask Meta AI about this" (Content Deep Dive)
    }

    /**
     * Reel "Ask Meta AI about this" — the Content Deep Dive prompt/entry (446+, Litho; appears in the
     * reel more-options sheet and as a pill). It is NOT a MediaOption row, so the menu-option filter
     * can't reach it. Kill it at the data layer instead: force its eligibility gate
     * (ClipsMediaInfoComponent.shouldShowContentDeepDivePrompt) to false and no-op its prompt-data
     * fetcher — with no eligibility and no prompt data, neither the pill nor the menu entry is built.
     * Anchored on stable trace-marker strings (classes are obfuscated). Both are gated on the flag.
     */
    private void installReelContentDeepDive(DexKitBridge bridge, ClassLoader cl) {
        // Gate: shouldShowContentDeepDivePrompt(...) -> force false. Return type may be boolean OR
        // boxed Boolean, so decide at runtime from the hooked method's actual return type (never
        // clobber a non-boolean return).
        XC_MethodHook forceFalse = new XC_MethodHook() {
            @Override protected void beforeHookedMethod(MethodHookParam p) { ps.reso.instaeclipse.utils.core.JitGuard.tick(p.method);
                if (!FeatureFlags.removeMetaAI) return;
                try {
                    Class<?> rt = ((java.lang.reflect.Method) p.method).getReturnType();
                    if (rt == boolean.class || rt == Boolean.class) p.setResult(false);
                } catch (Throwable ignored) {}
            }
        };
        hookByMarker(bridge, cl, forceFalse,
                "android_purge_26_q3_ClipsMediaInfoComponent_shouldShowContentDeepDivePrompt",
                "cdd-should-show", null);
        // Also blank the Content Deep Dive UI-state builder (getUiState) — belt and braces if the
        // gate lives elsewhere: with an empty/blanked ui-state the pill/entry has nothing to show.
        // On IG 446 and 451 R8 inlined it into the whole Clips MediaInfoUseCase.getUiState
        // (X.047j.A0V on 451), so hookByMarker skips it there rather than blank all reel media info.
        XC_MethodHook blankUiState = new XC_MethodHook() {
            @Override protected void afterHookedMethod(MethodHookParam p) { ps.reso.instaeclipse.utils.core.JitGuard.tick(p.method);
                if (!FeatureFlags.removeMetaAI) return;
                Object r = p.getResult();
                if (r == null) return;
                try {
                    for (java.lang.reflect.Field f : ps.reso.instaeclipse.utils.core.SafeReflect.fields(r.getClass())) {
                        if (f.getType() != String.class || java.lang.reflect.Modifier.isStatic(f.getModifiers())) continue;
                        f.setAccessible(true);
                        if (f.get(r) != null) f.set(r, "");
                    }
                } catch (Throwable ignored) {}
            }
        };
        hookByMarker(bridge, cl, blankUiState,
                "android_purge_26_q3_ContentDeepDiveUseCase_getUiState", "cdd-uistate", null);
        // Fetcher: skip fetching prompt data so there is nothing to render.
        XC_MethodHook skip = new XC_MethodHook() {
            @Override protected void beforeHookedMethod(MethodHookParam p) { ps.reso.instaeclipse.utils.core.JitGuard.tick(p.method);
                if (FeatureFlags.removeMetaAI) p.setResult(null);
            }
        };
        hookByMarker(bridge, cl, skip,
                "android_purge_26_q3_ClipsContentDeepDivePromptFetcher_fetchContentDeepDivePromptDataForMediaIds",
                "cdd-fetch", null);

        // The reel ⋮ more-options menu adds the Meta AI ("GenAI info") row via
        // ClipsOrganicMediaItemViewMoreOptionsController.maybeAddGenAIInfoRow(...). Skip that method
        // so the row is never appended. Only skip if it's void (a side-effecting row-adder) — never
        // clobber a method that returns a value the caller uses.
        XC_MethodHook skipIfVoid = new XC_MethodHook() {
            @Override protected void beforeHookedMethod(MethodHookParam p) { ps.reso.instaeclipse.utils.core.JitGuard.tick(p.method);
                if (!FeatureFlags.removeMetaAI) return;
                try {
                    if (((java.lang.reflect.Method) p.method).getReturnType() == void.class) p.setResult(null);
                } catch (Throwable ignored) {}
            }
        };
        hookByMarker(bridge, cl, skipIfVoid,
                "android_purge_26_q3_ClipsOrganicMediaItemViewMoreOptionsController_maybeAddGenAIInfoRow",
                "reel-menu-genai-row", null);
    }

    /** Hook every method that owns a trace-marker string; if returnTypeFilter is non-null, only hook
     *  methods with that return type (guards the force-false against non-boolean overloads).
     *  Every hook here blanks, skips or replaces, so a method R8 built from more than the marked
     *  method (see {@link #sharedReason}) is skipped and logged instead. */
    private void hookByMarker(DexKitBridge bridge, ClassLoader cl, XC_MethodHook hook,
                              String marker, String label, String returnTypeFilter) {
        int n = 0;
        try {
            // The checked targets are cached (an empty list too, when every hit was shared): the
            // full-dex string scan runs once per Instagram build, not on every start.
            String key = "RemoveMetaAI_" + marker + (returnTypeFilter != null ? "|" + returnTypeFilter : "");
            List<Method> targets = DexKitCache.isCacheValid() ? DexKitCache.loadMethods(key, cl) : null;
            if (targets == null) {
                targets = new ArrayList<>();
                MethodMatcher mm = MethodMatcher.create().usingStrings(marker);
                if (returnTypeFilter != null) mm = mm.returnType(returnTypeFilter);
                for (MethodData md : bridge.findMethod(FindMethod.create().matcher(mm))) {
                    try {
                        Method m = md.getMethodInstance(cl);
                        String shared = sharedReason(md, m, marker);
                        if (shared != null) {
                            ModuleLog.line("(IE|RemoveMetaAI) " + label + ": skipped "
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
            ModuleLog.line("(IE|RemoveMetaAI) ⚠️ " + label + ": " + t.getMessage());
        }
        ModuleLog.line("(IE|RemoveMetaAI) " + label + ": " + n + " method(s)");
    }

    /** Why a marker hit is more than the marked method, or null when it is that method. */
    private static String sharedReason(MethodData md, Method m, String marker) {
        Class<?> owner = m.getDeclaringClass();
        if (HookTargetGuard.isMergedLambda(owner, md.getOpCodes())) return "R8-merged lambda";
        if (!HookTargetGuard.ownsMarker(md.getUsingStrings(), marker)) return "marker inlined into another method";
        if (!HookTargetGuard.ownClass(owner)) return "abstract or non-final class";
        return null;
    }

    /**
     * Search-results "Ask Meta AI" HCM card (IG 446+, Litho-rendered — not XML-inflatable). The
     * card's data comes from ClipsTopSerpDataSource.createDefaultMetaAIHcmFetchResults(...); if that
     * produces no results, no card is shown. We anchor on the method's stable trace-marker string
     * (the class itself is obfuscated) and empty its result at runtime. Only collection/map results
     * are cleared; anything else is left untouched (and its type logged) so we never break the SERP.
     * On IG 446 and 451 the method sits in an R8-merged lambda (X.0Q77 on 451, about 40 unrelated
     * cases), which hookByMarker skips: clearing its result would hit the other cases.
     */
    private void installSearchSerpMetaAiHcm(DexKitBridge bridge, ClassLoader cl) {
        XC_MethodHook neuter = new XC_MethodHook() {
            @Override protected void afterHookedMethod(MethodHookParam p) { ps.reso.instaeclipse.utils.core.JitGuard.tick(p.method);
                if (!FeatureFlags.removeMetaAI) return;
                Object r = p.getResult();
                if (r == null) return;
                try {
                    if (r instanceof java.util.Collection) { ((java.util.Collection<?>) r).clear(); }
                    else if (r instanceof java.util.Map) { ((java.util.Map<?, ?>) r).clear(); }
                    else if (r instanceof Object[]) { p.setResult(java.util.Arrays.copyOf((Object[]) r, 0)); }
                    else { ModuleLog.probe("(IE|RemoveMetaAI) serp-hcm result type=" + r.getClass().getName()); }
                } catch (Throwable ignored) {}
            }
        };
        hookByMarker(bridge, cl, neuter,
                "android_purge_26_q2_ClipsTopSerpDataSource_createDefaultMetaAIHcmFetchResults",
                "search-serp-hcm", null);
    }

    /**
     * In-feed Reels Meta AI card. The card is injected by MetaAiClipsEligibilityFetcher: its
     * fetch method builds the eligible-candidate list and hands it to the generic feed injector.
     * Skipping that method (return void) means no candidates → the unit is never injected.
     * Anchored on the fetcher's stable trace markers; the method only reads+clears sets, so a
     * no-op is side-effect-safe.
     */
    private void installReelsCardEligibility(DexKitBridge bridge, ClassLoader cl) {
        XC_MethodHook skip = new XC_MethodHook() {
            @Override protected void beforeHookedMethod(MethodHookParam p) { ps.reso.instaeclipse.utils.core.JitGuard.tick(p.method);
                if (FeatureFlags.removeMetaAI) p.setResult(null); // void → skip candidate build/inject
            }
        };
        String[] anchors = {
                "android_purge_26_q3_MetaAiClipsEligibilityFetcher_fetchEligibility",
                "android_purge_26_q3_MetaAiClipsEligibilityFetcher_onClipsItemsRequestFinished",
                "android_purge_26_q3_MetaAiClipsEligibilityFetcher_onClipsItemsRequestSuccess", // IG 446+
        };
        int n = 0;
        java.util.Set<String> hooked = new HashSet<>();
        for (String a : anchors) {
            try {
                for (MethodData md : DexKitQueryCache.findMethod(bridge, FindMethod.create()
                        .matcher(MethodMatcher.create().usingStrings(a)))) {
                    String key = md.getDescriptor();
                    if (!hooked.add(key)) continue; // both markers live on the same method
                    try { XposedBridge.hookMethod(md.getMethodInstance(cl), skip); n++; }
                    catch (Throwable ignored) {}
                }
            } catch (Throwable t) {
                ModuleLog.line("(IE|RemoveMetaAI) ⚠️ reels-card anchor " + a + ": " + t.getMessage());
            }
        }
        ModuleLog.line("(IE|RemoveMetaAI) reels-card eligibility: " + n + " method(s)");
    }

    /**
     * Meta AI attribution subtitle on video posts (the "… Meta AI" row on the media). It is
     * built by MetaAIVideoAttributionSubtitleUseCase.getUiState(Media)->uiState. We can't null
     * the result (a downstream final field would NPE), so we blank the UI-state's String fields
     * — the row then renders no Meta AI text. Anchored on the use-case's stable marker string.
     */
    private void installVideoAttribution(DexKitBridge bridge, ClassLoader cl) {
        XC_MethodHook blank = new XC_MethodHook() {
            @Override protected void afterHookedMethod(MethodHookParam p) { ps.reso.instaeclipse.utils.core.JitGuard.tick(p.method);
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
        hookByMarker(bridge, cl, blank,
                "android_purge_26_q3_MetaAIVideoAttributionSubtitleUseCase_getUiState",
                "video-attribution", null);
    }

    // Meta-AI-reels-entrypoint eligibility gate. The reel more-options "blue circle" Meta AI
    // entrypoint (and its sibling contextual entrypoints) is built only when this boolean returns
    // true; the sheet builder checks it and skips the whole entrypoint block otherwise, and the
    // header binder re-checks it at render. It is a boolean(UserSession) that reads exactly two
    // MobileConfig ids — anchored on those (both present + co-located in this build), never an
    // obfuscated name. The standard rows (Download / Copy Link / Save) are built in
    // a different method that doesn't consult this gate, so they're unaffected.
    private static final long META_AI_GATE_ID_1 = 0x81111100005b47L;
    private static final long META_AI_GATE_ID_2 = 0x81106e000057c2L;
    // IG 451.0.0.20.80: same gate (X.05cD.A0B — "A && !B" over two MobileConfig booleans, the first
    // boolean(UserSession) the reel sheet's getBottomSheetHeaderItems builder calls), new ids.
    // Re-derive on a new build: find the builder via the marker
    // "android_purge_26_q3_ClipsOrganicMediaItemViewMoreOptionsController_getBottomSheetHeaderItems"
    // and read the const-wide ids of the first (UserSession)Z it invokes.
    private static final long META_AI_GATE_451_ID_1 = 0x8110d700005b5bL;
    private static final long META_AI_GATE_451_ID_2 = 0x81103500005795L;

    private void installReelsOverflowGate(DexKitBridge bridge, ClassLoader classLoader) {
        XC_MethodHook forceFalse = new XC_MethodHook() {
            @Override protected void beforeHookedMethod(MethodHookParam param) { ps.reso.instaeclipse.utils.core.JitGuard.tick(param.method);
                if (FeatureFlags.removeMetaAI) param.setResult(false);
            }
        };
        int n = hookGate(bridge, classLoader, forceFalse, new Number[]{META_AI_GATE_451_ID_1, META_AI_GATE_451_ID_2});
        if (n == 0) n = hookGate(bridge, classLoader, forceFalse, new Number[]{META_AI_GATE_ID_1, META_AI_GATE_ID_2});
        if (n == 0) n = hookGate(bridge, classLoader, forceFalse, new Number[]{META_AI_GATE_ID_1});
        if (n == 0) n = hookGate(bridge, classLoader, forceFalse, new Number[]{META_AI_GATE_ID_2});
        ModuleLog.line("(IE|RemoveMetaAI) reels-overflow gate: " + n + " method(s)");
    }

    private int hookGate(DexKitBridge bridge, ClassLoader cl, XC_MethodHook hook, Number[] ids) {
        int n = 0;
        try {
            MethodMatcher m = MethodMatcher.create()
                    .returnType("boolean")
                    .paramTypes("com.instagram.common.session.UserSession")
                    .usingNumbers(ids);
            for (MethodData md : DexKitQueryCache.findMethod(bridge, FindMethod.create().matcher(m))) {
                try { XposedBridge.hookMethod(md.getMethodInstance(cl), hook); n++; }
                catch (Throwable ignored) {}
            }
        } catch (Throwable t) {
            ModuleLog.line("(IE|RemoveMetaAI) ⚠️ overflow gate: " + t.getMessage());
        }
        return n;
    }

    /**
     * Drops the Meta AI item ("Ask Meta AI" / GenAI info) from the reel/post overflow (⋮) menus.
     * Those menus are built from lists of com.instagram.feed.media.mediaoption.MediaOption$Option;
     * we hook the list builders (anchored by their referenced enum fields) and remove any option
     * whose enum name is a Meta-AI one. Stable-field anchored — no obfuscated X.* names.
     */
    private void installMenuOptionFilter(DexKitBridge bridge, ClassLoader cl) {
        final String od = "Lcom/instagram/feed/media/mediaoption/MediaOption$Option;";
        XC_MethodHook filter = new XC_MethodHook() {
            @Override protected void afterHookedMethod(MethodHookParam param) { ps.reso.instaeclipse.utils.core.JitGuard.tick(param.method);
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
                    try { ((List<Object>) src).clear(); ((List<Object>) src).addAll(keep); } // mutate in place if possible
                    catch (Throwable mutateFail) { param.setResult(keep); }               // else replace
                } catch (Throwable ignored) {}
            }
        };
        // Any option-list builder that references the GEN_AI_INFO enum field is, by construction, a
        // menu that can contain the Meta AI item — post overflow, reel ⋮, or otherwise. Anchoring on
        // that single stable field (rather than a specific neighbour-field combo) catches every
        // surface across builds; IG 446 moved the reel ⋮ menu to a different builder than the old
        // PLAYBACK_CONTROLS+UNSAVE ArrayList, which is why the narrow reel matcher missed it.
        // Anchor on every Meta-AI menu enum constant: GEN_AI_INFO (post overflow), GEN_AI, and
        // CONTENT_DEEP_DIVE — the reel ⋮ "Ask Meta AI about this" entry (its prompt string is
        // meta_ai_content_deep_dive_prompt_v2), which neither GEN_AI anchor caught.
        for (String constName : new String[]{"GEN_AI_INFO", "GEN_AI", "CONTENT_DEEP_DIVE"}) {
            String field = od + "->" + constName + ":" + od;
            hookBuilder(bridge, cl, filter, FindMethod.create().matcher(MethodMatcher.create()
                    .returnType("java.util.List").addUsingField(field)), "list-" + constName);
            hookBuilder(bridge, cl, filter, FindMethod.create().matcher(MethodMatcher.create()
                    .returnType("java.util.ArrayList").addUsingField(field)), "arraylist-" + constName);
        }
        // Reel options list (ArrayList referencing PLAYBACK_CONTROLS + UNSAVE) — kept for builds
        // where the reel menu builder does not statically reference GEN_AI_INFO.
        hookBuilder(bridge, cl, filter, FindMethod.create().matcher(MethodMatcher.create()
                .returnType("java.util.ArrayList")
                .addUsingField(od + "->PLAYBACK_CONTROLS:" + od)
                .addUsingField(od + "->UNSAVE:" + od)), "reel-options");
    }

    /**
     * The post and reel ⋮ menus also add rows one at a time through MediaOptionsOverflowMenuCreator's
     * static add-button method, which the option lists above never see. Skip a Meta AI row there.
     */
    private void installAddButtonFilter(DexKitBridge bridge, ClassLoader cl) {
        try {
            Class<?> option = cl.loadClass("com.instagram.feed.media.mediaoption.MediaOption$Option");
            Method addButton = DexKitCache.isCacheValid() ? DexKitCache.loadMethod(ADD_BUTTON_CACHE, cl) : null;
            if (addButton == null) {
                addButton = findAddButton(bridge, cl, option);
                if (addButton == null) {
                    ModuleLog.line("(IE|RemoveMetaAI) ⚠️ overflow add-button not found");
                    return;
                }
                DexKitCache.saveMethod(ADD_BUTTON_CACHE, addButton);
            }
            int optionIndex = Arrays.asList(addButton.getParameterTypes()).indexOf(option);
            if (optionIndex < 0) return;
            XposedBridge.hookMethod(addButton, new XC_MethodHook() {
                @Override protected void beforeHookedMethod(MethodHookParam param) { ps.reso.instaeclipse.utils.core.JitGuard.tick(param.method);
                    Object opt = param.args[optionIndex];
                    if (FeatureFlags.removeMetaAI && opt != null && isMetaAiOption(opt.toString())) {
                        param.setResult(null); // void row-adder: the row is never added
                    }
                }
            });
            ModuleLog.line("(IE|RemoveMetaAI) overflow add-button filter: 1 method(s)");
        } catch (Throwable t) {
            ModuleLog.line("(IE|RemoveMetaAI) ⚠️ add-button filter: " + t.getMessage());
        }
    }

    private static Method findAddButton(DexKitBridge bridge, ClassLoader cl, Class<?> option) {
        List<ClassData> creators = bridge.findClass(FindClass.create().matcher(
                ClassMatcher.create().usingStrings("MediaOptionsOverflowMenuCreator")));
        if (creators.isEmpty()) return null;
        for (MethodData md : bridge.findMethod(FindMethod.create().matcher(MethodMatcher.create()
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

    private void hookBuilder(DexKitBridge bridge, ClassLoader cl, XC_MethodHook hook, FindMethod q, String label) {
        try {
            int n = 0;
            for (MethodData md : DexKitQueryCache.findMethod(bridge, q)) {
                try { XposedBridge.hookMethod(md.getMethodInstance(cl), hook); n++; } catch (Throwable ignored) {}
            }
            ModuleLog.line("(IE|RemoveMetaAI) " + label + " filter: " + n + " method(s)");
        } catch (Throwable t) {
            ModuleLog.line("(IE|RemoveMetaAI) ⚠️ " + label + ": " + t.getMessage());
        }
    }

    private static boolean isMetaAiOption(String name) {
        String n = name.toUpperCase();
        return n.contains("GEN_AI") || n.contains("GENAI") || n.contains("META_AI")
                || n.contains("METAAI") || n.contains("ASK_META")
                || n.contains("CONTENT_DEEP_DIVE"); // reel ⋮ "Ask Meta AI about this" (446+)
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

    // ── Reel/post action-sheet "About this reel" Content Deep Dive section (view-level) ──────────

    private static final java.util.Set<View> watchedSheetDecors =
            java.util.Collections.newSetFromMap(new java.util.WeakHashMap<>());
    // Global layout fires many times per second on the feed; a whole-decor walk on each pass is
    // a battery drain (see DistractionFreeUIHook, #257). Sweep at once (so an opening sheet never
    // shows the section), then at most once more per window until SHEET_SWEEP_MS has passed.
    private static final long SHEET_SWEEP_MS = 150;
    private static final android.os.Handler MAIN = new android.os.Handler(android.os.Looper.getMainLooper());
    private static final java.util.Set<View> pendingSheetSweeps =
            java.util.Collections.newSetFromMap(new java.util.WeakHashMap<>());

    /** Arm a one-time global-layout listener that collapses the Content Deep Dive section whenever
     *  a reel/post action sheet is shown. Called from UIHookManager.setupHooks on every resume. */
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
            if (composerId == 0) return;
            java.util.List<View> composers = new java.util.ArrayList<>();
            findViewsById(root, composerId, composers);
            for (View composer : composers) {
                // Only inside an action sheet — never the DM thread composer.
                if (sheetId != 0 && findAncestorById(composer, sheetId) == null) continue;
                View recycler = findAncestorOfClass(composer, "RecyclerView");
                // Already-collapsed views are skipped: collapse() always requests a layout, which
                // would fire this listener again and re-lay out the window every frame.
                if (!(recycler instanceof ViewGroup rv)) { collapseIfShown(composer); continue; }
                // Find the recycler item (direct child of the RecyclerView) that holds the composer.
                View item = composer;
                while (item.getParent() != rv && item.getParent() instanceof View p) item = p;
                if (item.getParent() != rv) { collapseIfShown(composer); continue; }
                int idx = rv.indexOfChild(item);
                collapseIfShown(item); // the "Ask Meta AI…" composer row
                // Collapse the section's header + summary directly above it: contiguous
                // non-clickable, text-bearing items. Stop at the first interactive (action) row.
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

    /** True if the subtree has any clickable/focusable control (i.e. an action row, not text). */
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
