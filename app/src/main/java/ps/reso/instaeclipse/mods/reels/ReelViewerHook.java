package ps.reso.instaeclipse.mods.reels;

import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewTreeObserver;
import android.widget.TextView;

import org.luckypray.dexkit.DexKitBridge;
import org.luckypray.dexkit.query.FindMethod;
import org.luckypray.dexkit.query.enums.StringMatchType;
import org.luckypray.dexkit.query.matchers.MethodMatcher;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;

import ps.reso.instaeclipse.xposedcompat.XC_MethodHook;
import ps.reso.instaeclipse.xposedcompat.XposedBridge;
import ps.reso.instaeclipse.xposedcompat.XposedHelpers;
import ps.reso.instaeclipse.R;
import ps.reso.instaeclipse.utils.core.DexKitCache;
import ps.reso.instaeclipse.utils.core.JitGuard;
import ps.reso.instaeclipse.utils.feature.FeatureFlags;
import ps.reso.instaeclipse.utils.feature.FeatureStatusTracker;
import ps.reso.instaeclipse.utils.i18n.I18n;
import ps.reso.instaeclipse.utils.log.ModuleLog;
import ps.reso.instaeclipse.utils.ui.ViewPager2Input;

/**
 * Counts organic viewer impressions, not preloads; covers cached viewers entered by swiping (#220).
 * Also owns the Reels pager input lock: the DM-reel restriction lock and "Lock Reels scrolling".
 */
public final class ReelViewerHook {
    private static final String CONFIG = "com.instagram.clips.intf.ClipsViewerConfig";
    private static final String SOURCE = "com.instagram.clips.intf.ClipsViewerSource";
    private static final String VIEWPAGER2 = "androidx.viewpager2.widget.ViewPager2";
    private static final String CACHE_PAGER_INPUT = "ReelPagerInput_v1";
    private static final String CACHE_REGISTRARS = "ReelImpressionRegistrars_v2";
    private static final String MISSING = "missing";
    private static final Set<View> blockedRoots = Collections.newSetFromMap(new WeakHashMap<>());
    private static volatile boolean trackingAvailable;
    private static volatile boolean blockingAvailable;
    private final Set<Object> players = Collections.newSetFromMap(new WeakHashMap<>());
    private final Map<Object, ReelViewerSession> sessions = new WeakHashMap<>();
    private final Map<Object, ReelViewerSession> impressionSources = new WeakHashMap<>();
    private final Map<View, ReelViewerSession> viewerRoots = new WeakHashMap<>();
    private final Map<View, ReelPagerGate> reelPagers = new WeakHashMap<>();
    private Method pausePlayer;
    private boolean anyPagerLocked;
    private boolean touchGateInstalled;

    public static boolean isTrackingAvailable() { return trackingAvailable; }
    public static boolean isBlockingAvailable() { return blockingAvailable; }

