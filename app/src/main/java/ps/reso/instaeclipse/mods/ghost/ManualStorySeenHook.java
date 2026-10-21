package ps.reso.instaeclipse.mods.ghost;

import ps.reso.instaeclipse.xposedcompat.AndroidAppHelper;
import android.content.Context;
import android.os.SystemClock;
import android.widget.Toast;

import org.luckypray.dexkit.DexKitBridge;
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
import java.util.concurrent.atomic.AtomicInteger;

import ps.reso.instaeclipse.xposedcompat.XC_MethodHook;
import ps.reso.instaeclipse.xposedcompat.XposedBridge;
import ps.reso.instaeclipse.R;
import ps.reso.instaeclipse.mods.media.StoryDownloadHook;
import ps.reso.instaeclipse.utils.core.DexKitCache;
import ps.reso.instaeclipse.utils.core.HookFrames;
import ps.reso.instaeclipse.utils.feature.FeatureFlags;
import ps.reso.instaeclipse.utils.feature.FeatureStatusTracker;
import ps.reso.instaeclipse.utils.i18n.I18n;
import ps.reso.instaeclipse.utils.log.ModuleLog;
import ps.reso.instaeclipse.utils.toast.IeToast;

/** Native single-story receipts; never sends or flushes Instagram's accumulated ghost-view queue. */
public final class ManualStorySeenHook {
    private static final String SESSION = "com.instagram.common.session.UserSession";
    private static final String REEL_ITEM = "com.instagram.model.reels.ReelItem";
    private static final String OPTIONS = "[INTERNAL] Pause Playback";
    // populate, send, store getter, path setter (in that order) / per-story trackers
    private static final String CACHE_PIPELINE = "ManualStorySeen_pipeline_v1";
    private static final String CACHE_TRACKERS = "ManualStorySeen_trackers_v1";
    private static final ManualStorySeenPolicy approvals = new ManualStorySeenPolicy();
    private static final ThreadLocal<Dispatch> dispatch = new ThreadLocal<>();
    private final ThreadLocal<String> recordingStory = new ThreadLocal<>();
    private final Map<Object, LinkedHashMap<String, Receipt>> receipts = new WeakHashMap<>();
    private Constructor<?> itemConstructor;
    private Method populateItem;
    private Method getStore;
    private Method sendItem;
    private Method pathSetter;
    private List<Method> trackers;
    private volatile boolean receiptPipelineReady;
    private String resolutionStage = "builder";
    private final AtomicInteger menuDiagnostics = new AtomicInteger();
    private final AtomicInteger actionDiagnostics = new AtomicInteger();
    private boolean loggedCapture;

    private static final class Receipt {
        final Object graph;
        final String reelId;
        Receipt(Object graph, String reelId) { this.graph = graph; this.reelId = reelId; }
    }

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

    public void install(DexKitBridge bridge, ClassLoader loader) {
        try {
            resolveReceiptPipeline(bridge, loader);
            receiptPipelineReady = true;
            ModuleLog.line("(IE|ManualStorySeen) Receipt pipeline installed");
        } catch (Throwable t) {
            ModuleLog.line("(IE|ManualStorySeen) Receipt resolution failed at " + resolutionStage
                    + ": " + t.getClass().getSimpleName());
        }
        // A changed receipt implementation must not silently hide the user's menu action.
        // Keep the same independent menu installation used by StoryDownloadHook; dispatch still
        // refuses to send anything until the entire single-story pipeline resolved successfully.
        try {
            installMenu(bridge, loader);
            if (receiptPipelineReady) FeatureStatusTracker.setHooked("ManualStorySeen");
            else if (enabled()) FeatureStatusTracker.setBroken("ManualStorySeen");
        } catch (Throwable t) {
            if (enabled()) FeatureStatusTracker.setBroken("ManualStorySeen");
            ModuleLog.line("(IE|ManualStorySeen) Menu installation failed: " + t.getClass().getSimpleName());
        }
    }

