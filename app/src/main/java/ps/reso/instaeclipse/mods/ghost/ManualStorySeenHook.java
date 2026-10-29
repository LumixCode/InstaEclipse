package ps.reso.instaeclipse.mods.ghost;

import ps.reso.instaeclipse.xposedcompat.AndroidAppHelper;
import android.app.Activity;
import android.content.Context;
import android.os.SystemClock;
import android.widget.Toast;

import org.luckypray.dexkit.query.FindMethod;
import org.luckypray.dexkit.query.matchers.MethodMatcher;
import org.luckypray.dexkit.result.MethodData;

import java.lang.ref.WeakReference;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.net.URI;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import ps.reso.instaeclipse.xposedcompat.XC_MethodHook;
import ps.reso.instaeclipse.xposedcompat.XposedBridge;
import ps.reso.instaeclipse.R;
import ps.reso.instaeclipse.mods.misc.StoryMenu;
import ps.reso.instaeclipse.mods.ui.UIHookManager;
import ps.reso.instaeclipse.utils.core.DexKitCache;
import ps.reso.instaeclipse.utils.core.DexKitQueryCache;
import ps.reso.instaeclipse.utils.core.DexKitQueryCache.Found;
import ps.reso.instaeclipse.utils.core.HookFrames;
import ps.reso.instaeclipse.utils.feature.FeatureFlags;
import ps.reso.instaeclipse.utils.feature.FeatureStatusTracker;
import ps.reso.instaeclipse.utils.ghost.UnsentIdentity;
import ps.reso.instaeclipse.utils.i18n.I18n;
import ps.reso.instaeclipse.utils.log.ModuleLog;
import ps.reso.instaeclipse.utils.toast.IeToast;

public final class ManualStorySeenHook {
    private static final String SESSION = "com.instagram.common.session.UserSession";
    private static final String REEL_ITEM = "com.instagram.model.reels.ReelItem";
    private static final int MENU_ORDER = 3;
    private static final String CACHE_PIPELINE = "ManualStorySeen_pipeline_v1";
    private static final String CACHE_TRACKERS = "ManualStorySeen_trackers_v1";
    private static final ManualStorySeenPolicy approvals = new ManualStorySeenPolicy();
    private static final ThreadLocal<Dispatch> dispatch = new ThreadLocal<>();
    private static final AtomicBoolean loggedRejection = new AtomicBoolean();
    private final ManualStorySeenPolicy.Tracking tracking = new ManualStorySeenPolicy.Tracking();
    private final Map<Object, LinkedHashMap<String, Receipt>> receipts = new WeakHashMap<>();
    private Constructor<?> itemConstructor;
    private Method populateItem;
    private Method getStore;
    private Method sendItem;
    private Method pathSetter;
    private List<Method> trackers;
    private volatile boolean receiptPipelineReady;
    private String resolutionStage = "builder";
    private final AtomicInteger actionDiagnostics = new AtomicInteger();
    private boolean loggedCapture;

    private static final class Receipt {
        final Object graph;
        final String reelId;
        Receipt(Object graph, String reelId) { this.graph = graph; this.reelId = reelId; }
    }

    private record Target(Object session, Receipt receipt) {}

    private static final class Dispatch {
        final Object item;
        final String token;
        final WeakReference<Context> context;
        boolean tagged;
        Dispatch(Object item, String token, Context context) {
            this.item = item;
            this.token = token;
            this.context = new WeakReference<>(context);
        }
    }

    private static final Map<String, WeakReference<Context>> notifications = new LinkedHashMap<>();

