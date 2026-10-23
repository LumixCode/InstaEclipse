package ps.reso.instaeclipse.mods.ui;

import android.animation.Animator;
import android.animation.LayoutTransition;
import android.annotation.SuppressLint;
import android.content.res.Resources;
import android.os.Bundle;
import android.view.Choreographer;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewTreeObserver;

import org.luckypray.dexkit.DexKitBridge;
import org.luckypray.dexkit.query.FindMethod;
import org.luckypray.dexkit.query.matchers.MethodMatcher;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.HashSet;
import java.util.Set;

import ps.reso.instaeclipse.xposedcompat.XC_MethodHook;
import ps.reso.instaeclipse.xposedcompat.XposedHelpers;
import ps.reso.instaeclipse.Xposed.Module;
import ps.reso.instaeclipse.utils.core.DexKitCache;
import ps.reso.instaeclipse.utils.feature.FeatureFlags;
import ps.reso.instaeclipse.utils.feature.FeatureStatusTracker;
import ps.reso.instaeclipse.utils.log.ModuleLog;

/**
 * DM share sheet cleanup, anchored on stable names verified on IG 436 / 446 / 447 / 451:
 *  - Hide "Create group" (#240, FeatureFlags.hideShareCreateGroup): the plain button
 *    {@code direct_private_share_create_group_button} inside
 *    instagram.features.direct.fragment.sharesheet.DirectShareSheetFragment, plus the
 *    {@code new_group_pog_view} and {@code direct_send_to_group_button_vertical} variants. IG re-shows
 *    them when the selection changes, so a pre-draw sweep scoped to the sheet hides them before any
 *    frame shows them. The multi-recipient facepile variants, inflated from layouts
 *    {@code direct_private_share_create_group_facepile_button/_view}, are collapsed as they inflate.
 *    "Send to new group chat (N)" under "Send separately" once two or more chats are selected is
 *    {@code direct_send_to_group_button_vertical/_horizontal} (both ids exist on IG 451), built in
 *    code, so the same sweep hides it. Their parents ({@code direct_multi_select_message_composer},
 *    {@code direct_send_to_group_button_container}) animate layout changes (446 layout; 451's
 *    binder tunes the container's LayoutTransition): a GONE set after IG's VISIBLE was laid out
 *    ended the button's delayed fade-in and drew it fading out for 300 ms.
 *    A listener on that LayoutTransition sees IG's VISIBLE and hides the button in the next frame's
 *    animation phase, before its layout, without the parent's fade or moves.
 *  - Always on: the external share actions ({@code direct_external_reshare_row}) get equal slots and
 *    are vertically centred; on IG 446 "Copy link" sat 42 px lower than its neighbours.
 */
public class ShareSheetCleanupHook {

    private static final String FRAGMENT =
            "instagram.features.direct.fragment.sharesheet.DirectShareSheetFragment";
    private static final String[] LAYOUTS = {
            "direct_private_share_create_group_facepile_button",
            "direct_private_share_create_group_facepile_view",
    };
    private static final String[] CREATE_GROUP_IDS = {
            "direct_private_share_create_group_button",
            "new_group_pog_view",
            "direct_send_to_group_button_vertical",
            "direct_send_to_group_button_horizontal", // IG 451+ (binder sets one of the two)
    };
    private static final String CACHE_REMOVER = "ShareRowDecorationRemover_v1";
    private static final String CACHE_DECORATIONS = "ShareRowDecorationList_v1";

    private static volatile boolean idsResolved;
    private static final int[] createGroupIds = new int[CREATE_GROUP_IDS.length];
    private static int externalShareRowId;
    private static int shareButtonContainerId;
    private static final Set<Integer> layoutIds = new HashSet<>();

    private static final Set<View> watchedShareRows =
            java.util.Collections.newSetFromMap(new java.util.WeakHashMap<>());
    private static final java.util.Map<View, Boolean> shareDecorationsCleared = new java.util.WeakHashMap<>();
    private static Method removeShareDecoration;
    private static Field shareDecorations;