    public void install(DexKitBridge bridge, ClassLoader loader) {
        installScrollGate(bridge, loader);
        try {
            bindImpressionSources(bridge, loader);
            List<Method> impressions = resolve(bridge, loader, "ReelImpression_v1",
                    "_ClipsViewerOrganicImpressionAction_handleImpression");
            for (Method method : impressions) {
                if (method.getParameterCount() != 2 || method.getReturnType() != void.class) continue;
                XposedBridge.hookMethod(method, new XC_MethodHook() {
                    @Override protected void beforeHookedMethod(MethodHookParam param) {
                        JitGuard.tick(param.method);
                        try {
                            String id = mediaId(param.args[0]);
                            if (id == null) return;
                            ReelViewerSession session = sessionForImpression(param.thisObject);
                            if (session != null) session.select(id);
                            if (blockReason(session) != ReelBlockPolicy.Reason.NONE
                                    || (session != null && session.isDmReel()
                                    && (FeatureFlags.disableReels || FeatureFlags.dailyReelLimitAllowDM
                                    || (NightReelSchedule.isActive() && FeatureFlags.nightReelBlockAllowDM)))) return;
                            int remaining = DailyReelLimit.recordView(id);
                            if (Looper.myLooper() == Looper.getMainLooper()) updatePagers();
                            else new Handler(Looper.getMainLooper()).post(ReelViewerHook.this::updatePagers);
                            if (remaining > 0) {
                                android.content.Context context = ps.reso.instaeclipse.xposedcompat.AndroidAppHelper.currentApplication();
                                ps.reso.instaeclipse.utils.toast.CustomToast.showCustomToast(context,
                                        I18n.t(context, R.string.ig_reel_limit_warning, remaining));
                            }
                        } catch (Throwable t) {
                            ModuleLog.detail("(IE|ReelLimit) cannot read viewed reel: " + t.getClass().getSimpleName());
                        }
                    }
                });
                trackingAvailable = true;
            }

            List<Method> pauses = resolve(bridge, loader, "ReelPause_v1",
                    "ClipsVideoPlayerController.pauseCurrentPlayer pauseReason=");
            for (Method method : pauses) {
                Class<?>[] p = method.getParameterTypes();
                if (p.length == 3 && p[0] == String.class && p[1] == boolean.class
                        && p[2] == boolean.class && !Modifier.isStatic(method.getModifiers())) {
                    pausePlayer = method;
                    break;
                }
            }

            int playbackHooks = 0;
            int itemPlaybackHooks = 0;
            for (String suffix : new String[]{"maybeResumePlayer", "resumeCurrentPlayer",
                    "tryToResumeVideoPlayback", "maybeResumeScrolledInPlayer"}) {
                boolean suppliesItem = suffix.equals("maybeResumePlayer") || suffix.equals("maybeResumeScrolledInPlayer");
                for (Method method : resolve(bridge, loader, "ReelPlayback_" + suffix,
                        "_ClipsVideoPlayerController_" + suffix)) {
                    if (Modifier.isStatic(method.getModifiers()) || method.getReturnType() != void.class) continue;
                    XposedBridge.hookMethod(method, new XC_MethodHook() {
                        @Override protected void beforeHookedMethod(MethodHookParam param) {
                            JitGuard.tick(param.method);
                            try {
                                players.add(param.thisObject);
                                // The outer resume methods resolve the item before calling the
                                // item-bearing hooks. A stale ID here could cut off the final reel.
                                if (!suppliesItem) {
                                    if (FeatureFlags.disableReels && !FeatureFlags.disableReelsExceptDM) param.setResult(null);
                                    return;
                                }
                                ReelViewerSession session = sessionForConfig(fieldOfType(param.thisObject, CONFIG));
                                if (session != null && param.args.length > 0) session.select(mediaId(param.args[0]));
                                if (blockReason(session) != ReelBlockPolicy.Reason.NONE) {
                                    param.setResult(null);
                                }
                            } catch (Throwable t) {
                                ModuleLog.detail("(IE|ReelLimit) playback gate failed: " + t.getClass().getSimpleName());
                            }
                        }
                    });
                    playbackHooks++;
                    if (suppliesItem) itemPlaybackHooks++;
                }
            }

            int viewerHooks = 0;
            for (Method method : resolve(bridge, loader, "ReelViewerCreated_v1", "ClipsViewerFragment.onViewCreated")) {
                Class<?>[] p = method.getParameterTypes();
                if (p.length != 2 || p[0] != View.class || p[1] != Bundle.class) continue;
                XposedBridge.hookMethod(method, new XC_MethodHook() {
                    @Override protected void afterHookedMethod(MethodHookParam param) {
                        JitGuard.tick(param.method);
                        if (!(param.args[0] instanceof ViewGroup)) return;
                        watch((ViewGroup) param.args[0], param.thisObject);
                    }
                });
                viewerHooks++;
            }
            blockingAvailable = viewerHooks > 0 && itemPlaybackHooks > 0 && pausePlayer != null;
            if (blockingAvailable) FeatureStatusTracker.setHooked("NightReelBlock");
            else if (FeatureFlags.nightReelBlockEnabled) FeatureStatusTracker.setBroken("NightReelBlock");
            ModuleLog.line("(IE|ReelLimit) " + (trackingAvailable && blockingAvailable ? "✅ ready"
                    : "❌ Reel limits cannot fully work on this Instagram version ("
                    + (trackingAvailable ? "reels are counted but cannot be blocked"
                    : blockingAvailable ? "reels can be blocked but are not counted" : "reels are not counted or blocked") + ")"));
            ModuleLog.detail("(IE|ReelLimit) impression=" + trackingAvailable + " viewer=" + viewerHooks
                    + " playback=" + playbackHooks + " pause=" + (pausePlayer != null));
        } catch (Throwable t) {
            ModuleLog.error("(IE|ReelLimit) Could not set up reel limits", t);
        }
    }