    public void install(ClassLoader loader) {
        try {
            resolveReceiptPipeline(loader);
            receiptPipelineReady = true;
            ModuleLog.detail("(IE|ManualStorySeen) receipt pipeline installed");
        } catch (Throwable t) {
            ModuleLog.error("(IE|ManualStorySeen) Could not turn on \"Mark as seen\" for stories: not supported by this Instagram version");
            ModuleLog.detail("(IE|ManualStorySeen) receipt resolution failed at " + resolutionStage
                    + ": " + t.getClass().getSimpleName());
        }
        try {
            if (!StoryMenu.install(loader)) throw new NoSuchMethodException("Story options menu");
            StoryMenu.register(MENU_ORDER, new SeenRow());
            if (receiptPipelineReady) FeatureStatusTracker.setHooked("ManualStorySeen");
            else if (enabled()) FeatureStatusTracker.setBroken("ManualStorySeen");
            if (receiptPipelineReady) ModuleLog.ok("(IE|ManualStorySeen) ready");
        } catch (Throwable t) {
            if (enabled()) FeatureStatusTracker.setBroken("ManualStorySeen");
            ModuleLog.error("(IE|ManualStorySeen) Could not add \"Mark as seen\" to the story menu: " + t.getClass().getSimpleName() + ": " + t.getMessage());
        }
    }

    static boolean isExplicitItem(Object item) {
        Dispatch active = dispatch.get();
        return active != null && active.item == item;
    }

    public static boolean hasManualMarker(URI uri) {
        return ManualStorySeenPolicy.hasMarker(uri);
    }

    public static boolean allowManualRequest(Object request, URI uri, Object tigonService) {
        String token = ManualStorySeenPolicy.token(uri);
        try {
            Object session = typedValue(tigonService, SESSION, 1);
            URI clean = approvals.consume(uri, session, SystemClock.elapsedRealtime());
            if (clean == null) {
                approvals.revoke(token);
                if (!loggedRejection.getAndSet(true)) {
                    ModuleLog.warn("(IE|ManualStorySeen) A \"Mark as seen\" request was not sent: " + approvals.rejection());
                }
                notifyResult(token, R.string.ig_manual_story_seen_unavailable);
                return false;
            }
            ManualStorySeenPolicy.stripMarker(request, uri, clean);
            notifyResult(token, R.string.ig_manual_story_seen_sent);
            return true;
        } catch (Throwable t) {
            ModuleLog.error("(IE|ManualStorySeen) Could not send \"Mark as seen\"", t);
            notifyResult(token, R.string.ig_manual_story_seen_unavailable);
            return false;
        }
    }

    private static void notifyResult(String token, int message) {
        WeakReference<Context> reference;
        synchronized (notifications) { reference = token == null ? null : notifications.remove(token); }
        Context context = reference == null ? null : reference.get();
        if (context != null) toast(context, message);
    }

    private void resolveReceiptPipeline(ClassLoader loader) throws Exception {
        if (!loadCachedPipeline(loader)) {
            discoverReceiptPipeline(loader);
            DexKitCache.saveMethods(CACHE_PIPELINE, List.of(populateItem, sendItem, getStore, pathSetter));
            DexKitCache.saveMethods(CACHE_TRACKERS, trackers);
        }
        hookReceiptPipeline();
    }

    private boolean loadCachedPipeline(ClassLoader loader) {
        if (!DexKitCache.isCacheValid()) return false;
        List<Method> pipeline = DexKitCache.loadMethods(CACHE_PIPELINE, loader);
        List<Method> cachedTrackers = DexKitCache.loadMethods(CACHE_TRACKERS, loader);
        if (pipeline == null || pipeline.size() != 4 || cachedTrackers == null || cachedTrackers.isEmpty()) return false;
        resolutionStage = "cached pipeline";
        try {
            itemConstructor = pipeline.get(0).getDeclaringClass().getDeclaredConstructor();
            itemConstructor.setAccessible(true);
        } catch (Throwable t) {
            itemConstructor = null;
            return false;
        }
        populateItem = pipeline.get(0);
        sendItem = pipeline.get(1);
        getStore = pipeline.get(2);
        pathSetter = pipeline.get(3);
        trackers = cachedTrackers;
        return true;
    }