    /** Used only by the ghost store's immediate sender; batch inserts remain blocked. */
    static boolean isExplicitItem(Object item) {
        Dispatch active = dispatch.get();
        return active != null && active.item == item;
    }

    public static boolean hasManualMarker(URI uri) {
        return ManualStorySeenPolicy.hasMarker(uri);
    }

    /** The network fallback must call this for seen requests, even if ghost mode was just disabled. */
    public static boolean allowManualRequest(Object request, URI uri, Object tigonService) {
        try {
            Object session = typedValue(tigonService, SESSION, 1);
            URI clean = approvals.consume(uri, session, SystemClock.elapsedRealtime());
            if (clean == null) return false;
            String token = null;
            for (String pair : uri.getRawQuery().split("&")) {
                if (pair.startsWith(ManualStorySeenPolicy.PARAMETER + "=")) {
                    token = pair.substring(ManualStorySeenPolicy.PARAMETER.length() + 1);
                }
            }
            ManualStorySeenPolicy.stripMarker(request, uri, clean);
            WeakReference<Context> reference;
            synchronized (notifications) { reference = notifications.remove(token); }
            Context context = reference == null ? null : reference.get();
            if (context != null) toast(context, R.string.ig_manual_story_seen_sent);
            return true;
        } catch (Throwable t) {
            ModuleLog.line("(IE|ManualStorySeen) request authorization failed: " + t.getClass().getSimpleName());
            return false;
        }
    }