    /** True when no reel restriction is on and no pager lock or cover is left from an earlier one. */
    private boolean idle() {
        return !(FeatureFlags.disableReels || FeatureFlags.dailyReelLimitEnabled
                || FeatureFlags.nightReelBlockEnabled || FeatureFlags.reelsLockScroll)
                && !anyPagerLocked && blockedRoots.isEmpty();
    }

    /**
     * View overlays do not receive touch events. Consume events at blocked viewer roots so covered
     * like/share controls cannot be triggered, while the app's navigation still works. Every view
     * level of every touch passes through dispatchTouchEvent, so hook it only once a reel
     * restriction is first used.
     */
    private void ensureTouchGate() {
        if (touchGateInstalled) return;
        touchGateInstalled = true;
        try {
            XposedHelpers.findAndHookMethod(ViewGroup.class, "dispatchTouchEvent", MotionEvent.class,
                    new XC_MethodHook() {
                        @Override protected void beforeHookedMethod(MethodHookParam param) {
                            JitGuard.tick(param.method);
                            if (idle()) return;
                            if (viewerRoots.containsKey(param.thisObject)) updatePagers();
                            if (blockedRoots.contains(param.thisObject)) param.setResult(true);
                        }
                    });
        } catch (Throwable t) {
            ModuleLog.warn("(IE|ReelLimit) Buttons on a blocked reel may still respond to taps", t);
        }
    }

    private void watch(ViewGroup root, Object fragment) {
        try {
            ReelViewerSession session = sessionForConfig(viewerConfig(fragment));
            viewerRoots.put(root, session);
            updatePagers();
            TextView cover = new TextView(root.getContext());
            cover.setBackgroundColor(0xFF17171B);
            cover.setTextColor(0xFFFFFFFF);
            cover.setTextSize(17);
            cover.setGravity(Gravity.CENTER);
            int padding = Math.round(24 * root.getResources().getDisplayMetrics().density);
            cover.setPadding(padding, padding, padding, padding);
            // The observer is window-wide: never cancel a draw, and do no work while reels are unrestricted.
            ViewTreeObserver.OnPreDrawListener gate = () -> {
                if (idle()) return true;
                try {
                    ensureTouchGate();
                    updatePagers();
                    ReelBlockPolicy.Reason reason = blockReason(session);
                    if (reason != ReelBlockPolicy.Reason.NONE) {
                        CharSequence message = blockMessage(root.getContext(), reason);
                        boolean changed = !TextUtils.equals(cover.getText(), message);
                        if (changed) cover.setText(message);
                        boolean added = blockedRoots.add(root);
                        if (added) {
                            root.getOverlay().add(cover);
                            pauseBlockedPlayers();
                        }
                        if (added || changed || cover.getWidth() != root.getWidth()
                                || cover.getHeight() != root.getHeight()) {
                            cover.measure(View.MeasureSpec.makeMeasureSpec(root.getWidth(), View.MeasureSpec.EXACTLY),
                                    View.MeasureSpec.makeMeasureSpec(root.getHeight(), View.MeasureSpec.EXACTLY));
                            cover.layout(0, 0, root.getWidth(), root.getHeight());
                        }
                    } else if (blockedRoots.remove(root)) {
                        root.getOverlay().remove(cover);
                    }
                } catch (Throwable t) {
                    ModuleLog.probe("(IE|ReelLimit) viewer gate failed: " + t.getClass().getSimpleName());
                }
                return true;
            };
            // Scope the listener to the viewer's attachment, or every closed viewer keeps running it.
            View.OnAttachStateChangeListener watcher = new View.OnAttachStateChangeListener() {
                private ViewTreeObserver observer;

                @Override public void onViewAttachedToWindow(View v) {
                    observer = v.getViewTreeObserver();
                    observer.addOnPreDrawListener(gate);
                }

                @Override public void onViewDetachedFromWindow(View v) {
                    if (observer != null && observer.isAlive()) observer.removeOnPreDrawListener(gate);
                    observer = null;
                    if (blockedRoots.remove(root)) root.getOverlay().remove(cover);
                }
            };
            root.addOnAttachStateChangeListener(watcher);
            if (root.isAttachedToWindow()) watcher.onViewAttachedToWindow(root);
        } catch (Throwable t) {
            ModuleLog.warn("(IE|ReelLimit) Could not apply reel limits to this reel viewer", t);
        }
    }