    private void discoverReceiptPipeline(ClassLoader loader) throws Exception {
        resolutionStage = "builder";
        List<Found> builders = DexKitQueryCache.methods(FindMethod.create().matcher(MethodMatcher.create()
                .usingStrings("media/seen/", "reels", "force_seen_story_ids")));
        List<Found> eligible = new ArrayList<>();
        for (Found data : builders) {
            if (!data.getParamTypeNames().equals(List.of(SESSION)) || data.getReturnTypeName().equals("void")) continue;
            Method method = data.getMethodInstance(loader);
            if (!Modifier.isStatic(method.getModifiers()) && method.getParameterCount() == 1
                    && method.getParameterTypes()[0].getName().equals(SESSION)) eligible.add(data);
        }
        if (eligible.size() != 1) throw new IllegalStateException("Ambiguous story request builder");
        resolutionStage = "empty receipt constructor";
        Found builderData = eligible.get(0);
        Class<?> itemClass = builderData.getMethodInstance(loader).getDeclaringClass();
        itemConstructor = itemClass.getDeclaredConstructor();
        itemConstructor.setAccessible(true);

        resolutionStage = "single-story population";
        for (Method method : itemClass.getDeclaredMethods()) {
            Class<?>[] types = method.getParameterTypes();
            if (method.getReturnType() != void.class) continue;
            boolean legacy = Modifier.isStatic(method.getModifiers()) && types.length == 4
                    && types[1].getName().equals(SESSION) && types[2] == itemClass
                    && types[3] == String.class && !types[0].isPrimitive();
            boolean current = !Modifier.isStatic(method.getModifiers()) && types.length == 3
                    && types[0].getName().equals(SESSION) && !types[1].isPrimitive()
                    && types[2] == String.class;
            if (!legacy && !current) continue;
            if (populateItem != null) throw new IllegalStateException("Ambiguous story population helper");
            populateItem = method;
            method.setAccessible(true);
        }
        if (populateItem == null) throw new NoSuchMethodException("Single story population");

        resolutionStage = "immediate sender";
        for (MethodData data : DexKitQueryCache.callersUncached(builderData)) {
            if (!data.getReturnTypeName().equals("void")
                    || !data.getParamTypeNames().equals(List.of(itemClass.getName()))) continue;
            Method method = data.getMethodInstance(loader);
            if (!Modifier.isStatic(method.getModifiers()) && method.getReturnType() == void.class
                    && method.getParameterCount() == 1 && method.getParameterTypes()[0] == itemClass) {
                if (sendItem != null) throw new IllegalStateException("Ambiguous story sender");
                sendItem = method;
                method.setAccessible(true);
            }
        }
        if (sendItem == null) throw new NoSuchMethodException("Immediate story sender");
        resolutionStage = "account story store";
        Class<?> store = sendItem.getDeclaringClass();
        for (Method method : store.getDeclaredMethods()) {
            if (Modifier.isStatic(method.getModifiers()) && method.getReturnType() == store
                    && method.getParameterCount() == 1 && method.getParameterTypes()[0].getName().equals(SESSION)) {
                if (getStore != null) throw new IllegalStateException("Ambiguous story store");
                getStore = method;
                method.setAccessible(true);
            }
        }
        if (getStore == null) throw new NoSuchMethodException("Account story store");

        resolutionStage = "request path setter";
        for (MethodData data : builderData.data().getInvokes()) {
            if (!data.getReturnTypeName().equals("void")
                    || !data.getParamTypeNames().equals(List.of("java.lang.String", "java.lang.Object[]"))) continue;
            Method method = data.getMethodInstance(loader);
            Class<?>[] types = method.getParameterTypes();
            if (method.getReturnType() == void.class && types.length == 2
                    && types[0] == String.class && types[1] == Object[].class) {
                if (pathSetter != null && !pathSetter.equals(method)) throw new IllegalStateException("Ambiguous request path setter");
                pathSetter = method;
            }
        }
        if (pathSetter == null) throw new NoSuchMethodException("Request path setter");

        resolutionStage = "per-story tracking";
        List<Method> found = new ArrayList<>();
        MethodData populationData = DexKitQueryCache.methodData(populateItem);
        if (populationData == null) throw new NoSuchMethodException("Population method metadata");
        for (MethodData data : DexKitQueryCache.callersUncached(populationData)) {
            if (!data.getParamTypeNames().contains(REEL_ITEM)) continue;
            Method method = data.getMethodInstance(loader);
            if (reelItemIndex(method) != -1) found.add(method);
        }
        if (found.isEmpty()) throw new NoSuchMethodException("Per-story receipt tracking");
        trackers = found;
    }

