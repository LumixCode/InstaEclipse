package ps.reso.instaeclipse.mods.ghost;

import android.annotation.SuppressLint;
import android.app.Activity;
import ps.reso.instaeclipse.xposedcompat.AndroidAppHelper;
import android.app.Application;
import android.content.Context;
import android.graphics.Color;
import android.graphics.Rect;
import android.os.Build;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.MotionEvent;
import android.view.ViewConfiguration;
import android.view.ViewTreeObserver;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.Toast;

import org.luckypray.dexkit.DexKitBridge;
import org.luckypray.dexkit.query.FindMethod;
import org.luckypray.dexkit.query.matchers.MethodMatcher;
import org.luckypray.dexkit.result.MethodData;
import org.luckypray.dexkit.result.MethodDataList;

import java.lang.ref.WeakReference;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;

import ps.reso.instaeclipse.xposedcompat.XC_MethodHook;
import ps.reso.instaeclipse.xposedcompat.XposedBridge;
import ps.reso.instaeclipse.R;
import ps.reso.instaeclipse.mods.ui.LockDirectMessagesHook;
import ps.reso.instaeclipse.mods.ui.UIHookManager;
import ps.reso.instaeclipse.mods.ui.utils.LifecycleHookUtil;
import ps.reso.instaeclipse.utils.core.DexKitQueryCache;
import ps.reso.instaeclipse.utils.core.LegacyPaths;
import ps.reso.instaeclipse.utils.core.ModuleResources;
import ps.reso.instaeclipse.utils.dialog.LockFlows;
import ps.reso.instaeclipse.utils.feature.FeatureFlags;
import ps.reso.instaeclipse.utils.feature.FeatureStatusTracker;
import ps.reso.instaeclipse.utils.ghost.CurrentThreadTracker;
import ps.reso.instaeclipse.utils.ghost.HiddenThreads;
import ps.reso.instaeclipse.utils.ghost.LockedThreads;
import ps.reso.instaeclipse.utils.ghost.ThreadNames;
import ps.reso.instaeclipse.utils.ghost.UnsentIdentity;
import ps.reso.instaeclipse.utils.log.ModuleLog;
import ps.reso.instaeclipse.utils.toast.IeToast;
import ps.reso.instaeclipse.utils.ui.IgTint;

/**
 * Hides explicitly selected Direct threads from Instagram's inbox. An open chat offers
 * "Hide chat" / "Unhide chat" in the module's thread header menu; a triple tap on the inbox
 * header (after the passcode) shows only the hidden chats.
 */
public class HideChatsHook {

    private static final String INBOX_CONTROL_TAG = "ie_hidden_chats_inbox_control";
    private static final String INBOX_ANCHOR = "DirectThreadStoreImpl.getSortedCopyOfThreadSummaries";
    private static final String GENERATOR_ANCHOR = "InboxViewModelGenerator.calculateAndApplyDiffUpdate";
    /** The corner runs from the window's left edge (the old 8 dp inset only dropped taps). */
    private static final int INBOX_GESTURE_WIDTH_DP = 108;
    /**
     * The hidden inbox's back arrow in that corner: 24 dp but never more than the corner's height
     * less 28 dp, plus BACK_ARROW_GROW_DP, centred BACK_ARROW_CENTER_X_DP from the left edge and
     * level with the account name (the corner's middle when the name isn't found). The whole
     * corner stays its touch target.
     */
    private static final int BACK_ARROW_DP = 24;
    private static final int BACK_ARROW_GROW_DP = 2;
    private static final int BACK_ARROW_CENTER_X_DP = 34;
    /** The account name in the inbox header, the text first. */
    private static final String[] ACCOUNT_NAME_IDS = {"action_bar_title_textview", "action_bar_title"};

    private static final Map<View, WeakReference<Activity>> watchedRoots = new WeakHashMap<>();
    private static final Map<View, ViewTreeObserver.OnGlobalLayoutListener> layoutListeners = new WeakHashMap<>();
    private static final Map<Activity, MenuBinding> menuBindings = new WeakHashMap<>();
    private static final Map<Activity, LockBinding> lockBindings = new WeakHashMap<>();
    private static final Map<Activity, ForegroundThread> foregroundThreads = new WeakHashMap<>();
    private static final Map<Activity, WeakReference<Object>> foregroundInboxes = new WeakHashMap<>();
    private static final Map<Activity, InboxControlBinding> inboxControls = new WeakHashMap<>();
    private static final Map<Activity, InboxTapGesture> tapGestures = new WeakHashMap<>();
    private static final KnownThreads knownThreads = new KnownThreads();
    private static final Map<Object, NativeInbox> nativeInboxes = new WeakHashMap<>();
    private static final Map<Class<?>, List<Field>> threadKeyFields = new java.util.concurrent.ConcurrentHashMap<>();
    private static final Set<Class<?>> chatScreens = java.util.concurrent.ConcurrentHashMap.newKeySet();
    private static volatile Method titleFormatter;
    /** invokeNoArg's lookups by class and name, misses included: the Lock gate resolves the open
     *  screen through them several times a second, and every miss threw NoSuchMethodException. */
    private static final Map<Class<?>, Map<String, java.util.Optional<Method>>> noArgMethods =
            new java.util.concurrent.ConcurrentHashMap<>();
    private static volatile NativeInboxPath inboxPath;
    private static Class<?> inboxGetterOwner;
    private static NativeInboxPath.Getter inboxGetter;
    private static HideChatsHook activeHook;
    private static volatile boolean hiddenOnlyMode;
    private static volatile String hiddenAccount;
    private static WeakReference<Activity> recentsHidden;
    private static boolean lifecycleRegistered;
    private static boolean threadsTracked, inboxesTracked;
    private static final Set<Activity> startedActivities =
            Collections.newSetFromMap(new WeakHashMap<>());
    private static int inboxActionBarId;

    /** The chat a header menu's Hide/Unhide entry was added for, and which of the two it offers. */
    private static final class MenuBinding {
        final WeakReference<View> header;
        final String threadId;
        final boolean hide;
        boolean titled;

        MenuBinding(View header, String threadId, boolean hide, boolean titled) {
            this.header = new WeakReference<>(header);
            this.threadId = threadId;
            this.hide = hide;
            this.titled = titled;
        }

        View shownHeader(String liveThreadId) {
            View view = header.get();
            return view != null && view.isAttachedToWindow() && view.isShown() && threadId.equals(liveThreadId)
                    && hide != HiddenThreads.isHidden(threadId) ? view : null;
        }
    }

    private record LockBinding(WeakReference<View> header, String threadId, int state, int revision) {
        boolean same(View shown, String id, int now, int menu) {
            return header.get() == shown && threadId.equals(id) && state == now && revision == menu;
        }
    }

    private static final class ForegroundThread {
        final WeakReference<Object> fragment;
        final String threadId;

        ForegroundThread(Object fragment, String threadId) {
            this.fragment = new WeakReference<>(fragment);
            this.threadId = threadId;
        }
    }

    private static final class NativeInbox {
        final String account;
        final HiddenInboxRows<Object> rows = new HiddenInboxRows<>();
        final InboxReplayGate gate = new InboxReplayGate();

        NativeInbox(Object owner) { account = UnsentIdentity.account(owner); }

        boolean hiddenOnly() {
            return hiddenOnlyMode && account != null && account.equals(hiddenAccount);
        }

        long revision() {
            return InboxReplayGate.revision(HiddenThreads.revision(), FeatureFlags.hideSpecificChats, hiddenOnly());
        }

        List<Object> visible() {
            return rows.visible(HiddenThreads.ids(), FeatureFlags.hideSpecificChats, hiddenOnly(),
                    HideChatsHook::threadIdOfRow);
        }
    }