    private void pauseBlockedPlayers() {
        if (pausePlayer == null) return;
        for (Object player : new ArrayList<>(players)) {
            try {
                if (pausePlayer.getDeclaringClass().isInstance(player)
                        && blockReason(sessionForConfig(fieldOfType(player, CONFIG)))
                        != ReelBlockPolicy.Reason.NONE) {
                    pausePlayer.invoke(player, "instaeclipse_reels_blocked", false, false);
                }
            } catch (Throwable t) {
                ModuleLog.warn("(IE|ReelLimit) Could not pause a blocked reel: " + t.getClass().getSimpleName());
            }
        }
    }

    private void installScrollGate(DexKitBridge bridge, ClassLoader loader) {
        try {
            Class<?> pagerClass = loader.loadClass(VIEWPAGER2);
            Method setter = null;
            try {
                setter = pagerClass.getDeclaredMethod("setUserInputEnabled", boolean.class);
            } catch (NoSuchMethodException e) {
                ModuleLog.detail("(IE|ReelLimit) setUserInputEnabled inlined; writing the pager input field");
            }
            Field inputField = null;
            if (setter != null) {
                try {
                    inputField = setterInputField(bridge, loader, pagerClass, setter);
                } catch (Throwable t) {
                    ModuleLog.detail("(IE|ReelLimit) pager setter field unresolved; using ViewPager2Input", t);
                }
            }
            final Method enabledSetter = setter;
            final Field setterField = inputField;
            XC_MethodHook rememberPager = new XC_MethodHook() {
                @Override protected void afterHookedMethod(MethodHookParam param) {
                    JitGuard.tick(param.method);
                    Object pager = fieldOfType(param.thisObject, VIEWPAGER2);
                    if (!(pager instanceof View view)) return;
                    if (!reelPagers.containsKey(view)) {
                        // ViewPager2Input needs a live pager, so the fallback resolves here.
                        Field field = setterField != null ? setterField : ViewPager2Input.field(view);
                        if (field == null) return;
                        reelPagers.put(view, new ReelPagerGate(field, enabledSetter));
                    }
                    updatePagers();
                }
            };
            Set<Class<?>> owners = new java.util.HashSet<>();
            for (Method method : resolve(bridge, loader, "ReelPagerView_v1", "ClipsViewPagerImpl_getViewAtIndex")) {
                if (Modifier.isStatic(method.getModifiers())) continue;
                XposedBridge.hookMethod(method, rememberPager);
                if (owners.add(method.getDeclaringClass())) {
                    XposedBridge.hookAllConstructors(method.getDeclaringClass(), rememberPager);
                }
            }
            for (Method method : resolve(bridge, loader, "ReelPagerSetup_v1", "ClipsViewPagerImpl_setupView")) {
                if (owners.contains(method.getDeclaringClass())) XposedBridge.hookMethod(method, rememberPager);
            }
            if (owners.isEmpty()) throw new NoSuchMethodException("Clips pager owner");
            if (setter != null) {
                XposedBridge.hookMethod(setter, new XC_MethodHook() {
                    @Override protected void beforeHookedMethod(MethodHookParam param) {
                        JitGuard.tick(param.method);
                        ReelPagerGate gate = reelPagers.get(param.thisObject);
                        if (gate == null || gate.isUpdating()) return;
                        boolean requested = (boolean) param.args[0];
                        gate.rememberRequest(requested);
                        if (inputLock((View) param.thisObject)) {
                            updatePagers();
                            gate.rememberRequest(requested);
                            param.args[0] = false;
                        }
                    }
                });
            }
            // Input disabling stops swipes/accessibility paging. Also guard explicit page jumps once
            // the opened reel is laid out; initial DM positioning must still be allowed. "Lock Reels
            // scrolling" is not checked here, so IG's own auto-scroll keeps advancing.
            for (Method method : pagerClass.getDeclaredMethods()) {
                Class<?>[] p = method.getParameterTypes();
                if (Modifier.isStatic(method.getModifiers()) || !method.getName().equals("setCurrentItem") || method.getReturnType() != void.class
                        || !(p.length == 1 || (p.length == 2 && p[1] == boolean.class)) || p[0] != int.class) continue;
                XposedBridge.hookMethod(method, new XC_MethodHook() {
                    @Override protected void beforeHookedMethod(MethodHookParam param) {
                        JitGuard.tick(param.method);
                        View pager = (View) param.thisObject;
                        if (!reelPagers.containsKey(pager) || !pager.isLaidOut()) return;
                        ReelViewerSession session = sessionForView(pager);
                        if (!ReelBlockPolicy.shouldLockScrolling(session)) return;
                        if (session.currentMediaId != null
                                && (int) param.args[0] != (int) XposedHelpers.callMethod(pager, "getCurrentItem")) {
                            param.setResult(null);
                        }
                    }
                });
            }
            FeatureStatusTracker.setHooked("reelsLockScroll");
            ModuleLog.detail("(IE|ReelLimit) scroll gate pager owners=" + owners.size()
                    + " setter=" + (setter != null));
        } catch (Throwable t) {
            ModuleLog.error("(IE|ReelLimit) Could not turn on locking the reel scroll (blocked reels are still covered)", t);
        }
        try {
            // Pull-to-refresh on top of a locked pager would still reload the feed.
            Class<?> refresh = XposedHelpers.findClass(
                    "instagram.features.clips.viewer.ui.ClipsSwipeRefreshLayout", loader);
            Method intercept = XposedHelpers.findMethodExact(refresh, "onInterceptTouchEvent", MotionEvent.class);
            // The compat lookup also walks superclasses; an inherited one is shared by every
            // refresh layout (or every ViewGroup), so only the layout's own override is hooked.
            if (intercept.getDeclaringClass() != refresh) {
                throw new NoSuchMethodException("onInterceptTouchEvent is inherited from "
                        + intercept.getDeclaringClass().getName());
            }
            XposedBridge.hookMethod(intercept, new XC_MethodHook() {
                @Override protected void beforeHookedMethod(MethodHookParam param) {
                    JitGuard.tick(param.method);
                    if (inputLock((View) param.thisObject)) {
                        param.setResult(false);
                    }
                }
            });
        } catch (Throwable t) {
            ModuleLog.warn("(IE|ReelLimit) Pull to refresh still works while reels are locked", t);
        }
    }