    private void hookReceiptPipeline() throws Exception {
        resolutionStage = "request path setter hook";
        XposedBridge.hookMethod(pathSetter, new XC_MethodHook() {
            @Override protected void beforeHookedMethod(MethodHookParam p) {
                Dispatch active = dispatch.get();
                if (active == null || !(p.args[0] instanceof String path)
                        || !path.startsWith("media/seen/?")) return;
                p.args[0] = path + "&" + ManualStorySeenPolicy.PARAMETER + "=" + active.token;
                active.tagged = true;
            }
        });

        resolutionStage = "per-story tracking hook";
        for (Method method : trackers) {
            final int index = reelItemIndex(method);
            if (index == -1) throw new NoSuchMethodException("Per-story receipt tracking");
            XposedBridge.hookMethod(method, new XC_MethodHook() {
                private final HookFrames.Slot frames = new HookFrames.Slot();

                @Override protected void beforeHookedMethod(MethodHookParam p) {
                    HookFrames.Frame frame = frames.enter();
                    frame.put("manual_story_previous", tracking.enter(enabled(), () -> mediaId(p.args[index])));
                }
                @Override protected void afterHookedMethod(MethodHookParam p) {
                    tracking.exit(frames.exit().get("manual_story_previous"));
                }
            });
        }
        boolean staticPopulation = Modifier.isStatic(populateItem.getModifiers());
        int graphIndex = staticPopulation ? 0 : 1;
        int sessionIndex = staticPopulation ? 1 : 0;
        int reelIndex = staticPopulation ? 3 : 2;
        XposedBridge.hookMethod(populateItem, new XC_MethodHook() {
            @Override protected void beforeHookedMethod(MethodHookParam p) {
                String id = tracking.current();
                if (!enabled() || dispatch.get() != null || id == null || p.args[graphIndex] == null
                        || p.args[sessionIndex] == null || !(p.args[reelIndex] instanceof String reelId)) return;
                synchronized (receipts) {
                    LinkedHashMap<String, Receipt> account = receipts.computeIfAbsent(p.args[sessionIndex], ignored -> new LinkedHashMap<>());
                    account.put(id, new Receipt(p.args[graphIndex], reelId));
                    while (account.size() > 32) account.remove(account.keySet().iterator().next());
                    if (!loggedCapture) {
                        loggedCapture = true;
                        ModuleLog.detail("(IE|ManualStorySeen) first account-scoped story receipt captured");
                    }
                }
            }
        });
        ModuleLog.detail("(IE|ManualStorySeen) installed " + trackers.size() + " per-story tracker(s)");
    }

    private static int reelItemIndex(Method method) {
        int index = -1;
        Class<?>[] types = method.getParameterTypes();
        for (int i = 0; i < types.length; i++) {
            if (!types[i].getName().equals(REEL_ITEM)) continue;
            if (index != -1) throw new IllegalStateException("Ambiguous viewed story");
            index = i;
        }
        return index;
    }

    private final class SeenRow implements StoryMenu.Row {
        @Override public boolean enabled() {
            return ManualStorySeenHook.enabled();
        }

        @Override public void rows(Object media, List<CharSequence> out) {
            if (!receiptPipelineReady || target(mediaId(media), null) != null) out.add(label());
        }

        @Override public boolean owns(CharSequence tapped) {
            return tapped != null && label().contentEquals(tapped);
        }

        @Override public void tap(CharSequence tapped, Object media, Context ctx) {
            markSeen(ctx, mediaId(media));
        }
    }

    private static String label() {
        return I18n.t(AndroidAppHelper.currentApplication(), R.string.ig_manual_story_seen_action);
    }

    private Target target(String id, Context context) {
        if (id == null) return null;
        Map<Object, Receipt> found = new IdentityHashMap<>();
        synchronized (receipts) {
            for (Map.Entry<Object, LinkedHashMap<String, Receipt>> account : receipts.entrySet()) {
                Receipt receipt = account.getValue().get(id);
                if (receipt != null) found.put(account.getKey(), receipt);
            }
        }
        if (found.isEmpty()) return null;
        Map<Object, String> accounts = new IdentityHashMap<>();
        for (Object session : found.keySet()) accounts.put(session, UnsentIdentity.account(session));
        Activity activity = context instanceof Activity a ? a : UIHookManager.topActivity();
        Object session = ManualStorySeenPolicy.pickSession(accounts,
                activity == null ? null : HideChatsHook.currentAccount(activity));
        Receipt receipt = session == null ? null : found.get(session);
        if (receipt == null || !ManualStorySeenPolicy.othersStory(receipt.reelId, accounts.get(session))) return null;
        return new Target(session, receipt);
    }