    private static final class InboxControlBinding {
        final ImageView control;

        InboxControlBinding(ImageView control) {
            this.control = control;
        }

    }

    public void install(DexKitBridge bridge, ClassLoader classLoader) {
        activeHook = this;
        resolveTitleFormatter(bridge, classLoader);
        installInboxPresentation(bridge, classLoader);
        installInboxFilter(bridge, classLoader);
        installForegroundTracker(bridge, classLoader);
        installHeaderMenu(classLoader);
        installInboxGesture(classLoader);
        int inboxResumes = hookDirectLifecycle(bridge, classLoader, "DirectInboxController.onResume", new XC_MethodHook() {
            @Override protected void afterHookedMethod(MethodHookParam param) { ps.reso.instaeclipse.utils.core.JitGuard.tick(param.method);
                Activity activity = fragmentActivity(param.thisObject);
                if (activity != null) foregroundInboxes.put(activity, new WeakReference<>(param.thisObject));
                watchActivity(activity);
                if (activeHook != null && activity != null) activeHook.refreshInbox(activity);
                LockDirectMessagesHook.watchActivity(activity);
            }
        });
        hookDirectLifecycle(bridge, classLoader, "DirectInboxController.onPause", new XC_MethodHook() {
            @Override protected void afterHookedMethod(MethodHookParam param) { ps.reso.instaeclipse.utils.core.JitGuard.tick(param.method);
                Activity activity = fragmentActivity(param.thisObject);
                WeakReference<Object> current = foregroundInboxes.get(activity);
                if (current != null && current.get() == param.thisObject) foregroundInboxes.remove(activity);
            }
        });
        inboxesTracked = inboxResumes > 0;
    }

    public static void watchActivity(Activity activity) {
        if (activity != null && activeHook != null) {
            activity.runOnUiThread(() -> activeHook.registerListener(activity));
        }
    }

    private static void installInboxGesture(ClassLoader loader) {
        Set<Method> hooked = new java.util.HashSet<>();
        for (String name : new String[]{"com.instagram.mainactivity.InstagramMainActivity",
                "com.instagram.modal.ModalActivity"}) {
            try {
                Class<?> type = Class.forName(name, false, loader);
                Method dispatch = type.getMethod("dispatchTouchEvent", MotionEvent.class);
                if (!hooked.add(dispatch)) continue;
                XposedBridge.hookMethod(dispatch, new XC_MethodHook() {
                    @Override protected void beforeHookedMethod(MethodHookParam param) { ps.reso.instaeclipse.utils.core.JitGuard.tick(param.method);
                        if (param.thisObject instanceof Activity activity
                                && activity.getPackageName().startsWith("com.instagram")
                                && activeHook != null
                                && activeHook.onInboxTouch(activity, (MotionEvent) param.args[0])) {
                            param.setResult(true); // a stray tap after the triple tap: Instagram never sees it
                        }
                    }
                });
            } catch (Throwable t) {
                ModuleLog.warn("(IE|HideChats) Triple tap to open hidden chats may not work in "
                        + name.substring(name.lastIndexOf('.') + 1) + ": " + t.getClass().getSimpleName() + ": " + t.getMessage());
            }
        }
    }