    private void resolveReceiptPipeline(DexKitBridge bridge, ClassLoader loader) throws Exception {
        // The call-graph queries below are costly and every warm launch installs inline, so a
        // previous scan of this Instagram build is reused whenever all of its members still load.
        if (!loadCachedPipeline(loader)) {
            discoverReceiptPipeline(bridge, loader);
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
            // Both verified population shapes are declared on the pending item class itself.
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

    private void discoverReceiptPipeline(DexKitBridge bridge, ClassLoader loader) throws Exception {
        resolutionStage = "builder";
        List<MethodData> builders = bridge.findMethod(FindMethod.create().matcher(MethodMatcher.create()
                .usingStrings("media/seen/", "reels", "force_seen_story_ids")));
        List<MethodData> eligible = new ArrayList<>();
        for (MethodData data : builders) {
            if (!data.getParamTypeNames().equals(List.of(SESSION)) || data.getReturnTypeName().equals("void")) continue;
            Method method = data.getMethodInstance(loader);
            if (!Modifier.isStatic(method.getModifiers()) && method.getParameterCount() == 1
                    && method.getParameterTypes()[0].getName().equals(SESSION)) eligible.add(data);
        }
        if (eligible.size() != 1) throw new IllegalStateException("Ambiguous story request builder");
        resolutionStage = "empty receipt constructor";
        MethodData builderData = eligible.get(0);
        Class<?> itemClass = builderData.getMethodInstance(loader).getDeclaringClass();
        itemConstructor = itemClass.getDeclaredConstructor();
        itemConstructor.setAccessible(true);

        // 439 passes the pending item into a static helper; 446 uses the fresh item as receiver.
        // Both verified shapes populate exactly one story using the original account and reel ID.
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
        for (MethodData data : builderData.getCallers()) {
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
        for (MethodData data : builderData.getInvokes()) {
            // Inspect metadata before resolving reflection. Constructors and unrelated request
            // helpers are not methods we intend to hook and can change independently of this API.
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
        MethodData populationData = bridge.getMethodData(populateItem);
        if (populationData == null) throw new NoSuchMethodException("Population method metadata");
        for (MethodData data : populationData.getCallers()) {
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
            @Override protected void beforeHookedMethod(MethodHookParam p) { ps.reso.instaeclipse.utils.core.JitGuard.tick(p.method);
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

                @Override protected void beforeHookedMethod(MethodHookParam p) { ps.reso.instaeclipse.utils.core.JitGuard.tick(p.method);
                    HookFrames.Frame frame = frames.enter();
                    frame.put("manual_story_previous", recordingStory.get());
                    recordingStory.set(mediaId(p.args[index]));
                }
                @Override protected void afterHookedMethod(MethodHookParam p) { ps.reso.instaeclipse.utils.core.JitGuard.tick(p.method);
                    HookFrames.Frame frame = frames.exit();
                    String previous = (String) frame.get("manual_story_previous");
                    if (previous == null) recordingStory.remove(); else recordingStory.set(previous);
                }
            });
        }
        boolean staticPopulation = Modifier.isStatic(populateItem.getModifiers());
        int graphIndex = staticPopulation ? 0 : 1;
        int sessionIndex = staticPopulation ? 1 : 0;
        int reelIndex = staticPopulation ? 3 : 2;
        XposedBridge.hookMethod(populateItem, new XC_MethodHook() {
            @Override protected void beforeHookedMethod(MethodHookParam p) { ps.reso.instaeclipse.utils.core.JitGuard.tick(p.method);
                String id = recordingStory.get();
                if (!enabled() || dispatch.get() != null || id == null || p.args[graphIndex] == null
                        || p.args[sessionIndex] == null || !(p.args[reelIndex] instanceof String reelId)) return;
                synchronized (receipts) {
                    LinkedHashMap<String, Receipt> account = receipts.computeIfAbsent(p.args[sessionIndex], ignored -> new LinkedHashMap<>());
                    account.put(id, new Receipt(p.args[graphIndex], reelId));
                    while (account.size() > 32) account.remove(account.keySet().iterator().next());
                    if (!loggedCapture) {
                        loggedCapture = true;
                        ModuleLog.line("(IE|ManualStorySeen) First account-scoped story receipt captured");
                    }
                }
            }
        });
        ModuleLog.line("(IE|ManualStorySeen) Installed " + trackers.size() + " per-story tracker(s)");
    }

    /** Index of the method's only ReelItem parameter, or -1 when it has none. */
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

    private void installMenu(DexKitBridge bridge, ClassLoader loader) throws Exception {
        int builders = 0, handlers = 0, failed = 0;
        // Same builders and dispatchers as the other story-menu rows: IG 452 moved the anchor out of
        // the others'-story builder. Static builders must stay hooked: every 446 builder is static.
        List<Method> optionBuilders = DexKitCache.isCacheValid() ? DexKitCache.loadMethods("MentionButtons", loader) : null;
        if (optionBuilders == null || optionBuilders.isEmpty()) {
            optionBuilders = new ArrayList<>(StoryDownloadHook.storyOptionBuilders(bridge.findMethod(FindMethod.create()
                    .matcher(MethodMatcher.create().usingStrings(OPTIONS))), loader));
        }
        XC_MethodHook builderHook = new XC_MethodHook() {
            @Override protected void afterHookedMethod(MethodHookParam p) { ps.reso.instaeclipse.utils.core.JitGuard.tick(p.method);
                try {
                    if (menuDiagnostics.getAndIncrement() < 3) {
                        ModuleLog.line("(IE|ManualStorySeen) Menu bind: option=" + FeatureFlags.manualStorySeen
                            + ", ghost=" + FeatureFlags.isGhostStory + ", sender=" + receiptPipelineReady
                            + ", labels=" + (p.getResult() instanceof CharSequence[]));
                    }
                    if (!enabled() || !(p.getResult() instanceof CharSequence[] original)) return;
                    String label = I18n.t(AndroidAppHelper.currentApplication(), R.string.ig_manual_story_seen_action);
                    p.setResult(ManualStorySeenPolicy.appendAction(original, label));
                } catch (Throwable t) {
                    if (menuDiagnostics.getAndIncrement() < 6) {
                        ModuleLog.line("(IE|ManualStorySeen) Menu append failed: " + t.getClass().getSimpleName());
                    }
                }
            }
        };
        for (Method method : optionBuilders) {
            try {
                XposedBridge.hookMethod(method, builderHook);
                builders++;
            } catch (Throwable t) {
                failed++;
                if (failed <= 2) ModuleLog.line("(IE|ManualStorySeen) Menu candidate skipped: " + t.getClass().getSimpleName());
            }
        }
        XC_MethodHook dispatcherHook = new XC_MethodHook() {
            @Override protected void beforeHookedMethod(MethodHookParam p) { ps.reso.instaeclipse.utils.core.JitGuard.tick(p.method);
                if (!enabled()) return;
                String label = I18n.t(AndroidAppHelper.currentApplication(), R.string.ig_manual_story_seen_action);
                boolean selected = false;
                for (Object arg : p.args) selected |= arg instanceof CharSequence && label.contentEquals((CharSequence) arg);
                if (!selected) return;
                p.setResult(null);
                Object item = across(p, REEL_ITEM), session = across(p, SESSION);
                Context context = AndroidAppHelper.currentApplication();
                markSeen(context, session, mediaId(item));
            }
        };
        for (Method method : StoryDownloadHook.storyClickDispatchers(bridge, loader)) {
            if (method.getParameterCount() == 0) continue; // dispatchers receive the tapped label
            try {
                XposedBridge.hookMethod(method, dispatcherHook);
                handlers++;
            } catch (Throwable t) {
                failed++;
                if (failed <= 2) ModuleLog.line("(IE|ManualStorySeen) Menu candidate skipped: " + t.getClass().getSimpleName());
            }
        }
        ModuleLog.line("(IE|ManualStorySeen) Menu discovery: builders=" + builders
            + ", dispatchers=" + handlers + ", skipped=" + failed);
        if (builders == 0 || handlers == 0) throw new NoSuchMethodException("Story options menu");
    }

    private void markSeen(Context context, Object session, String id) {
        if (!receiptPipelineReady) {
            if (actionDiagnostics.getAndIncrement() < 3) ModuleLog.line("(IE|ManualStorySeen) Action refused: sender unresolved");
            toast(context, R.string.ig_manual_story_seen_unsupported);
            return;
        }
        String token = null;
        String stage = "receipt lookup";
        try {
            Receipt receipt;
            synchronized (receipts) {
                Map<String, Receipt> account = receipts.get(session);
                receipt = account == null ? null : account.get(id);
            }
            if (actionDiagnostics.getAndIncrement() < 3) {
                ModuleLog.line("(IE|ManualStorySeen) Action lookup: account=" + (session != null)
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
            ModuleLog.line("(IE|ManualStorySeen) send failed at " + stage + ": " + t.getClass().getSimpleName());
        } finally { dispatch.remove(); }
    }

    private static boolean enabled() { return FeatureFlags.manualStorySeen && FeatureFlags.isGhostStory; }

    private static Object across(XC_MethodHook.MethodHookParam p, String type) {
        Set<Object> matches = Collections.newSetFromMap(new IdentityHashMap<>());
        for (Object arg : p.args) if (arg != null && arg.getClass().getName().equals(type)) matches.add(arg);
        if (!matches.isEmpty()) return matches.size() == 1 ? matches.iterator().next() : null;
        // Both native menu dispatchers pass their declaring-class holder, whose direct typed fields
        // are the current ReelItem and UserSession. Never walk the viewer/prefetch object graph.
        Class<?> owner = p.method.getDeclaringClass();
        if (owner.isInstance(p.thisObject)) collectTyped(p.thisObject, type, 1, matches);
        for (Object arg : p.args) if (owner.isInstance(arg)) collectTyped(arg, type, 1, matches);
        return matches.size() == 1 ? matches.iterator().next() : null;
    }

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