    private void markSeen(Context context, String id) {
        if (!receiptPipelineReady) {
            if (actionDiagnostics.getAndIncrement() < 3) ModuleLog.warn("(IE|ManualStorySeen) \"Mark as seen\" is not available on this Instagram version");
            toast(context, R.string.ig_manual_story_seen_unsupported);
            return;
        }
        String token = null;
        String stage = "receipt lookup";
        try {
            Target target = target(id, context);
            Object session = target == null ? null : target.session();
            Receipt receipt = target == null ? null : target.receipt();
            if (actionDiagnostics.getAndIncrement() < 3) {
                ModuleLog.detail("(IE|ManualStorySeen) action lookup: account=" + (session != null)
                        + ", currentStory=" + (id != null) + ", receipt=" + (receipt != null));
            }
            if (session == null || id == null || receipt == null) throw new IllegalStateException("Story receipt not captured");
            stage = "single-story construction";
            Object item = ManualStorySeenPolicy.createSingleItem(itemConstructor, populateItem,
                    receipt.graph, session, receipt.reelId);
            token = approvals.authorize(session, SystemClock.elapsedRealtime());
            Dispatch active = new Dispatch(item, token, context);
            synchronized (notifications) {
                while (notifications.size() >= 8) notifications.remove(notifications.keySet().iterator().next());
                notifications.put(token, active.context);
            }
            dispatch.set(active);
            stage = "native dispatch";
            sendItem.invoke(getStore.invoke(null, session), item);
            if (!active.tagged) throw new IllegalStateException("Request was not tagged");
        } catch (Throwable t) {
            if (token != null) {
                approvals.revoke(token);
                synchronized (notifications) { notifications.remove(token); }
            }
            toast(context, R.string.ig_manual_story_seen_unavailable);
            ModuleLog.error("(IE|ManualStorySeen) Could not mark the story as seen: " + t.getClass().getSimpleName() + ": " + t.getMessage());
            ModuleLog.detail("(IE|ManualStorySeen) send failed at " + stage);
        } finally { dispatch.remove(); }
    }

    private static boolean enabled() { return FeatureFlags.manualStorySeen && FeatureFlags.isGhostStory; }

    private static Object typedValue(Object object, String type, int depth) {
        Set<Object> matches = Collections.newSetFromMap(new IdentityHashMap<>());
        collectTyped(object, type, depth, matches);
        return matches.size() == 1 ? matches.iterator().next() : null;
    }

    private static void collectTyped(Object object, String type, int depth, Set<Object> matches) {
        if (object == null) return;
        if (object.getClass().getName().equals(type)) { matches.add(object); return; }
        if (depth == 0) return;
        String owner = object.getClass().getName();
        if (!owner.startsWith("X.") && !owner.startsWith("com.instagram.")) return;
        for (Class<?> c = object.getClass(); c != null && c != Object.class; c = c.getSuperclass()) {
            for (Field field : c.getDeclaredFields()) {
                if (Modifier.isStatic(field.getModifiers()) || field.getType().isPrimitive()) continue;
                try {
                    field.setAccessible(true);
                    collectTyped(field.get(object), type, depth - 1, matches);
                } catch (Throwable ignored) { }
            }
        }
    }

    private static String mediaId(Object reelItem) {
        try {
            Object value = reelItem.getClass().getMethod("getId").invoke(reelItem);
            return value instanceof String && !((String) value).isEmpty() ? (String) value : null;
        } catch (Throwable ignored) { return null; }
    }

    private static void toast(Context context, int message) {
        IeToast.makeText(context, I18n.t(context, message), Toast.LENGTH_SHORT).show();
    }
}
