package ps.reso.instaeclipse.mods.reels;

import android.app.Activity;
import android.content.Context;
import android.content.ContextWrapper;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewTreeObserver;

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
import java.util.concurrent.ConcurrentHashMap;

import ps.reso.instaeclipse.xposedcompat.XC_MethodHook;
import ps.reso.instaeclipse.xposedcompat.XposedBridge;
import ps.reso.instaeclipse.xposedcompat.XposedHelpers;
import ps.reso.instaeclipse.R;
import ps.reso.instaeclipse.utils.core.DexKitCache;
import ps.reso.instaeclipse.utils.core.DexKitQueryCache;
import ps.reso.instaeclipse.utils.core.JitGuard;
import ps.reso.instaeclipse.utils.core.SafeReflect;
import ps.reso.instaeclipse.utils.feature.FeatureFlags;
import ps.reso.instaeclipse.utils.feature.FeatureStatusTracker;
import ps.reso.instaeclipse.utils.i18n.I18n;
import ps.reso.instaeclipse.utils.log.ModuleLog;
import ps.reso.instaeclipse.utils.ui.ResIds;
import ps.reso.instaeclipse.utils.ui.ViewPager2Input;

/**
 * Counts organic viewer impressions, not preloads; covers cached viewers entered by swiping (#220).
 * Also owns the Reels pager input lock: the DM-reel restriction lock and "Lock Reels scrolling".
 */
public final class ReelViewerHook {
    private static final String CONFIG = "com.instagram.clips.intf.ClipsViewerConfig";
    private static final String SOURCE = "com.instagram.clips.intf.ClipsViewerSource";
    private static final String MEDIA = "com.instagram.feed.media.Media";
    private static final String VIEWPAGER2 = "androidx.viewpager2.widget.ViewPager2";
    private static final String PAGER_ID = "clips_viewer_view_pager";
    private static final String CACHE_REGISTRARS = "ReelImpressionRegistrars_v2";
    private static final String MISSING = "missing";
    private static final FieldCache CONFIG_FIELDS = new FieldCache(CONFIG);
    private static final FieldCache SOURCE_FIELDS = new FieldCache(SOURCE);
    private static final FieldCache MEDIA_FIELDS = new FieldCache(MEDIA);
    private static final FieldCache HANDLER_FIELDS = new FieldCache(null);
    private static final Map<Class<?>, Method> MEDIA_IDS = new ConcurrentHashMap<>();
    private static final Map<View, ReelBlockCard> blockedRoots = new WeakHashMap<>();
    private static volatile int viewsRecorded;
    private final Set<Object> players = Collections.newSetFromMap(new WeakHashMap<>());
    private final ReelSessions sessions = new ReelSessions();
    private final Map<View, ReelPagerGate> reelPagers = new WeakHashMap<>();
    private Method pausePlayer;
    private Class<?> pagerClass;
    private Method inputSetter;
    private Field inputField;
    private boolean anyPagerLocked;
    private boolean touchGateInstalled;