    /** The getter is obfuscated in Instagram. Resolve the setter's sole boolean write instead. */
    private static Field setterInputField(DexKitBridge bridge, ClassLoader loader, Class<?> pagerClass,
                                          Method setter) throws ReflectiveOperationException {
        String cached = DexKitCache.isCacheValid() ? DexKitCache.loadString(CACHE_PAGER_INPUT) : null;
        if (cached != null) {
            try {
                Field field = pagerClass.getDeclaredField(cached);
                if (field.getType() == boolean.class) {
                    field.setAccessible(true);
                    return field;
                }
            } catch (NoSuchFieldException ignored) {} // stale cache: rediscover below
        }
        Field inputField = null;
        for (var use : bridge.getMethodData(setter).getUsingFields()) {
            Field field = use.getField().getFieldInstance(loader);
            if (!use.getUsingType().isWrite() || field.getType() != boolean.class
                    || field.getDeclaringClass() != pagerClass || Modifier.isStatic(field.getModifiers())) continue;
            if (inputField != null) throw new IllegalStateException("Ambiguous pager input flag");
            inputField = field;
        }
        if (inputField == null) throw new NoSuchFieldException("Pager input flag");
        inputField.setAccessible(true);
        DexKitCache.saveString(CACHE_PAGER_INPUT, inputField.getName());
        return inputField;
    }

    private void updatePagers() {
        boolean locked = false;
        for (View pager : new ArrayList<>(reelPagers.keySet())) {
            ReelPagerGate gate = reelPagers.get(pager);
            if (gate == null) continue;
            try {
                gate.update(pager, inputLock(pager));
            } catch (Throwable t) {
                ModuleLog.detail("(IE|ReelLimit) cannot update pager input", t);
            }
            locked |= gate.isLocked();
        }
        // Lets idle() skip work only once every lock taken earlier has been released.
        anyPagerLocked = locked;
    }

    /** Swipe input: "Lock Reels scrolling" locks every reel pager, on top of the DM-reel lock. */
    private boolean inputLock(View view) {
        return FeatureFlags.reelsLockScroll || ReelBlockPolicy.shouldLockScrolling(sessionForView(view));
    }