    /**
     * Feeds one Instagram touch event to this activity's {@link InboxTapGesture}; true when the
     * event must not reach Instagram (a touch in the corner just after the triple tap).
     */
    private boolean onInboxTouch(Activity activity, MotionEvent event) {
        boolean counting = FeatureFlags.hideSpecificChats && !hiddenOnlyMode;
        InboxTapGesture gesture = tapGestures.get(activity);
        if (gesture == null) {
            if (!counting) return false;
            gesture = new InboxTapGesture();
            tapGestures.put(activity, gesture);
        }
        InboxTapGesture.Verdict verdict;
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN -> {
                // Every touch in Instagram lands here: skip the fragment walk unless the inbox is up
                // and the touch can count, or be swallowed after a triple tap. The hidden inbox
                // counts no taps; its back control in the corner is only guarded.
                boolean active = counting || gesture.guarding(event.getEventTime());
                Rect area = active && inboxMaybeShown(activity) ? inboxGestureBounds(activity) : null;
                verdict = gesture.down(event.getRawX(), event.getRawY(), event.getEventTime(),
                        area != null && area.contains((int) event.getRawX(), (int) event.getRawY()));
            }
            case MotionEvent.ACTION_UP -> verdict = !gesture.inTouch() ? InboxTapGesture.Verdict.PASS
                    : gesture.up(event.getRawX(), event.getRawY(), event.getEventTime(), event.getDownTime(),
                    ViewConfiguration.get(activity).getScaledTouchSlop(),
                    ViewConfiguration.getLongPressTimeout());
            case MotionEvent.ACTION_CANCEL -> verdict = gesture.cancel();
            case MotionEvent.ACTION_POINTER_DOWN -> verdict = gesture.pointerDown();
            default -> verdict = gesture.during();
        }
        String broken = gesture.takeBreak();
        if (broken != null) ModuleLog.detail("(IE|HideChats) inbox triple tap dropped: " + broken);
        if (verdict == InboxTapGesture.Verdict.OPEN) openHiddenInboxPrompt(activity, gesture);
        return verdict == InboxTapGesture.Verdict.SWALLOW;
    }

    private void openHiddenInboxPrompt(Activity activity, InboxTapGesture gesture) {
        ModuleLog.detail("(IE|HideChats) header triple tap detected");
        activity.getWindow().getDecorView().post(() -> {
            if (inboxGestureBounds(activity) == null) {
                gesture.endGuard();
                ModuleLog.detail("(IE|HideChats) triple tap dropped: the inbox is no longer shown");
                return;
            }
            boolean shown = LockDirectMessagesHook.authenticateForHiddenChats(activity, () -> {
                if (FeatureFlags.hideSpecificChats && inboxGestureBounds(activity) != null) {
                    setHiddenOnlyMode(activity, true);
                }
            });
            if (!shown) {
                gesture.endGuard();
                ModuleLog.line("(IE|HideChats) Hidden chats did not open: no passcode is set, or a passcode prompt is already open");
            }
        });
    }

    /** False only when the inbox lifecycle is tracked and no inbox is in front in this activity. */
    private static boolean inboxMaybeShown(Activity activity) {
        if (!inboxesTracked) return true; // no lifecycle signal on this build: the view check decides
        WeakReference<Object> inbox = foregroundInboxes.get(activity);
        return inbox != null && inbox.get() != null;
    }

    private Rect inboxGestureBounds(Activity activity) {
        ensureIds(activity);
        View decor = activity.getWindow().getDecorView();
        String module = visibleModule(activity);
        if (!"direct_inbox".equals(module) && !(module.isEmpty()
                && ThreadHeaderMenu.findShown(decor, inboxActionBarId) != null
                && ThreadHeaderMenu.shownHeader(activity) == null)) return null;
        View bar = ThreadHeaderMenu.findShown(decor, inboxActionBarId);
        Rect bounds = new Rect();
        if (bar == null || !bar.getGlobalVisibleRect(bounds)) {
            // Compose inboxes can omit the old action-bar view. Anchor below the status inset.
            View content = activity.findViewById(android.R.id.content);
            if (content == null || !content.getGlobalVisibleRect(bounds)) return null;
            android.view.WindowInsets insets = decor.getRootWindowInsets();
            if (insets != null) bounds.top = Math.max(bounds.top, insets.getSystemWindowInsetTop());
        }
        bounds.left = 0;
        bounds.right = Math.min(decor.getWidth(), dp(activity, INBOX_GESTURE_WIDTH_DP));
        bounds.bottom = Math.min(bounds.bottom, bounds.top + dp(activity, 64));
        int[] origin = new int[2];
        decor.getLocationOnScreen(origin);
        bounds.offset(origin[0], origin[1]);
        return bounds;
    }

    private void installInboxFilter(DexKitBridge bridge, ClassLoader classLoader) {
        XC_MethodHook filter = new XC_MethodHook() {
            @Override protected void afterHookedMethod(MethodHookParam param) { ps.reso.instaeclipse.utils.core.JitGuard.tick(param.method);
                Object result = param.getResult();
                if (!(result instanceof List<?> rows) || rows.isEmpty()) return;

                // Capture before filtering. Hidden or blocked accounts remain selectable because
                // every label and ID comes from Instagram's local thread summary.
                rememberRows(rows, param.thisObject);
                // Builds without the native row consumer keep normal hiding as a fallback. Where it
                // exists, filter only the final inbox submission so native caches and pagination
                // retain every thread.
                if (inboxPath == null && FeatureFlags.hideSpecificChats) {
                    param.setResult(HiddenInboxRows.select(rows, HiddenThreads.ids(),
                            false, HideChatsHook::threadIdOfRow));
                }
            }
        };
        try {
            int count = 0;
            for (Method method : KeepUnsentMessagesHook.cachedMethods("HideChats_inbox", classLoader,
                    () -> KeepUnsentMessagesHook.methodsOf(DexKitQueryCache.findMethod(bridge, FindMethod.create()
                            .matcher(MethodMatcher.create().usingStrings(INBOX_ANCHOR))), classLoader))) {
                try {
                    XposedBridge.hookMethod(method, filter);
                    count++;
                } catch (Throwable ignored) {}
            }
            if (count > 0) FeatureStatusTracker.setHooked("HideSpecificChats");
            ModuleLog.line("(IE|HideChats) " + (count > 0 ? "✅ ready"
                    : "❌ Could not turn on hidden chats: not supported by this Instagram version (chat list not found)"));
            ModuleLog.detail("(IE|HideChats) inbox filter hooked " + count + " method(s)");
        } catch (Throwable t) {
            ModuleLog.error("(IE|HideChats) Could not turn on hidden chats", t);
        }
    }

    /**
     * The inbox generator's row consumer receives the complete row List, then publishes the native
     * inbox diff (446 X.0AJc as X.05Ho.A0J, 451 X.09TE as X.07Ln.A0L). Resolved from the
     * generator's trace string and class shapes only; see {@link NativeInboxPath}.
     */
    private static void installInboxPresentation(DexKitBridge bridge, ClassLoader loader) {
        try {
            Class<?> generator = null;
            for (Method method : KeepUnsentMessagesHook.cachedMethods("HideChats_inboxGenerator", loader,
                    () -> KeepUnsentMessagesHook.methodsOf(DexKitQueryCache.findMethod(bridge, FindMethod.create()
                            .matcher(MethodMatcher.create().usingStrings(GENERATOR_ANCHOR))), loader))) {
                if (generator != null && generator != method.getDeclaringClass()) {
                    throw new IllegalStateException("inbox generator ambiguous");
                }
                generator = method.getDeclaringClass();
            }
            if (generator == null) throw new IllegalStateException("inbox generator anchor missing");
            Class<?> model = generator;
            List<Method> callbacks = KeepUnsentMessagesHook.cachedMethods("HideChats_inboxConsumer", loader,
                    () -> NativeInboxPath.callbacks(model, constructedBy(bridge, loader, model)));
            NativeInboxPath path = callbacks.size() == 1 ? NativeInboxPath.of(model, callbacks.get(0)) : null;
            if (path == null) throw new IllegalStateException(callbacks.size() + " inbox consumer callback(s)");
            XposedBridge.hookMethod(path.submit, new XC_MethodHook() {
                @Override protected void beforeHookedMethod(MethodHookParam param) throws Throwable { ps.reso.instaeclipse.utils.core.JitGuard.tick(param.method);
                    // The lambda class serves every stream variant (on 451 other owners too):
                    // only a List through this generator's own consumer is its inbox row list.
                    Object owner = path.rowGenerator(param.thisObject, param.args[0]);
                    if (owner == null) return;
                    NativeInbox state;
                    synchronized (nativeInboxes) {
                        state = nativeInboxes.computeIfAbsent(param.thisObject, ignored -> new NativeInbox(owner));
                        state.rows.replace((List<?>) param.args[0]);
                    }
                    long revision = state.revision();
                    if (FeatureFlags.hideSpecificChats || hiddenOnlyMode) param.args[0] = state.visible();
                    state.gate.applied(revision);
                }
            });
            inboxPath = path;
            ModuleLog.detail("(IE|HideChats) native inbox presentation hooked " + model.getName()
                    + " via " + path.submit.getDeclaringClass().getName());
        } catch (Throwable t) {
            ModuleLog.warn("(IE|HideChats) The hidden chats inbox is not available on this Instagram version (chats are still hidden from the list)");
            ModuleLog.detail("(IE|HideChats) native inbox presentation unavailable: " + t.getMessage());
        }
    }

    /** Classes the constructors of {@code type} instantiate, read from its bytecode by DexKit. */
    private static List<Class<?>> constructedBy(DexKitBridge bridge, ClassLoader loader, Class<?> type) {
        List<Class<?>> types = new ArrayList<>();
        for (Constructor<?> init : type.getDeclaredConstructors()) {
            MethodData data = bridge.getMethodData(init);
            if (data == null) continue;
            for (MethodData call : data.getInvokes()) {
                if (!call.isConstructor()) continue;
                try {
                    types.add(Class.forName(call.getClassName(), false, loader));
                } catch (ClassNotFoundException | LinkageError ignored) {}
            }
        }
        return types;
    }

    private void installForegroundTracker(DexKitBridge bridge, ClassLoader classLoader) {
        if (!LegacyPaths.has(LegacyPaths.HEADER)) {
            for (Method method : directLifecycle(bridge, classLoader, "DirectThreadController.onResume")) {
                chatScreens.add(method.getDeclaringClass());
            }
            int pauses = directLifecycle(bridge, classLoader, "DirectThreadController.onPause").size();
            threadsTracked = true;
            ModuleLog.detail("(IE|HideChats) open chat read from the chat tracker screens=" + chatScreens.size()
                    + " pause=" + pauses);
            return;
        }
        int resumes = hookDirectLifecycle(bridge, classLoader,
                "DirectThreadController.onResume", new XC_MethodHook() {
                    @Override protected void afterHookedMethod(MethodHookParam param) { ps.reso.instaeclipse.utils.core.JitGuard.tick(param.method);
                        Object fragment = param.thisObject;
                        Activity activity = fragmentActivity(fragment);
                        String threadId = directThreadId(fragment);
                        if (activity == null || threadId == null) return;
                        synchronized (foregroundThreads) {
                            foregroundThreads.put(activity, new ForegroundThread(fragment, threadId));
                        }
                        activity.runOnUiThread(() -> registerListener(activity));
                        LockDirectMessagesHook.watchActivity(activity);
                    }
                });
        int pauses = hookDirectLifecycle(bridge, classLoader,
                "DirectThreadController.onPause", new XC_MethodHook() {
                    @Override protected void afterHookedMethod(MethodHookParam param) { ps.reso.instaeclipse.utils.core.JitGuard.tick(param.method);
                        Object fragment = param.thisObject;
                        Activity activity = fragmentActivity(fragment);
                        if (activity == null) return;
                        synchronized (foregroundThreads) {
                            ForegroundThread current = foregroundThreads.get(activity);
                            if (current != null && current.fragment.get() == fragment) {
                                foregroundThreads.remove(activity);
                            }
                        }
                    }
                });
        threadsTracked = resumes > 0;
        ModuleLog.detail("(IE|HideChats) foreground tracker hooked resume=" + resumes
                + " pause=" + pauses);
    }

    private static int hookDirectLifecycle(DexKitBridge bridge, ClassLoader classLoader,
                                           String anchor, XC_MethodHook hook) {
        int count = 0;
        for (Method method : directLifecycle(bridge, classLoader, anchor)) {
            try {
                XposedBridge.hookMethod(method, hook);
                count++;
            } catch (Throwable ignored) {}
        }
        return count;
    }

    private static List<Method> directLifecycle(DexKitBridge bridge, ClassLoader classLoader, String anchor) {
        try {
            return KeepUnsentMessagesHook.cachedMethods("HideChats_" + anchor, classLoader,
                    () -> KeepUnsentMessagesHook.methodsOf(DexKitQueryCache.findMethod(bridge, FindMethod.create().matcher(
                            MethodMatcher.create().usingStrings(anchor).paramCount(0).returnType("void"))), classLoader));
        } catch (Throwable t) {
            ModuleLog.detail("(IE|HideChats) lifecycle " + anchor + " failed: " + t.getMessage());
            return Collections.emptyList();
        }
    }

    private static void rememberRows(List<?> rows, Object threadStore) {
        Map<String, String> titles = new LinkedHashMap<>();
        for (Object row : rows) {
            String id = threadIdOfRow(row);
            if (id != null) titles.merge(id, threadTitleOfRow(row, threadStore), (was, now) -> now.isEmpty() ? was : now);
        }
        knownThreads.rows(UnsentIdentity.account(threadStore), titles, HideChatsHook::selected);
    }

    private static boolean selected(String id) {
        return HiddenThreads.isHidden(id) || LockedThreads.contains(id);
    }

    public static Map<String, String> knownThreads() {
        Activity top = UIHookManager.topActivity();
        Map<String, String> chats = knownThreads.list(top == null ? null : currentAccount(top));
        for (Map.Entry<String, String> chat : chats.entrySet()) {
            if (chat.getValue().isEmpty()) chat.setValue(ThreadNames.get(chat.getKey()));
        }
        return chats;
    }

    private static boolean rememberThread(Activity activity, String id, String title) {
        if (id == null) return false;
        String account = knownThreads.has(id) ? null : currentAccount(activity);
        String cleanTitle = knownThreads.opened(account, id, title);
        if (cleanTitle.isEmpty()) return false;
        ThreadNames.put(id, cleanTitle);
        return true;
    }

    public static String knownThreadTitle(String id) {
        return knownThreads.title(id);
    }

    /** Finds the DirectThreadKey on an inbox row and returns its canonical native thread ID. */
    static String threadIdOfRow(Object row) {
        if (row == null) return null;
        try {
            List<Field> fields = threadKeyFields.computeIfAbsent(row.getClass(), rowType -> {
                List<Field> matches = new ArrayList<>();
                for (Class<?> type = rowType; type != null && type != Object.class; type = type.getSuperclass()) {
                    for (Field field : type.getDeclaredFields()) {
                        if (!Modifier.isStatic(field.getModifiers())
                                && field.getType().getName().equals(UnsentIdentity.THREAD)) {
                            field.setAccessible(true);
                            matches.add(field);
                        }
                    }
                }
                return matches;
            });
            for (Field field : fields) {
                String id = UnsentIdentity.thread(field.get(row));
                if (id != null) return id;
            }
        } catch (Throwable ignored) {}
        return null;
    }

    private static void resolveTitleFormatter(DexKitBridge bridge, ClassLoader loader) {
        try {
            List<Method> found = KeepUnsentMessagesHook.cachedMethods("HideChats_titleFormatter_v1", loader, () -> {
                MethodDataList formatters = DexKitQueryCache.findMethod(bridge, FindMethod.create().matcher(MethodMatcher.create()
                        .paramTypes("android.content.Context", UnsentIdentity.SESSION, "java.lang.String", "java.util.List", "boolean")
                        .returnType("java.lang.String")));
                List<Method> callers = new ArrayList<>();
                if (formatters.size() == 1) {
                    String owner = formatters.get(0).getClassName();
                    for (MethodData caller : DexKitQueryCache.callersUncached(formatters.get(0))) {
                        if (caller.isMethod() && caller.getClassName().equals(owner)) callers.add(caller.getMethodInstance(loader));
                    }
                }
                Method pick = TitleFormatterPick.pick(KeepUnsentMessagesHook.methodsOf(formatters, loader), callers);
                return pick == null ? List.of() : List.of(pick);
            });
            titleFormatter = found.size() == 1 ? found.get(0) : null;
            if (titleFormatter != null) titleFormatter.setAccessible(true);
            ModuleLog.detail("(IE|HideChats) title formatter " + (titleFormatter == null ? "missing"
                    : titleFormatter.getDeclaringClass().getName() + "." + titleFormatter.getName()));
        } catch (Throwable t) {
            ModuleLog.detail("(IE|HideChats) title formatter lookup failed: " + t);
        }
    }

    private static String threadTitleOfRow(Object row, Object threadStore) {
        Method formatter = titleFormatter;
        if (row == null || formatter == null || !formatter.getParameterTypes()[2].isInstance(row)) return "";
        try {
            Context context = AndroidAppHelper.currentApplication();
            Object session = UnsentIdentity.directValue(threadStore, formatter.getParameterTypes()[1]);
            if (context == null || session == null) return "";
            Object value = formatter.invoke(null, context, session, row);
            return value instanceof String ? ((String) value).trim() : "";
        } catch (Throwable ignored) {
            return "";
        }
    }

    private void installHeaderMenu(ClassLoader classLoader) {
        // InstagramMainActivity declares no onResume on 446 (IgFragmentActivity does), so a plain
        // findAndHookMethod throws there; LifecycleHookUtil hooks the declaring class instead.
        for (String activity : new String[]{"com.instagram.modal.ModalActivity",
                "com.instagram.mainactivity.InstagramMainActivity"}) {
            try {
                LifecycleHookUtil.hookAfter(classLoader, activity, "onResume",
                        (a, param) -> a.runOnUiThread(() -> registerListener(a)));
            } catch (Throwable t) {
                ModuleLog.detail("(IE|HideChats) hook " + activity + " failed (chat tracker still used): " + t.getMessage());
            }
        }
        // A chat opened inside the main activity is a fragment transaction (no activity onResume);
        // also the fallback signal when the DirectThreadController anchor is gone.
        CurrentThreadTracker.install(classLoader);
        ThreadHeaderInjector.install(classLoader);
        ThreadHeaderInjector.register("hide", true, (a, threadId) -> {
            if (FeatureFlags.hideSpecificChats || hiddenOnlyMode
                    || layoutListeners.containsKey(a.getWindow().getDecorView())) tryInject(a);
            return true;
        });
        ThreadHeaderInjector.register("lock", true, HideChatsHook::lockEntry);
        CurrentThreadTracker.addListener((a, threadId) -> {
            boolean chat = !LegacyPaths.has(LegacyPaths.HEADER)
                    && CurrentThreadTracker.fragment(a, HideChatsHook::chatScreen) != null;
            if (chat || FeatureFlags.hideSpecificChats) registerListener(a);
            if (chat) LockDirectMessagesHook.watchActivity(a);
        });
        ModuleLog.detail("(IE|HideChats) chat menu installed");
    }

    @SuppressLint("DiscouragedApi")
    private void ensureIds(Activity activity) {
        if (inboxActionBarId != 0) return;
        inboxActionBarId = activity.getResources().getIdentifier("direct_inbox_action_bar", "id",
                activity.getPackageName());
    }

    private void registerListener(Activity activity) {
        try {
            ensureIds(activity);
            registerLifecycle(activity);
            tryInject(activity);
            View decor = activity.getWindow().getDecorView();
            if (!watchedRoots.containsKey(decor)) watchedRoots.put(decor, new WeakReference<>(activity));
            syncLayoutListener(activity);
        } catch (Throwable t) {
            ModuleLog.warn("(IE|HideChats) Could not add the hide option on this screen: " + t.getClass().getSimpleName() + ": " + t.getMessage());
        }
    }

    private void syncLayoutListener(Activity activity) {
        try {
            View decor = activity.getWindow().getDecorView();
            ViewTreeObserver.OnGlobalLayoutListener listener = layoutListeners.get(decor);
            if ((FeatureFlags.hideSpecificChats || hiddenOnlyMode) == (listener != null)) return;
            if (listener != null) {
                layoutListeners.remove(decor);
                decor.getViewTreeObserver().removeOnGlobalLayoutListener(listener);
                return;
            }
            WeakReference<Activity> owner = new WeakReference<>(activity);
            listener = () -> {
                Activity live = owner.get();
                if (live != null && !ThreadHeaderInjector.handled(live.getWindow().getDecorView(), "hide")) tryInject(live);
            };
            decor.getViewTreeObserver().addOnGlobalLayoutListener(listener);
            layoutListeners.put(decor, listener);
        } catch (Throwable t) {
            ModuleLog.detail("(IE|HideChats) layout listener update failed: " + t.getMessage());
        }
    }

    public static void refreshVisibleButtons() {
        new android.os.Handler(android.os.Looper.getMainLooper()).post(() -> {
            if (activeHook == null) return;
            for (WeakReference<Activity> reference : new ArrayList<>(watchedRoots.values())) {
                Activity activity = reference.get();
                if (activity != null && !activity.isDestroyed()) {
                    activeHook.refreshInbox(activity);
                    activeHook.tryInject(activity);
                    activeHook.syncLayoutListener(activity);
                }
            }
        });
    }

    private static void registerLifecycle(Activity activity) {
        startedActivities.add(activity);
        if (lifecycleRegistered) return;
        Application application = activity.getApplication();
        if (application == null) return;
        application.registerActivityLifecycleCallbacks(new Application.ActivityLifecycleCallbacks() {
            @Override public void onActivityStarted(Activity started) { startedActivities.add(started); }

            @Override public void onActivityStopped(Activity stopped) {
                startedActivities.remove(stopped);
                if (!startedActivities.isEmpty() || stopped.isChangingConfigurations() || !hiddenOnlyMode) return;
                hiddenOnlyMode = false;
                hiddenAccount = null;
                syncRecents(stopped);
                ModuleLog.line("(IE|HideChats) Hidden chats locked again because Instagram went to the background");
            }

            @Override public void onActivityResumed(Activity resumed) {
                if (activeHook == null || !anyInboxStale()) return;
                resumed.runOnUiThread(() -> {
                    activeHook.refreshInbox(resumed);
                    activeHook.tryInject(resumed);
                });
            }

            @Override public void onActivityCreated(Activity ignored, Bundle state) {}
            @Override public void onActivityPaused(Activity ignored) {}
            @Override public void onActivitySaveInstanceState(Activity ignored, Bundle state) {}
            @Override public void onActivityDestroyed(Activity destroyed) {
                startedActivities.remove(destroyed);
                inboxControls.remove(destroyed);
                foregroundThreads.remove(destroyed);
                foregroundInboxes.remove(destroyed);
                menuBindings.remove(destroyed);
                lockBindings.remove(destroyed);
                tapGestures.remove(destroyed);
            }
        });
        lifecycleRegistered = true;
    }

    /** Runs on every layout of a watched window, so it returns at once outside an open chat. */
    private boolean tryInject(Activity activity) {
        try {
            View decor = activity.getWindow().getDecorView();
            updateInboxControl(activity, decor);
            if (!FeatureFlags.hideSpecificChats || !FeatureFlags.showHideChatButton) {
                if (menuBindings.remove(activity) != null) ThreadHeaderMenu.remove(activity, "hide");
                return false;
            }
            MenuBinding bound = menuBindings.get(activity);
            View shown = bound == null ? null : bound.shownHeader(trackedThreadId(activity));
            if (shown != null && ThreadHeaderMenu.has(activity, "hide")) {
                if (!bound.titled) bound.titled = rememberThread(activity, bound.threadId, threadTitle(shown));
                return true;
            }
            ForegroundThread foreground = currentForegroundThread(activity);
            // Without the 446 lifecycle signal, the shared tracker says whether any chat is open.
            if (foreground == null && (threadsTracked || CurrentThreadTracker.currentThreadId() == null)) return false;
            View header = ThreadHeaderMenu.shownHeader(activity);
            if (header == null) return false;
            String threadId = foreground != null ? foreground.threadId : resolveVisibleThreadId(activity, header);
            if (threadId == null) return false;

            boolean titled = rememberThread(activity, threadId, threadTitle(header));
            boolean hide = !HiddenThreads.isHidden(threadId);
            if (bound != null && bound.header.get() == header && threadId.equals(bound.threadId)
                    && bound.hide == hide && ThreadHeaderMenu.has(activity, "hide")) {
                bound.titled = titled;
                return true;
            }

            // The entry is bound to this chat and to the action its label shows; a click after
            // the screen moved to another chat is rejected instead of hiding or unhiding that one.
            final String boundId = threadId;
            final View boundHeader = header;
            if (!ThreadHeaderMenu.add(activity, "hide",
                    text(activity, hide ? R.string.ig_hide_chat : R.string.ig_unhide_chat),
                    () -> onHideEntry(activity, boundId, boundHeader, hide))) return false;
            menuBindings.put(activity, new MenuBinding(header, threadId, hide, titled));
            return true;
        } catch (Throwable t) {
            ModuleLog.detail("(IE|HideChats) inject failed: " + t.getMessage());
            return false;
        }
    }

    private void onHideEntry(Activity activity, String boundId, View boundHeader, boolean hide) {
        ForegroundThread liveForeground = currentForegroundThread(activity);
        String liveId;
        if (!LegacyPaths.has(LegacyPaths.HEADER)) liveId = liveForeground == null ? null : liveForeground.threadId;
        else liveId = liveForeground == null
                ? resolveVisibleThreadId(activity, boundHeader)
                : directThreadId(liveForeground.fragment.get());
        if (!boundId.equals(liveId)) {
            menuBindings.remove(activity);
            tryInject(activity);
            IeToast.makeText(activity, text(activity, R.string.ig_hide_chat_no_thread), Toast.LENGTH_SHORT).show();
            ModuleLog.detail("(IE|HideChats) rejected stale menu binding");
            return;
        }
        String liveTitle = knownThreadTitle(liveId);
        if (liveTitle.isEmpty()) liveTitle = threadTitle(boundHeader);
        HiddenThreads.setHidden(liveId, liveTitle, hide);
        rememberThread(activity, liveId, liveTitle);
        // Refreshes the inbox and re-adds this entry with the opposite label.
        refreshVisibleButtons();
        IeToast.makeText(activity, text(activity, hide ? R.string.ig_hide_chat_hidden : R.string.ig_hide_chat_unhidden),
                Toast.LENGTH_SHORT).show();
        ModuleLog.line("(IE|HideChats) " + (hide ? "Chat hidden" : "Chat unhidden") + " (thread " + liveId + ")");
    }

    private static boolean lockEntry(Activity activity, String openThread) {
        View header = ThreadHeaderMenu.shownHeader(activity);
        if (header == null) return false;
        String threadId = openThread != null ? openThread : resolveThreadId(activity);
        if (threadId == null) return false;
        boolean passcode = LockFlows.hasPasscode(), listed = LockedThreads.contains(threadId);
        boolean on = FeatureFlags.lockSpecificMessages;
        int state = (passcode ? 1 : 0) | (listed ? 2 : 0) | (on ? 4 : 0);
        LockBinding bound = lockBindings.get(activity);
        if (bound != null && bound.same(header, threadId, state, ThreadHeaderMenu.revision())) return true;
        LockChatEntry entry = LockChatEntry.of(passcode, listed, on, ThreadHeaderMenu.hasOthers(activity, "lock"));
        if (!entry.shown) ThreadHeaderMenu.remove(activity, "lock");
        else if (!ThreadHeaderMenu.add(activity, "lock", text(activity, entry.label),
                () -> onLockEntry(activity, threadId, header))) return false;
        lockBindings.put(activity, new LockBinding(new WeakReference<>(header), threadId, state, ThreadHeaderMenu.revision()));
        return true;
    }

    private static LockChatEntry lockState(Activity activity, String threadId) {
        return LockChatEntry.of(LockFlows.hasPasscode(), LockedThreads.contains(threadId),
                FeatureFlags.lockSpecificMessages, ThreadHeaderMenu.hasOthers(activity, "lock"));
    }

    private static void onLockEntry(Activity activity, String boundId, View header) {
        if (!boundId.equals(resolveThreadId(activity))) {
            lockBindings.remove(activity);
            IeToast.makeText(activity, text(activity, R.string.ig_hide_chat_no_thread), Toast.LENGTH_SHORT).show();
            ModuleLog.detail("(IE|HideChats) rejected stale lock entry");
            return;
        }
        String known = knownThreadTitle(boundId);
        String title = known.isEmpty() ? threadTitle(header) : known;
        switch (lockState(activity, boundId).step) {
            case SET_PASSCODE -> LockFlows.setPasscode(activity, () -> lockChat(activity, boundId, title), null);
            case LOCK -> lockChat(activity, boundId, title);
            case AUTHENTICATE -> LockFlows.authenticate(activity, text(activity, R.string.ig_lock_change_locks_prompt),
                    "settings", () -> unlockChat(activity, boundId, title));
            case UNLOCK -> unlockChat(activity, boundId, title);
        }
    }

    private static void lockChat(Activity activity, String id, String title) {
        boolean turnedOn = LockedThreads.lockChat(id, title);
        lockBindings.remove(activity);
        lockEntry(activity, id);
        IeToast.makeText(activity, text(activity, turnedOn ? R.string.ig_lock_chat_locked_on : R.string.ig_lock_chat_locked),
                Toast.LENGTH_SHORT).show();
        ModuleLog.line("(IE|HideChats) Chat locked (thread " + id + ")" + (turnedOn ? "; Lock chosen chats turned on" : ""));
    }

    private static void unlockChat(Activity activity, String id, String title) {
        LockedThreads.set(id, title, false);
        lockBindings.remove(activity);
        lockEntry(activity, id);
        IeToast.makeText(activity, text(activity, R.string.ig_lock_chat_unlocked), Toast.LENGTH_SHORT).show();
        ModuleLog.line("(IE|HideChats) Chat unlocked (thread " + id + ")");
    }

    @SuppressLint("RtlHardcoded") // The requested gesture is on the physical left in every locale.
    private void updateInboxControl(Activity activity, View decor) {
        if (!FeatureFlags.hideSpecificChats && hiddenOnlyMode) {
            setHiddenOnlyMode(activity, false);
        }
        Rect area = hiddenOnlyMode ? inboxGestureBounds(activity) : null;
        InboxControlBinding binding = inboxControls.get(activity);
        if (area == null) {
            if (binding != null) binding.control.setVisibility(View.GONE);
            return;
        }
        if (!(decor instanceof FrameLayout content)) return;
        if (binding == null || binding.control.getParent() != content) {
            ImageView control = new ImageView(activity);
            control.setTag(INBOX_CONTROL_TAG);
            control.setScaleType(ImageView.ScaleType.FIT_CENTER);
            control.setBackgroundColor(Color.TRANSPARENT);
            control.setClickable(true);
            control.setFocusable(true);
            control.setElevation(dp(activity, 24));
            binding = new InboxControlBinding(control);
            control.setOnClickListener(view -> setHiddenOnlyMode(activity, false));
            renderInboxControl(activity, control);
            content.addView(control, new FrameLayout.LayoutParams(area.width(), area.height(),
                    Gravity.TOP | Gravity.LEFT));
            inboxControls.put(activity, binding);
        }
        View accountName = accountName(activity, decor);
        IgTint.copy(accountName, binding.control);
        int[] contentLocation = new int[2];
        content.getLocationOnScreen(contentLocation);
        ViewGroup.LayoutParams params = binding.control.getLayoutParams();
        if (params.width != area.width() || params.height != area.height()) {
            params.width = area.width();
            params.height = area.height();
            binding.control.setLayoutParams(params);
        }
        int nameMiddle = accountNameMiddle(accountName);
        placeBackArrow(activity, binding.control, area.width(), area.height(),
                nameMiddle < 0 ? -1 : nameMiddle - area.top);
        binding.control.setX(area.left - contentLocation[0]);
        binding.control.setY(area.top - contentLocation[1]);
        binding.control.setVisibility(View.VISIBLE);
    }

    private static void renderInboxControl(Activity activity, ImageView control) {
        control.setImageDrawable(ModuleResources.icon(activity, R.drawable.ic_arrow_back));
        control.setContentDescription(text(activity, R.string.ig_hidden_inbox_show_all));
        control.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_YES);
    }

    /**
     * Pads the corner control so that its fitted arrow gets the size and spot described above;
     * {@code middle} is the account name's middle from the corner's top, or -1.
     */
    private static void placeBackArrow(Activity activity, ImageView control, int width, int height, int middle) {
        int size = Math.max(dp(activity, 16), Math.min(dp(activity, BACK_ARROW_DP), height - dp(activity, 28))
                + dp(activity, BACK_ARROW_GROW_DP));
        int left = Math.max(0, Math.min(width - size, dp(activity, BACK_ARROW_CENTER_X_DP) - size / 2));
        int top = Math.max(0, Math.min(height - size, middle < 0 ? (height - size) / 2 : middle - size / 2));
        control.setPadding(left, top, Math.max(0, width - left - size), Math.max(0, height - top - size));
    }

    private static View accountName(Activity activity, View decor) {
        View bar = ThreadHeaderMenu.findShown(decor, inboxActionBarId);
        if (bar == null) return null;
        for (String name : ACCOUNT_NAME_IDS) {
            int id = ps.reso.instaeclipse.utils.ui.ResIds.id(activity, name);
            View title = id == 0 ? null : bar.findViewById(id);
            if (title != null && title.isShown() && title.getHeight() != 0) return title;
        }
        return null;
    }

    private static int accountNameMiddle(View title) {
        if (title == null) return -1;
        int[] at = new int[2];
        title.getLocationOnScreen(at);
        return at[1] + title.getHeight() / 2;
    }

    private static void setHiddenOnlyMode(Activity activity, boolean enabled) {
        if (hiddenOnlyMode == enabled) return;
        if (enabled) {
            Object consumer = findInboxConsumer(activity);
            NativeInbox state;
            synchronized (nativeInboxes) { state = nativeInboxes.get(consumer); }
            if (state == null || state.account == null) {
                // Reached only without the native row path, or before this inbox delivered rows.
                ModuleLog.warn("(IE|HideChats) Could not open hidden chats: " + (inboxPath == null
                        ? "not available on this Instagram version" : "the chat list has not loaded yet, try again"));
                ModuleLog.detail("(IE|HideChats) hidden inbox unavailable: path=" + (inboxPath != null)
                        + " consumer=" + (consumer != null) + " rows=" + (state != null));
                IeToast.makeText(activity, text(activity, R.string.ig_hidden_inbox_unavailable),
                        Toast.LENGTH_SHORT).show();
                return;
            }
            hiddenAccount = state.account;
        } else hiddenAccount = null;
        hiddenOnlyMode = enabled;
        syncRecents(activity);
        if (activeHook != null) {
            activeHook.refreshInbox(activity);
            activeHook.tryInject(activity);
        }
        ModuleLog.line("(IE|HideChats) " + (enabled ? "Hidden chats opened" : "Hidden chats closed"));
    }

    private static void syncRecents(Activity activity) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return;
        Activity hidden = recentsHidden == null ? null : recentsHidden.get();
        if (HiddenOnlyRecents.hideFromRecents(Build.VERSION.SDK_INT, hiddenOnlyMode)) {
            if (hidden == activity) return;
            if (hidden != null) hidden.setRecentsScreenshotEnabled(true);
            activity.setRecentsScreenshotEnabled(false);
            recentsHidden = new WeakReference<>(activity);
        } else if (hidden != null) {
            hidden.setRecentsScreenshotEnabled(true);
            recentsHidden = null;
        }
    }

    /** The foreground inbox's row consumer once it has delivered rows; null otherwise. */
    private static Object findInboxConsumer(Activity activity) {
        NativeInboxPath path = inboxPath;
        if (activity == null || activity.isDestroyed() || path == null) return null;
        try {
            WeakReference<Object> current = foregroundInboxes.get(activity);
            Object fragment = current == null ? null : current.get();
            if (!Boolean.TRUE.equals(invokeNoArg(fragment, "isResumed"))) {
                fragment = findVisibleModuleFragment(
                        invokeNoArg(activity, "getSupportFragmentManager"), "direct_inbox");
            }
            if (fragment == null) return null;
            if (fragment.getClass() != inboxGetterOwner) {
                inboxGetterOwner = fragment.getClass();
                inboxGetter = NativeInboxPath.getter(inboxGetterOwner, path.generator);
                if (inboxGetter == null) {
                    ModuleLog.detail("(IE|HideChats) no generator getter on " + inboxGetterOwner.getName());
                }
            }
            Object generator = inboxGetter == null ? null : inboxGetter.generatorOf(fragment);
            if (generator == null) return null;
            synchronized (nativeInboxes) {
                // Of the generator's stream consumers, only the row consumer captured a list.
                for (Object consumer : path.consumersOf(generator)) {
                    if (nativeInboxes.containsKey(consumer)) return consumer;
                }
            }
            return null;
        } catch (Throwable t) {
            ModuleLog.detail("(IE|HideChats) inbox lookup failed: " + t.getMessage());
            return null;
        }
    }

    private static boolean anyInboxStale() {
        synchronized (nativeInboxes) {
            for (NativeInbox state : nativeInboxes.values()) {
                if (state.gate.stale(state.revision())) return true;
            }
        }
        return false;
    }

    private void refreshInbox(Activity activity) {
        if (!hiddenOnlyMode && !anyInboxStale()) return;
        Object consumer = findInboxConsumer(activity);
        if (consumer == null) return;
        try {
            NativeInbox state;
            synchronized (nativeInboxes) { state = nativeInboxes.get(consumer); }
            if (state == null) return; // The first native submission will apply the current mode.
            if (hiddenOnlyMode && !java.util.Objects.equals(hiddenAccount, state.account)) {
                hiddenOnlyMode = false;
                hiddenAccount = null;
                syncRecents(activity);
            }
            long revision = state.revision();
            if (!state.gate.stale(revision)) return;
            List<Object> visible = state.visible();
            // Bypass our capture hook: a filtered replay must never replace the complete source.
            XposedBridge.invokeOriginalMethod(inboxPath.submit, consumer, new Object[]{visible});
            state.gate.applied(revision);
            ModuleLog.probe("(IE|HideChats) local inbox submitted rows=" + visible.size()
                    + " hiddenOnly=" + hiddenOnlyMode);
        } catch (Throwable t) {
            ModuleLog.warn("(IE|HideChats) Could not update the chat list: " + t.getClass().getSimpleName() + ": " + t.getMessage());
        }
    }

    private static String trackedThreadId(Activity activity) {
        if (!LegacyPaths.has(LegacyPaths.HEADER)) {
            ForegroundThread current = currentForegroundThread(activity);
            return current == null ? null : current.threadId;
        }
        if (!threadsTracked) return CurrentThreadTracker.currentThreadId();
        synchronized (foregroundThreads) {
            ForegroundThread current = foregroundThreads.get(activity);
            return current == null ? null : current.threadId;
        }
    }

    /** Returns a binding only while its original Direct Fragment is still resumed in this Activity. */
    private static ForegroundThread currentForegroundThread(Activity activity) {
        if (!LegacyPaths.has(LegacyPaths.HEADER)) {
            Object fragment = CurrentThreadTracker.fragment(activity, HideChatsHook::chatScreen);
            if (fragment == null || !Boolean.TRUE.equals(invokeNoArg(fragment, "isResumed"))
                    || fragmentActivity(fragment) != activity) return null;
            String threadId = CurrentThreadTracker.threadIdOf(fragment);
            if (threadId == null && chatScreens.isEmpty()) threadId = CurrentThreadTracker.currentThreadId(activity);
            return threadId == null ? null : new ForegroundThread(fragment, threadId);
        }
        ForegroundThread current;
        synchronized (foregroundThreads) {
            current = foregroundThreads.get(activity);
        }
        if (current == null) return null;
        Object fragment = current.fragment.get();
        boolean valid = fragment != null
                && Boolean.TRUE.equals(invokeNoArg(fragment, "isResumed"))
                && fragmentActivity(fragment) == activity;
        if (valid) return current;
        synchronized (foregroundThreads) {
            if (foregroundThreads.get(activity) == current) foregroundThreads.remove(activity);
        }
        return null;
    }

    private static boolean chatScreen(Object fragment) {
        if (chatScreens.isEmpty()) return true;
        for (Class<?> screen : chatScreens) if (screen.isInstance(fragment)) return true;
        return false;
    }

    private static String directThreadId(Object fragment) {
        Object key = findDirectThreadKey(fragment, 0,
                Collections.newSetFromMap(new IdentityHashMap<>()));
        return UnsentIdentity.thread(key);
    }

    private static Activity fragmentActivity(Object fragment) {
        Object value = invokeNoArg(fragment, "getActivity");
        return value instanceof Activity ? (Activity) value : null;
    }

    private static String text(Activity activity, int resource) {
        return ps.reso.instaeclipse.utils.i18n.I18n.t(activity, resource);
    }

    static String threadTitle(View header) {
        if (!(header instanceof ViewGroup)) return "";
        List<String> texts = new ArrayList<>();
        collectTexts((ViewGroup) header, texts);
        return texts.isEmpty() ? "" : texts.get(0);
    }

    private static void collectTexts(ViewGroup group, List<String> out) {
        for (int i = 0; i < group.getChildCount() && out.size() < 12; i++) {
            View child = group.getChildAt(i);
            if (child.getVisibility() != View.VISIBLE) continue;
            if (child instanceof android.widget.TextView textView) {
                CharSequence text = textView.getText();
                if (text != null && !text.toString().trim().isEmpty()) out.add(text.toString().trim());
            } else if (child instanceof ViewGroup) {
                collectTexts((ViewGroup) child, out);
            }
        }
    }

    /** Resolves only from the visible Fragment whose root owns this exact header. */
    private static String resolveVisibleThreadId(Activity activity, View header) {
        try {
            Object fragmentManager = invokeNoArg(activity, "getSupportFragmentManager");
            Object fragment = findOwningFragment(fragmentManager, header);
            Object key = findDirectThreadKey(fragment, 0,
                    Collections.newSetFromMap(new IdentityHashMap<>()));
            if (key == null) {
                // The header can be owned by a nested UI Fragment while its parent direct_thread
                // Fragment owns the controller and key.
                fragment = findVisibleDirectFragment(fragmentManager);
                key = findDirectThreadKey(fragment, 0,
                        Collections.newSetFromMap(new IdentityHashMap<>()));
            }
            return UnsentIdentity.thread(key);
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static Object findOwningFragment(Object manager, View header) {
        Object value = invokeNoArg(manager, "getFragments");
        if (!(value instanceof List<?> fragments)) return null;
        for (int i = fragments.size() - 1; i >= 0; i--) {
            Object fragment = fragments.get(i);
            View root = fragmentView(fragment);
            if (root == null || !root.isShown() || !contains(root, header)) continue;
            Object childManager = invokeNoArg(fragment, "getChildFragmentManager");
            Object child = findOwningFragment(childManager, header);
            return child != null ? child : fragment;
        }
        return null;
    }

    /** Safe fallback for action bars attached outside the Direct Fragment's own root. */
    private static Object findVisibleDirectFragment(Object manager) {
        return findVisibleModuleFragment(manager, "direct_thread");
    }

    /** Shared by the gate and gesture; invisible cached fragments must not identify a screen. */
    public static String visibleModule(Activity activity) {
        if (currentForegroundThread(activity) != null) return "direct_thread";
        WeakReference<Object> inbox = foregroundInboxes.get(activity);
        if (inbox != null && Boolean.TRUE.equals(invokeNoArg(inbox.get(), "isResumed"))) {
            return "direct_inbox";
        }
        Object fragment = topResumedFragment(invokeNoArg(activity, "getSupportFragmentManager"));
        Object module = invokeNoArg(fragment, "getModuleName");
        return module instanceof String ? (String) module : "";
    }

    public static String currentThreadId(Activity activity) {
        ForegroundThread tracked = currentForegroundThread(activity);
        if (tracked != null) return tracked.threadId;
        Object fragment = findVisibleDirectFragment(invokeNoArg(activity, "getSupportFragmentManager"));
        return LegacyPaths.has(LegacyPaths.HEADER) ? directThreadId(fragment) : CurrentThreadTracker.threadIdOf(fragment);
    }

    /** The open chat's id for {@code activity} (shared with ReadReceiptChatHook), or null: this
     *  activity's resumed chat fragment, else the shared fragment tracker. Never a scan of the
     *  whole activity, which in the main activity can return another chat's key. */
    public static String resolveThreadId(Activity activity) {
        String id = activity == null ? null : currentThreadId(activity);
        return id != null ? id : CurrentThreadTracker.currentThreadId();
    }

    public static String currentAccount(Activity activity) {
        Object fragment = topResumedFragment(invokeNoArg(activity, "getSupportFragmentManager"));
        String account = UnsentIdentity.account(fragment);
        return account == null ? UnsentIdentity.account(activity) : account;
    }

    private static Object topResumedFragment(Object manager) {
        Object value = invokeNoArg(manager, "getFragments");
        if (!(value instanceof List<?> fragments)) return null;
        for (int i = fragments.size() - 1; i >= 0; i--) {
            Object fragment = fragments.get(i);
            if (!Boolean.TRUE.equals(invokeNoArg(fragment, "isResumed"))) continue;
            View root = fragmentView(fragment);
            if (root == null || !root.isShown()) continue;
            Object child = topResumedFragment(invokeNoArg(fragment, "getChildFragmentManager"));
            if (child != null) return child;
            if (invokeNoArg(fragment, "getModuleName") instanceof String) return fragment;
        }
        return null;
    }

    private static Object findVisibleModuleFragment(Object manager, String moduleName) {
        Object value = invokeNoArg(manager, "getFragments");
        if (!(value instanceof List<?> fragments)) return null;
        for (int i = fragments.size() - 1; i >= 0; i--) {
            Object fragment = fragments.get(i);
            Object child = findVisibleModuleFragment(
                    invokeNoArg(fragment, "getChildFragmentManager"), moduleName);
            if (child != null) return child;
            View root = fragmentView(fragment);
            if (root == null || !root.isShown()
                    || !Boolean.TRUE.equals(invokeNoArg(fragment, "isResumed"))) continue;
            if (moduleName.equals(invokeNoArg(fragment, "getModuleName"))) return fragment;
        }
        return null;
    }

    private static View fragmentView(Object fragment) {
        Object value = invokeNoArg(fragment, "getView");
        return value instanceof View ? (View) value : null;
    }

    private static boolean contains(View root, View child) {
        for (View current = child; current != null;
             current = current.getParent() instanceof View ? (View) current.getParent() : null) {
            if (current == root) return true;
        }
        return false;
    }

    /** Searches the owning Instagram fragment/controller only; framework/global state is pruned. */
    private static Object findDirectThreadKey(Object object, int depth, Set<Object> seen) {
        if (object == null || depth > 2 || !seen.add(object)) return null;
        String className = object.getClass().getName();
        if (className.contains("DirectThreadKey")) return object;
        if (object instanceof Bundle bundle) {
            for (String key : bundle.keySet()) {
                Object found = findDirectThreadKey(bundle.get(key), depth + 1, seen);
                if (found != null) return found;
            }
            return null;
        }
        if (!(className.startsWith("X.") || className.startsWith("com.instagram."))) return null;

        for (Class<?> type = object.getClass(); type != null && type != Object.class;
             type = type.getSuperclass()) {
            for (Method method : type.getDeclaredMethods()) {
                if (Modifier.isStatic(method.getModifiers()) || method.getParameterTypes().length != 0
                        || !method.getReturnType().getName().contains("DirectThreadKey")) continue;
                try {
                    method.setAccessible(true);
                    Object result = method.invoke(object);
                    if (result != null) return result;
                } catch (Throwable ignored) {}
            }
            for (Field field : type.getDeclaredFields()) {
                if (Modifier.isStatic(field.getModifiers()) || field.getType().isPrimitive()) continue;
                try {
                    field.setAccessible(true);
                    Object value = field.get(object);
                    if (value == null) continue;
                    if (value.getClass().getName().contains("DirectThreadKey")) return value;
                    String valueClass = value.getClass().getName();
                    if (value instanceof Bundle || valueClass.startsWith("X.")
                            || valueClass.startsWith("com.instagram.")) {
                        Object found = findDirectThreadKey(value, depth + 1, seen);
                        if (found != null) return found;
                    }
                } catch (Throwable ignored) {}
            }
        }
        return null;
    }

    private static Object invokeNoArg(Object object, String name) {
        if (object == null) return null;
        try {
            Method method = noArgMethod(object.getClass(), name);
            return method == null ? null : method.invoke(object);
        } catch (Throwable ignored) {
            return null;
        }
    }

    /** The class's public no-arg method of that name, made accessible, or null; looked up once. */
    static Method noArgMethod(Class<?> type, String name) {
        return noArgMethods.computeIfAbsent(type, ignored -> new java.util.concurrent.ConcurrentHashMap<>())
                .computeIfAbsent(name, ignored -> {
                    try {
                        Method method = type.getMethod(name);
                        method.setAccessible(true);
                        return java.util.Optional.of(method);
                    } catch (ReflectiveOperationException | RuntimeException | LinkageError missing) {
                        return java.util.Optional.empty();
                    }
                }).orElse(null);
    }

    private static int dp(Activity activity, int value) {
        return Math.round(value * activity.getResources().getDisplayMetrics().density);
    }
}