    public void install(ClassLoader loader) {
        installScrollGate(loader);
        try {
            bindImpressionSources(loader);
            boolean trackingAvailable = false;
            List<Method> impressions = resolve(loader, "ReelImpression_v1",
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
                            viewsRecorded++;
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

            List<Method> pauses = resolve(loader, "ReelPause_v1",
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
            for (String suffix : new String[]{"maybeResumePlayer", "resumeCurrentPlayer", "tryToResumeVideoPlayback"}) {
                boolean suppliesItem = suffix.equals("maybeResumePlayer");
                for (Method method : resolve(loader, "ReelPlayback_" + suffix,
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
                                ReelViewerSession session = sessionForConfig(CONFIG_FIELDS.value(param.thisObject));
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
            for (Method method : resolve(loader, "ReelViewerCreated_v1", "ClipsViewerFragment.onViewCreated")) {
                Class<?>[] p = method.getParameterTypes();
                if (p.length != 2 || p[0] != View.class || p[1] != Bundle.class) continue;
                XposedBridge.hookMethod(method, new XC_MethodHook() {
                    @Override protected void afterHookedMethod(MethodHookParam param) {
                        JitGuard.tick(param.method);
                        if (!(param.args[0] instanceof ViewGroup root)) return;
                        registerPager(root);
                        watch(root, param.thisObject);
                    }
                });
                viewerHooks++;
            }
            if (viewerHooks > 0 && pagerClass != null && inputField != null) {
                FeatureStatusTracker.setHooked("reelsLockScroll");
            }
            boolean blockingAvailable = viewerHooks > 0 && itemPlaybackHooks > 0 && pausePlayer != null;
            if (blockingAvailable) FeatureStatusTracker.setHooked("NightReelBlock");
            else if (FeatureFlags.nightReelBlockEnabled) FeatureStatusTracker.setBroken("NightReelBlock");
            if (trackingAvailable && blockingAvailable) FeatureStatusTracker.setHooked("DailyReelLimit");
            else if (FeatureFlags.dailyReelLimitEnabled) FeatureStatusTracker.setBroken("DailyReelLimit");
            ModuleLog.line("(IE|ReelLimit) " + (trackingAvailable && blockingAvailable ? "✅ ready"
                    : "❌ Reel limits cannot fully work on this Instagram version ("
                    + (trackingAvailable ? "reels are counted but cannot be blocked"
                    : blockingAvailable ? "reels can be blocked but are not counted" : "reels are not counted or blocked") + ")"));
            ModuleLog.detail("(IE|ReelLimit) impression=" + trackingAvailable + " viewer=" + viewerHooks
                    + " playback=" + playbackHooks + " pause=" + (pausePlayer != null));
        } catch (Throwable t) {
            ModuleLog.error("(IE|ReelLimit) Could not set up reel limits", t);
        }
        if (FeatureFlags.disableReels || FeatureFlags.dailyReelLimitEnabled || FeatureFlags.nightReelBlockEnabled) {
            ensureTouchGate();
        }
    }

    /** True when no reel restriction is on and no pager lock or cover is left from an earlier one. */
    private boolean idle() {
        return !(FeatureFlags.disableReels || FeatureFlags.dailyReelLimitEnabled
                || FeatureFlags.nightReelBlockEnabled || FeatureFlags.reelsLockScroll)
                && !anyPagerLocked && blockedRoots.isEmpty();
    }

    private void ensureTouchGate() {
        if (touchGateInstalled) return;
        touchGateInstalled = true;
        try {
            XposedHelpers.findAndHookMethod(ViewGroup.class, "dispatchTouchEvent", MotionEvent.class,
                    new XC_MethodHook() {
                        @Override protected void beforeHookedMethod(MethodHookParam param) {
                            JitGuard.tick(param.method);
                            if (idle()) return;
                            if (sessions.isRoot(param.thisObject)) updatePagers();
                            ReelBlockCard card = blockedRoots.get(param.thisObject);
                            if (card == null) return;
                            View root = (View) param.thisObject;
                            card.touch((MotionEvent) param.args[0], () -> goBack(root));
                            param.setResult(true);
                        }
                    });
        } catch (Throwable t) {
            ModuleLog.warn("(IE|ReelLimit) Buttons on a blocked reel may still respond to taps", t);
        }
    }

    private static void goBack(View root) {
        for (Context c = root.getContext(); c instanceof ContextWrapper w; c = w.getBaseContext()) {
            if (c instanceof Activity activity) {
                root.post(activity::onBackPressed);
                return;
            }
        }
    }

    private record GateInputs(boolean disabled, boolean disabledAllowsDm, boolean night, int nightStart,
                              int nightEnd, boolean nightAllowsDm, boolean daily, int limit, boolean dailyAllowsDm,
                              boolean lockScroll, boolean dmReel, String mediaId, int views) {}

    private static GateInputs inputs(ReelViewerSession session) {
        return new GateInputs(FeatureFlags.disableReels, FeatureFlags.disableReelsExceptDM,
                FeatureFlags.nightReelBlockEnabled, FeatureFlags.nightReelBlockStartMinutes,
                FeatureFlags.nightReelBlockEndMinutes, FeatureFlags.nightReelBlockAllowDM,
                FeatureFlags.dailyReelLimitEnabled, FeatureFlags.dailyReelLimit, FeatureFlags.dailyReelLimitAllowDM,
                FeatureFlags.reelsLockScroll, session != null && session.isDmReel(),
                session == null ? null : session.currentMediaId, viewsRecorded);
    }

    private void watch(ViewGroup root, Object fragment) {
        try {
            ReelViewerSession session = sessionForConfig(viewerConfig(fragment));
            sessions.bindRoot(root, session);
            updatePagers();
            ReelGateCache cache = new ReelGateCache(System::currentTimeMillis);
            ViewTreeObserver.OnPreDrawListener gate = () -> {
                if (idle()) return true;
                try {
                    ensureTouchGate();
                    GateInputs inputs = inputs(session);
                    if (cache.expired(inputs)) {
                        ReelBlockPolicy.Reason reason = cache.reason(inputs, () -> blockReason(session));
                        updatePagers();
                        if (reason != ReelBlockPolicy.Reason.NONE) block(root, reason);
                        else unblock(root);
                    }
                    ReelBlockCard card = blockedRoots.get(root);
                    if (card != null) card.fit(root.getWidth(), root.getHeight());
                } catch (Throwable t) {
                    ModuleLog.probe("(IE|ReelLimit) viewer gate failed: " + t.getClass().getSimpleName());
                }
                return true;
            };
            View.OnAttachStateChangeListener watcher = new View.OnAttachStateChangeListener() {
                private ViewTreeObserver observer;

                @Override public void onViewAttachedToWindow(View v) {
                    observer = v.getViewTreeObserver();
                    observer.addOnPreDrawListener(gate);
                }

                @Override public void onViewDetachedFromWindow(View v) {
                    if (observer != null && observer.isAlive()) observer.removeOnPreDrawListener(gate);
                    observer = null;
                    unblock(root);
                    cache.clear();
                }
            };
            root.addOnAttachStateChangeListener(watcher);
            if (root.isAttachedToWindow()) watcher.onViewAttachedToWindow(root);
        } catch (Throwable t) {
            ModuleLog.warn("(IE|ReelLimit) Could not apply reel limits to this reel viewer", t);
        }
    }

    private void block(ViewGroup root, ReelBlockPolicy.Reason reason) {
        ReelBlockCard card = blockedRoots.get(root);
        boolean added = card == null;
        if (added) card = new ReelBlockCard(root.getContext());
        card.show(reason, FeatureFlags.nightReelBlockEndMinutes, DailyReelLimit.watchedToday(),
                DailyReelLimit.validLimit(FeatureFlags.dailyReelLimit));
        if (!added) return;
        blockedRoots.put(root, card);
        root.getOverlay().add(card.view());
        pauseBlockedPlayers();
    }

    private static void unblock(ViewGroup root) {
        ReelBlockCard card = blockedRoots.remove(root);
        if (card != null) root.getOverlay().remove(card.view());
    }

    private void pauseBlockedPlayers() {
        if (pausePlayer == null) return;
        for (Object player : new ArrayList<>(players)) {
            try {
                if (pausePlayer.getDeclaringClass().isInstance(player)
                        && blockReason(sessionForConfig(CONFIG_FIELDS.value(player)))
                        != ReelBlockPolicy.Reason.NONE) {
                    pausePlayer.invoke(player, "instaeclipse_reels_blocked", false, false);
                }
            } catch (Throwable t) {
                ModuleLog.warn("(IE|ReelLimit) Could not pause a blocked reel: " + t.getClass().getSimpleName());
            }
        }
    }

    private void installScrollGate(ClassLoader loader) {
        try {
            Class<?> pager = loader.loadClass(VIEWPAGER2);
            Method setter = null;
            try {
                setter = pager.getDeclaredMethod("setUserInputEnabled", boolean.class);
            } catch (NoSuchMethodException e) {
                ModuleLog.detail("(IE|ReelLimit) setUserInputEnabled inlined; writing the pager input field");
            }
            if (setter != null) {
                try {
                    inputField = ViewPager2Input.setterInputField(loader, pager, setter);
                } catch (Throwable t) {
                    ModuleLog.detail("(IE|ReelLimit) pager setter field unresolved; using ViewPager2Input", t);
                }
                XposedBridge.hookMethod(setter, new XC_MethodHook() {
                    @Override protected void beforeHookedMethod(MethodHookParam param) {
                        JitGuard.tick(param.method);
                        if (idle()) return;
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
            inputSetter = setter;
            // Input disabling stops swipes/accessibility paging. Also guard explicit page jumps once
            // the opened reel is laid out; initial DM positioning must still be allowed. "Lock Reels
            // scrolling" is not checked here, so IG's own auto-scroll keeps advancing.
            for (Method method : PagerJumpFilter.jumps(pager)) {
                XposedBridge.hookMethod(method, new XC_MethodHook() {
                    @Override protected void beforeHookedMethod(MethodHookParam param) {
                        JitGuard.tick(param.method);
                        if (idle()) return;
                        View view = (View) param.thisObject;
                        if (!reelPagers.containsKey(view) || !view.isLaidOut()) return;
                        ReelViewerSession session = sessionForView(view);
                        if (!ReelBlockPolicy.shouldLockScrolling(session)) return;
                        if (session.currentMediaId != null
                                && (int) param.args[0] != (int) XposedHelpers.callMethod(view, "getCurrentItem")) {
                            param.setResult(null);
                        }
                    }
                });
            }
            pagerClass = pager;
            ModuleLog.detail("(IE|ReelLimit) scroll gate setter=" + (setter != null)
                    + " input=" + (inputField != null));
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
                    if (idle()) return;
                    if (inputLock((View) param.thisObject)) {
                        param.setResult(false);
                    }
                }
            });
        } catch (Throwable t) {
            ModuleLog.warn("(IE|ReelLimit) Pull to refresh still works while reels are locked", t);
        }
    }

    private void registerPager(ViewGroup root) {
        if (pagerClass == null) return;
        try {
            int id = ResIds.id(root.getContext(), PAGER_ID);
            View pager = id == 0 ? null : root.findViewById(id);
            if (!pagerClass.isInstance(pager) || reelPagers.containsKey(pager)) return;
            Field field = inputField != null ? inputField : ViewPager2Input.field(pager);
            if (field == null) return;
            reelPagers.put(pager, new ReelPagerGate(field, inputSetter));
            FeatureStatusTracker.setHooked("reelsLockScroll");
        } catch (Throwable t) {
            ModuleLog.detail("(IE|ReelLimit) cannot register the reel pager", t);
        }
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

    private ReelViewerSession sessionForView(View view) {
        for (View current = view; current != null;
             current = current.getParent() instanceof View ? (View) current.getParent() : null) {
            if (sessions.isRoot(current)) return sessions.forRoot(current);
        }
        return null;
    }

    private static ReelBlockPolicy.Reason blockReason(ReelViewerSession session) {
        return ReelBlockPolicy.playbackReason(session);
    }

    private ReelViewerSession sessionForConfig(Object config) {
        return sessions.forConfig(config, ReelViewerHook::isDirectConfig);
    }

    private static String mediaId(Object item) throws ReflectiveOperationException {
        Object media = MEDIA_FIELDS.value(item);
        if (media == null) return null;
        Method getId = MEDIA_IDS.get(media.getClass());
        if (getId == null) {
            getId = media.getClass().getMethod("getId");
            getId.setAccessible(true);
            MEDIA_IDS.put(media.getClass(), getId);
        }
        Object id = getId.invoke(media);
        return id instanceof String ? (String) id : null;
    }

    private ReelViewerSession sessionForImpression(Object handler) {
        if (handler == null) return null;
        for (Field field : HANDLER_FIELDS.fields(handler.getClass())) {
            try {
                ReelViewerSession session = sessions.forSource(field.get(handler));
                if (session != null) return session;
            } catch (Throwable ignored) {}
        }
        return null;
    }

    private void bindImpressionSources(ClassLoader loader) {
        int bindings = 0;
        try {
            // This registrar receives both the viewer config and the logger source forwarded to
            // organic impression handlers. Match their identity, never the currently visible tab.
            for (Constructor<?> constructor : impressionRegistrars(loader)) {
                Class<?>[] types = constructor.getParameterTypes();
                int configIndex = configParam(types);
                int[] sourceIndexes = ReelSessions.sourceParams(types);
                if (configIndex < 0 || sourceIndexes.length == 0) continue;
                XposedBridge.hookMethod(constructor, new XC_MethodHook() {
                    @Override protected void afterHookedMethod(MethodHookParam param) {
                        JitGuard.tick(param.method);
                        ReelViewerSession session = sessionForConfig(param.args[configIndex]);
                        if (session == null) return;
                        for (int index : sourceIndexes) sessions.bindSource(param.args[index], session);
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
    private static List<Constructor<?>> impressionRegistrars(ClassLoader loader)
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
        for (var data : DexKitQueryCache.methods(FindMethod.create().matcher(MethodMatcher.create()
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
        return configParam(constructor.getParameterTypes()) >= 0;
    }

    private static int configParam(Class<?>[] types) {
        for (int i = 0; i < types.length; i++) {
            if (types[i].getName().equals(CONFIG)) return i;
        }
        return -1;
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
        Object source = SOURCE_FIELDS.value(config);
        if (!(source instanceof Enum<?>)) return false;
        String name = ((Enum<?>) source).name();
        return name.equals("DIRECT") || name.equals("DIRECT_SEARCH") || name.equals("DIRECT_ICEBREAKER")
                || name.equals("DIRECT_CROSS_APP_SAVES") || name.equals("CUTOUT_ATTRIBUTION_ON_DIRECT_MESSAGE");
    }

    private static List<Method> resolve(ClassLoader loader, String key, String anchor) {
        if (DexKitCache.isCacheValid()) {
            List<Method> cached = DexKitCache.loadMethods(key, loader);
            if (cached != null && !cached.isEmpty()) return cached;
            // A builder without this anchor must not pay a DexKit scan on every launch.
            if (MISSING.equals(DexKitCache.loadString(key))) return new ArrayList<>();
        }
        List<Method> result = new ArrayList<>();
        for (var data : DexKitQueryCache.methods(FindMethod.create().matcher(MethodMatcher.create()
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

    private static final class FieldCache {
        private static final Field[] NONE = new Field[0];
        private final String type;
        private final Map<Class<?>, Field[]> byClass = new ConcurrentHashMap<>();

        FieldCache(String type) {
            this.type = type;
        }

        Field[] fields(Class<?> owner) {
            Field[] cached = byClass.get(owner);
            if (cached != null) return cached;
            List<Field> found = new ArrayList<>();
            for (Class<?> c = owner; c != null && c != Object.class; c = c.getSuperclass()) {
                for (Field field : SafeReflect.fields(c)) {
                    if (Modifier.isStatic(field.getModifiers()) || field.getType().isPrimitive()
                            || (type != null && !field.getType().getName().equals(type))) continue;
                    try {
                        field.setAccessible(true);
                        found.add(field);
                    } catch (Throwable ignored) {}
                }
            }
            Field[] fields = found.toArray(NONE);
            byClass.put(owner, fields);
            return fields;
        }

        Object value(Object object) {
            if (object == null) return null;
            if (object.getClass().getName().equals(type)) return object;
            for (Field field : fields(object.getClass())) {
                try {
                    Object value = field.get(object);
                    if (value != null) return value;
                } catch (Throwable ignored) {}
            }
            return null;
        }
    }
}