    private View viewerRoot(View view) {
        for (View current = view; current != null;
             current = current.getParent() instanceof View ? (View) current.getParent() : null) {
            if (viewerRoots.containsKey(current)) return current;
        }
        return null;
    }

    private ReelViewerSession sessionForView(View view) {
        return viewerRoots.get(viewerRoot(view));
    }

    private static ReelBlockPolicy.Reason blockReason(ReelViewerSession session) {
        return ReelBlockPolicy.playbackReason(session);
    }

    private static CharSequence blockMessage(android.content.Context context, ReelBlockPolicy.Reason reason) {
        if (reason == ReelBlockPolicy.Reason.DISABLE_REELS) {
            return I18n.t(context, R.string.ig_reels_blocked);
        }
        if (reason == ReelBlockPolicy.Reason.NIGHTTIME) {
            java.util.Calendar end = java.util.Calendar.getInstance();
            end.set(java.util.Calendar.HOUR_OF_DAY, FeatureFlags.nightReelBlockEndMinutes / 60);
            end.set(java.util.Calendar.MINUTE, FeatureFlags.nightReelBlockEndMinutes % 60);
            return I18n.t(context, R.string.ig_night_reels_blocked,
                    android.text.format.DateFormat.getTimeFormat(context).format(end.getTime()));
        }
        return I18n.t(context, R.string.ig_reel_limit_reached);
    }

    private ReelViewerSession sessionForConfig(Object config) {
        if (config == null) return null;
        return sessions.computeIfAbsent(config, key -> new ReelViewerSession(isDirectConfig(key)));
    }

    private static String mediaId(Object item) {
        Object media = fieldOfType(item, "com.instagram.feed.media.Media");
        if (media == null) return null;
        Object id = XposedHelpers.callMethod(media, "getId");
        return id instanceof String ? (String) id : null;
    }

    private ReelViewerSession sessionForImpression(Object handler) {
        if (handler == null) return null;
        for (Class<?> c = handler.getClass(); c != null && c != Object.class; c = c.getSuperclass()) {
            for (Field field : c.getDeclaredFields()) {
                if (Modifier.isStatic(field.getModifiers()) || field.getType().isPrimitive()) continue;
                try {
                    field.setAccessible(true);
                    ReelViewerSession session = impressionSources.get(field.get(handler));
                    if (session != null) return session;
                } catch (Throwable ignored) {}
            }
        }
        return null;
    }

    private void bindImpressionSources(DexKitBridge bridge, ClassLoader loader) {
        int bindings = 0;
        try {
            // This registrar receives both the viewer config and the logger source forwarded to
            // organic impression handlers. Match their identity, never the currently visible tab.
            for (Constructor<?> constructor : impressionRegistrars(bridge, loader)) {
                XposedBridge.hookMethod(constructor, new XC_MethodHook() {
                    @Override protected void afterHookedMethod(MethodHookParam param) {
                        JitGuard.tick(param.method);
                        Object config = null;
                        for (Object arg : param.args) {
                            if (arg != null && arg.getClass().getName().equals(CONFIG)) { config = arg; break; }
                        }
                        ReelViewerSession session = sessionForConfig(config);
                        if (session == null) return;
                        for (Object arg : param.args) {
                            if (arg == null) continue;
                            try {
                                if (arg.getClass().getMethod("getModuleName").getReturnType() == String.class) {
                                    impressionSources.put(arg, session);
                                }
                            } catch (NoSuchMethodException ignored) {}
                        }
                    }
                });
                bindings++;
            }
        } catch (Throwable t) {
            ModuleLog.detail("(IE|ReelLimit) DM impression source discovery failed: " + t);
        }
        if (bindings == 0 && FeatureFlags.dailyReelLimitEnabled) {
            ModuleLog.warn("(IE|ReelLimit) Reels opened from chats may count toward the daily limit on this Instagram version");
        }
        ModuleLog.detail("(IE|ReelLimit) DM impression source hooks=" + bindings);
    }