    /** Runs inside IG's setVisibility(VISIBLE) on a child of an animated container. */
    static final LayoutTransition.TransitionListener APPEARING_WATCHER = new LayoutTransition.TransitionListener() {
        @Override public void startTransition(LayoutTransition transition, ViewGroup container, View view, int type) {
            if (type == LayoutTransition.APPEARING && FeatureFlags.hideShareCreateGroup
                    && isCreateGroup(view.getId())) hideBeforeNextLayout(view);
        }

        @Override public void endTransition(LayoutTransition transition, ViewGroup container, View view, int type) {
        }
    };

    public void install(ClassLoader classLoader) {
        resolveShareDecorations(Module.dexKitBridge, classLoader);
        int hooked = 0;
        try {
            XposedHelpers.findAndHookMethod(FRAGMENT, classLoader, "onViewCreated",
                    View.class, Bundle.class, new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) { ps.reso.instaeclipse.utils.core.JitGuard.tick(param.method);
                            if (param.args[0] instanceof View root) watch(root);
                        }
                    });
            hooked++;
        } catch (Throwable t) {
            ModuleLog.warn("(IE|ShareSheet) \"Create group\" may still show in the share sheet: " + t.getClass().getSimpleName() + ": " + t.getMessage());
            ModuleLog.detail("(IE|ShareSheet) onViewCreated hook failed");
        }

        XC_MethodHook inflate = new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam param) { ps.reso.instaeclipse.utils.core.JitGuard.tick(param.method);
                if (!FeatureFlags.hideShareCreateGroup) return;
                try {
                    if (!(param.args[0] instanceof Integer res) || !(param.getResult() instanceof View result)) return;
                    ensureIds(result.getResources(), result.getContext().getPackageName());
                    View target = result;
                    boolean attached = param.args.length >= 3
                            ? Boolean.TRUE.equals(param.args[2]) : param.args[1] != null;
                    // Attached inflation returns the parent; only the new child was inflated.
                    if (attached && param.args[1] != null && result instanceof ViewGroup root
                            && root.getChildCount() > 0) {
                        target = root.getChildAt(root.getChildCount() - 1);
                    }
                    if (layoutIds.contains(res)) collapse(target);
                    else sweep(target); // recycled pogs, before their first draw
                } catch (Throwable ignored) {
                }
            }
        };
        try {
            XposedHelpers.findAndHookMethod(LayoutInflater.class, "inflate",
                    int.class, ViewGroup.class, boolean.class, inflate);
            hooked++;
        } catch (Throwable t) {
            ModuleLog.warn("(IE|ShareSheet) \"Create group\" may still show in the share sheet: " + t.getClass().getSimpleName() + ": " + t.getMessage());
            ModuleLog.detail("(IE|ShareSheet) inflate hook failed");
        }

        if (hooked > 0) FeatureStatusTracker.setHooked("HideShareCreateGroup");
        if (hooked > 0) ModuleLog.ok("(IE|ShareSheet) ready");
        else ModuleLog.error("(IE|ShareSheet) Hiding \"Create group\" will not work: setup failed");
        ModuleLog.detail("(IE|ShareSheet) installed (" + hooked + " hooks)");
    }

    private static void watch(View root) {
        try {
            ensureIds(root.getResources(), root.getContext().getPackageName());
            // Pre-draw, not global layout: IG re-shows Create group without a layout pass we could
            // catch in time. The observer is window-wide, so it must never cancel a draw (a
            // cancelling one froze IG on the Reels comment share sheet), and it lives only while
            // the sheet is attached.
            ViewTreeObserver.OnPreDrawListener listener = () -> {
                cleanUp(root);
                return true;
            };
            View.OnAttachStateChangeListener watcher = new View.OnAttachStateChangeListener() {
                private ViewTreeObserver observer;

                @Override public void onViewAttachedToWindow(View v) {
                    observer = v.getViewTreeObserver();
                    observer.addOnPreDrawListener(listener);
                }

                @Override public void onViewDetachedFromWindow(View v) {
                    if (observer != null && observer.isAlive()) observer.removeOnPreDrawListener(listener);
                    observer = null;
                }
            };
            root.addOnAttachStateChangeListener(watcher);
            if (root.isAttachedToWindow()) watcher.onViewAttachedToWindow(root);
            cleanUp(root);
        } catch (Throwable ignored) {
        }
    }

    private static void cleanUp(View root) {
        try {
            if (FeatureFlags.hideShareCreateGroup) sweep(root);
            alignActionButtons(root, externalShareRowId, shareButtonContainerId);
        } catch (Throwable ignored) {
        }
    }

    private static void sweep(View root) {
        java.util.ArrayDeque<View> stack = new java.util.ArrayDeque<>();
        stack.push(root);
        while (!stack.isEmpty()) {
            View v = stack.pop();
            if (isCreateGroup(v.getId())) {
                watchAppearing(v);
                // ConstraintLayout child: GONE only (width/height 0 would mean "match constraints").
                // Fallback for a VISIBLE the watcher missed: in pre-draw it is already laid out.
                if (v.getVisibility() != View.GONE) {
                    hideWithoutTransition(v);
                    ModuleLog.probe("(IE|ShareSheet) create group hidden by sweep");
                }
            }
            if (v instanceof ViewGroup g)
                for (int i = 0; i < g.getChildCount(); i++) stack.push(g.getChildAt(i));
        }
    }

    /** Hears IG show the button while its layout is still pending (once per container). */
    static void watchAppearing(View button) {
        if (!(button.getParent() instanceof ViewGroup parent)) return;
        LayoutTransition transition = parent.getLayoutTransition();
        if (transition == null) return;
        java.util.List<LayoutTransition.TransitionListener> listeners = transition.getTransitionListeners();
        if (listeners != null && listeners.contains(APPEARING_WATCHER)) return;
        transition.addTransitionListener(APPEARING_WATCHER);
        ModuleLog.probe("(IE|ShareSheet) watching create group container");
    }

    /**
     * Still inside IG's setVisibility: a GONE here would re-enter it, and a plain post runs only
     * after the next traversal. Frame callbacks run in the animation phase, before that frame's
     * layout, so the button never takes space or draws.
     */
    static void hideBeforeNextLayout(View button) {
        Choreographer.getInstance().postFrameCallback(frameTimeNanos -> {
            if (!FeatureFlags.hideShareCreateGroup || button.getVisibility() == View.GONE) return;
            hideWithoutTransition(button);
            ModuleLog.probe("(IE|ShareSheet) create group hidden before layout");
        });
    }

    /**
     * GONE without the parent's LayoutTransition. Its DISAPPEARING type would end the button's
     * pending fade-in (alpha 1) and keep drawing it while it fades out; its CHANGE_DISAPPEARING
     * type cancels the neighbours' queued moves, and with no animator it sets up no new ones
     * (the default ones slide them back after a 300 ms delay). Both are restored right after.
     */
    static void hideWithoutTransition(View button) {
        LayoutTransition transition = button.getParent() instanceof ViewGroup parent
                ? parent.getLayoutTransition() : null;
        if (transition == null) {
            button.setVisibility(View.GONE);
            return;
        }
        boolean fade = transition.isTransitionTypeEnabled(LayoutTransition.DISAPPEARING);
        boolean move = transition.isTransitionTypeEnabled(LayoutTransition.CHANGE_DISAPPEARING);
        Animator moveAnimator = transition.getAnimator(LayoutTransition.CHANGE_DISAPPEARING);
        if (fade) transition.disableTransitionType(LayoutTransition.DISAPPEARING);
        transition.setAnimator(LayoutTransition.CHANGE_DISAPPEARING, null);
        if (!move) transition.enableTransitionType(LayoutTransition.CHANGE_DISAPPEARING);
        try {
            button.setVisibility(View.GONE);
        } finally {
            if (!move) transition.disableTransitionType(LayoutTransition.CHANGE_DISAPPEARING);
            transition.setAnimator(LayoutTransition.CHANGE_DISAPPEARING, moveAnimator);
            if (fade) transition.enableTransitionType(LayoutTransition.DISAPPEARING);
        }
    }

    private static boolean isCreateGroup(int id) {
        if (id == View.NO_ID || id == 0) return false;
        for (int target : createGroupIds) if (id == target) return true;
        return false;
    }

    @SuppressLint("DiscouragedApi")
    private static void ensureIds(Resources res, String pkg) {
        if (idsResolved) return;
        for (int i = 0; i < CREATE_GROUP_IDS.length; i++)
            createGroupIds[i] = res.getIdentifier(CREATE_GROUP_IDS[i], "id", pkg);
        externalShareRowId = res.getIdentifier("direct_external_reshare_row", "id", pkg);
        shareButtonContainerId = res.getIdentifier("button_container", "id", pkg);
        for (String name : LAYOUTS) {
            int id = res.getIdentifier(name, "layout", pkg);
            if (id != 0) layoutIds.add(id);
        }
        idsResolved = true;
    }

    private static void collapse(View v) {
        v.setVisibility(View.GONE);
        ViewGroup.LayoutParams lp = v.getLayoutParams();
        if (lp != null) {
            lp.width = 0;
            lp.height = 0;
            v.setLayoutParams(lp);
        }
    }

    /** Keep external share actions evenly spaced and vertically centered, including recycled items. */
    static void alignActionButtons(View root, int rowId, int buttonId) {
        if (root == null || rowId == 0 || buttonId == 0) return;
        java.util.ArrayDeque<View> stack = new java.util.ArrayDeque<>();
        stack.push(root);
        while (!stack.isEmpty()) {
            View view = stack.pop();
            if (!(view instanceof ViewGroup group)) continue;
            if (view.getId() == rowId) {
                layoutShareItems(group, buttonId);
                if (watchedShareRows.add(group)) {
                    // Scrolling attaches recycled buttons without a global-layout event. Align
                    // this small row before drawing, and release its observer when dismissed.
                    // The observer is window-wide, so it must never cancel a draw: the Reels
                    // comment share sheet never lays the resized items out, which froze the
                    // whole of Instagram until the user scrolled.
                    View.OnAttachStateChangeListener watcher = new View.OnAttachStateChangeListener() {
                        private ViewTreeObserver observer;
                        private final ViewTreeObserver.OnPreDrawListener listener =
                                alignBeforeDraw(group, buttonId);
                        @Override public void onViewAttachedToWindow(View v) {
                            observer = v.getViewTreeObserver();
                            observer.addOnPreDrawListener(listener);
                        }
                        @Override public void onViewDetachedFromWindow(View v) {
                            if (observer != null && observer.isAlive()) observer.removeOnPreDrawListener(listener);
                            observer = null;
                        }
                    };
                    group.addOnAttachStateChangeListener(watcher);
                    if (group.isAttachedToWindow()) watcher.onViewAttachedToWindow(group);
                }
            } else {
                for (int i = 0; i < group.getChildCount(); i++) stack.push(group.getChildAt(i));
            }
        }
    }

    static ViewTreeObserver.OnPreDrawListener alignBeforeDraw(ViewGroup row, int buttonId) {
        return () -> {
            layoutShareItems(row, buttonId);
            return true;
        };
    }

    private static void layoutShareItems(ViewGroup group, int buttonId) {
        if (!clearShareDecorations(group)) return;
        int availableWidth = group.getWidth() - group.getPaddingLeft() - group.getPaddingRight();
        if (availableWidth <= 0) return;
        int minimumSlot = Math.round(80 * group.getResources().getDisplayMetrics().density);
        int slots = Math.max(1, Math.min(5, availableWidth / Math.max(1, minimumSlot)));
        int width = availableWidth / slots;
        int height = group.getHeight() - group.getPaddingTop() - group.getPaddingBottom();
        for (int i = 0; i < group.getChildCount(); i++) {
            View item = group.getChildAt(i);
            if (item.getVisibility() != View.VISIBLE || item.findViewById(buttonId) == null) continue;
            ViewGroup.LayoutParams params = item.getLayoutParams();
            if (params == null) continue;
            boolean resize = params.width != width;
            params.width = width;
            // Native label-sized items and their margins give Copy link a different gap. The
            // equal slots include the spacing, so keep only the row's existing outside padding.
            if (params instanceof ViewGroup.MarginLayoutParams margins) {
                resize |= margins.leftMargin != 0 || margins.rightMargin != 0
                        || margins.getMarginStart() != 0 || margins.getMarginEnd() != 0;
                margins.leftMargin = margins.rightMargin = 0;
                margins.setMarginStart(0);
                margins.setMarginEnd(0);
            }
            if (resize) item.setLayoutParams(params);
            if (item.getHeight() <= 0 || item.getHeight() > height) continue;
            // The host and module have separate RecyclerView classes. Framework Views work in
            // both; deriving the offset from layout bounds also prevents accumulated shifts.
            float offset = group.getPaddingTop() + (height - item.getHeight()) / 2f - item.getTop();
            if (Math.abs(item.getTranslationY() - offset) > .5f) item.setTranslationY(offset);
        }
    }

    private static boolean clearShareDecorations(ViewGroup row) {
        Method remover = removeShareDecoration;
        Field decorations = shareDecorations;
        if (remover == null || decorations == null || !remover.getDeclaringClass().isInstance(row)) return true;
        Boolean complete = shareDecorationsCleared.get(row);
        if (complete != null) return complete;
        shareDecorationsCleared.put(row, false);
        // Only the matched external share row reaches here. Post once so native RecyclerView
        // layout/scroll callbacks finish before its decoration-removal assertion is evaluated.
        row.post(() -> {
            try {
                java.util.List<?> current = (java.util.List<?>) decorations.get(row);
                for (Object decoration : new java.util.ArrayList<>(current)) remover.invoke(row, decoration);
            } catch (Throwable t) {
                ModuleLog.detail("(IE|ShareSheet) Cannot remove share-row spacing", t);
            } finally {
                // A host change must not repeatedly enqueue cleanup.
                shareDecorationsCleared.put(row, true);
                row.requestLayout();
            }
        });
        return false;
    }

    private static void resolveShareDecorations(DexKitBridge bridge, ClassLoader loader) {
        try {
            Method remover = DexKitCache.isCacheValid() ? DexKitCache.loadMethod(CACHE_REMOVER, loader) : null;
            String listName = DexKitCache.isCacheValid() ? DexKitCache.loadString(CACHE_DECORATIONS) : null;
            Field decorations = null;
            if (remover != null && listName != null) {
                try {
                    decorations = remover.getDeclaringClass().getDeclaredField(listName);
                } catch (NoSuchFieldException ignored) {} // stale cache: rediscover below
            }
            if (decorations == null) {
                if (bridge == null) throw new IllegalStateException("DexKit unavailable");
                var matches = bridge.findMethod(FindMethod.create().matcher(MethodMatcher.create()
                        .declaredClass("androidx.recyclerview.widget.RecyclerView")
                        .usingStrings("Cannot remove item decoration during a scroll  or layout")
                        .paramCount(1).returnType(void.class)));
                if (matches.size() != 1) throw new IllegalStateException("Ambiguous decoration remover");
                var data = matches.get(0);
                remover = data.getMethodInstance(loader);
                // Both the remover and its list are obfuscated. Resolve the list used by the native
                // remover so its normal inset invalidation and layout bookkeeping still run.
                for (var use : data.getUsingFields()) {
                    Field field = use.getField().getFieldInstance(loader);
                    if (Modifier.isStatic(field.getModifiers())
                            || field.getDeclaringClass() != remover.getDeclaringClass()
                            || !java.util.List.class.isAssignableFrom(field.getType())) continue;
                    if (decorations != null && !decorations.equals(field)) {
                        throw new IllegalStateException("Ambiguous decoration list");
                    }
                    decorations = field;
                }
                if (decorations == null) throw new NoSuchFieldException("Decoration list");
                DexKitCache.saveMethod(CACHE_REMOVER, remover);
                DexKitCache.saveString(CACHE_DECORATIONS, decorations.getName());
            }
            remover.setAccessible(true);
            decorations.setAccessible(true);
            shareDecorations = decorations;
            removeShareDecoration = remover;
        } catch (Throwable t) {
            ModuleLog.detail("(IE|ShareSheet) Share-row spacing cleanup unavailable", t);
        }
    }
}