    /** Registrar constructors taking the viewer config. DexKitCache stores Methods only, so cache class names. */
    private static List<Constructor<?>> impressionRegistrars(DexKitBridge bridge, ClassLoader loader)
            throws ReflectiveOperationException {
        List<Constructor<?>> result = new ArrayList<>();
        String cached = DexKitCache.isCacheValid() ? DexKitCache.loadString(CACHE_REGISTRARS) : null;
        if (cached != null) {
            for (String name : cached.split(",")) {
                if (name.isEmpty()) continue;
                for (Constructor<?> constructor : loader.loadClass(name).getDeclaredConstructors()) {
                    if (takesConfig(constructor)) result.add(constructor);
                }
            }
            return result;
        }
        Set<String> classes = new LinkedHashSet<>();
        // Any registrar marker, then the owning class's config-taking constructor: on IG 451 the
        // "_init" marker sits in a class without the config (X.056c), while the registrar that
        // takes it (X.054k) only carries "_addAction".
        for (var data : bridge.findMethod(FindMethod.create().matcher(MethodMatcher.create()
                .addUsingString("ClipsViewerSponsoredViewpointRegistrar_", StringMatchType.Contains)))) {
            String owner = data.getClassName();
            if (classes.contains(owner)) continue;
            boolean found = false;
            for (Constructor<?> constructor : loader.loadClass(owner).getDeclaredConstructors()) {
                if (!takesConfig(constructor)) continue;
                result.add(constructor);
                found = true;
            }
            if (found) classes.add(owner);
        }
        DexKitCache.saveString(CACHE_REGISTRARS, String.join(",", classes));
        return result;
    }

    private static boolean takesConfig(Constructor<?> constructor) {
        for (Class<?> type : constructor.getParameterTypes()) {
            if (type.getName().equals(CONFIG)) return true;
        }
        return false;
    }

    private static Object viewerConfig(Object viewer) {
        // The fragment exposes its config through a getter, not necessarily a Bundle or direct field.
        for (Class<?> c = viewer.getClass(); c != null && c != Object.class; c = c.getSuperclass()) {
            for (Method method : c.getDeclaredMethods()) {
                if (Modifier.isStatic(method.getModifiers()) || method.getParameterCount() != 0
                        || !method.getReturnType().getName().equals(CONFIG)) continue;
                try {
                    method.setAccessible(true);
                    return method.invoke(viewer);
                } catch (Throwable ignored) {}
            }
        }
        return null;
    }

    private static boolean isDirectConfig(Object config) {
        Object source = fieldOfType(config, SOURCE);
        if (!(source instanceof Enum<?>)) return false;
        String name = ((Enum<?>) source).name();
        return name.equals("DIRECT") || name.equals("DIRECT_SEARCH") || name.equals("DIRECT_ICEBREAKER")
                || name.equals("DIRECT_CROSS_APP_SAVES") || name.equals("CUTOUT_ATTRIBUTION_ON_DIRECT_MESSAGE");
    }

    private static Object fieldOfType(Object object, String type) {
        if (object == null) return null;
        if (object.getClass().getName().equals(type)) return object;
        for (Class<?> c = object.getClass(); c != null && c != Object.class; c = c.getSuperclass()) {
            for (Field field : c.getDeclaredFields()) {
                if (Modifier.isStatic(field.getModifiers()) || !field.getType().getName().equals(type)) continue;
                try {
                    field.setAccessible(true);
                    Object value = field.get(object);
                    if (value != null) return value;
                } catch (Throwable ignored) {}
            }
        }
        return null;
    }

    private static List<Method> resolve(DexKitBridge bridge, ClassLoader loader, String key, String anchor) {
        if (DexKitCache.isCacheValid()) {
            List<Method> cached = DexKitCache.loadMethods(key, loader);
            if (cached != null && !cached.isEmpty()) return cached;
            // A builder without this anchor must not pay a DexKit scan on every launch.
            if (MISSING.equals(DexKitCache.loadString(key))) return new ArrayList<>();
        }
        List<Method> result = new ArrayList<>();
        for (var data : bridge.findMethod(FindMethod.create().matcher(MethodMatcher.create()
                .addUsingString(anchor, StringMatchType.Contains)))) {
            try {
                Method method = data.getMethodInstance(loader);
                method.setAccessible(true);
                result.add(method);
            } catch (Throwable ignored) {}
        }
        if (!result.isEmpty()) DexKitCache.saveMethods(key, result);
        else DexKitCache.saveString(key, MISSING);
        return result;
    }
}
