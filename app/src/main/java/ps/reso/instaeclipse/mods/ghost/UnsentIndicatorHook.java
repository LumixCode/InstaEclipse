package ps.reso.instaeclipse.mods.ghost;

import android.os.Handler;
import android.os.Looper;
import android.os.Build;
import android.view.View;
import android.view.ViewTreeObserver;

import org.luckypray.dexkit.DexKitBridge;
import org.luckypray.dexkit.query.FindMethod;
import org.luckypray.dexkit.query.matchers.MethodMatcher;
import org.luckypray.dexkit.result.MethodData;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;

import ps.reso.instaeclipse.xposedcompat.XC_MethodHook;
import ps.reso.instaeclipse.xposedcompat.XposedBridge;
import ps.reso.instaeclipse.R;
import ps.reso.instaeclipse.utils.core.HookFrames;
import ps.reso.instaeclipse.utils.feature.FeatureFlags;
import ps.reso.instaeclipse.utils.feature.FeatureStatusTracker;
import ps.reso.instaeclipse.utils.ghost.UnsentIdentity;
import ps.reso.instaeclipse.utils.ghost.UnsentLog;
import ps.reso.instaeclipse.utils.ghost.UnsentMessageIndex.Key;
import ps.reso.instaeclipse.utils.i18n.I18n;
import ps.reso.instaeclipse.utils.log.ModuleLog;

/** Marks retained messages without changing their text, copy actions or send payloads. */
final class UnsentIndicatorHook {
    private static final Map<View, Row> rows = new WeakHashMap<>();
    private static final Map<View, ViewTreeObserver.OnPreDrawListener> observed = new WeakHashMap<>();
    private static final UnsentBubbleHook bubbles = new UnsentBubbleHook();

    void install(DexKitBridge bridge, ClassLoader loader) {
        try {
            Class<?> model = findMessageClass(bridge, loader);
            List<Method> binders = KeepUnsentMessagesHook.cachedMethods("UnsentIndicator_binders2", loader,
                    () -> findBinders(bridge, loader));
            int installed = 0;
            boolean bubbleInstalled = false;
            for (Method bind : binders) {
                Class<?>[] types = bind.getParameterTypes();
                if (model == null || types.length != 2 || types[1] != int.class) continue;
                Field itemView = rowView(types[0]);
                if (itemView == null) continue;
                itemView.setAccessible(true);
                Field viewField = itemView;
                bubbleInstalled |= bubbles.install(bridge, loader, viewField);
                XposedBridge.hookMethod(bind, new XC_MethodHook() {
                    private final HookFrames.Slot frames = new HookFrames.Slot();

                    @Override protected void beforeHookedMethod(MethodHookParam param) throws Throwable { ps.reso.instaeclipse.utils.core.JitGuard.tick(param.method);
                        HookFrames.Frame frame = frames.enter();
                        View view = (View) viewField.get(param.args[0]);
                        Row old = rows.remove(view);
                        if (old != null) {
                            old.update(view);
                            old.detach(view);
                        }
                        frame.put("ie_unsent_previous_binding", bubbles.beginBind(view));
                    }

                    @Override protected void afterHookedMethod(MethodHookParam param) throws Throwable { ps.reso.instaeclipse.utils.core.JitGuard.tick(param.method);
                        HookFrames.Frame frame = frames.exit();
                        try {
                            if (param.hasThrowable() || !FeatureFlags.keepUnsentMessages) return;
                            Object holder = param.args[0];
                            Object data = UnsentIdentity.boundData(holder, model);
                            Object message = UnsentIdentity.directValue(data, model);
                            if (message == null) return;
                            String account = UnsentIdentity.account(data);
                            String thread = UnsentIdentity.thread(message);
                            if (account == null) return;
                            View view = (View) viewField.get(holder);
                            if (view == null) return;
                            bubbles.bind(view, holder);
                            List<String> ids = UnsentIdentity.messageIds(message);
                            if (ids.isEmpty()) return;
                            Row row = new Row(account, thread, ids, message,
                                    KeepUnsentMessagesHook.hasMessageText(message) ? null : new UnsentMediaMark(view));
                            rows.put(view, row);
                            observe(view);
                            row.update(view);
                        } finally {
                            bubbles.endBind(frame.get("ie_unsent_previous_binding"));
                        }
                    }
                });
                installed++;
            }
            if (installed > 0 && bubbleInstalled) FeatureStatusTracker.setHooked("UnsentIndicator");
            // Same condition FeatureManager registers the feature under.
            else if (FeatureFlags.showUnsentIndicator && FeatureFlags.keepUnsentMessages)
                FeatureStatusTracker.setBroken("UnsentIndicator");
            ModuleLog.line("(IE|UnsentIndicator) bound-row hooks=" + installed);
        } catch (Throwable t) {
            ModuleLog.line("(IE|UnsentIndicator) unavailable: " + t.getMessage());
            if (FeatureFlags.showUnsentIndicator && FeatureFlags.keepUnsentMessages)
                FeatureStatusTracker.setBroken("UnsentIndicator");
        }
    }

    /**
     * The DM adapter's (holder, position) bind. 446 logs the unseen-message impression inside it;
     * 451 moved that logging into a row helper the bind calls, so the bind is the anchor or its caller.
     */
    private static List<Method> findBinders(DexKitBridge bridge, ClassLoader loader) throws NoSuchMethodException {
        Set<Method> binders = new LinkedHashSet<>();
        for (MethodData anchor : bridge.findMethod(FindMethod.create().matcher(MethodMatcher.create()
                .usingStrings("direct_thread_unseen_message_impression", "open_thread_id").returnType("void")))) {
            if (isBind(anchor)) binders.add(anchor.getMethodInstance(loader));
            else for (MethodData caller : anchor.getCallers()) {
                if (isBind(caller)) binders.add(caller.getMethodInstance(loader));
            }
        }
        return new ArrayList<>(binders);
    }

    private static boolean isBind(MethodData method) {
        List<String> types = method.getParamTypeNames();
        return method.isMethod() && !Modifier.isStatic(method.getModifiers()) && method.getReturnTypeName().equals("void")
                && types.size() == 2 && types.get(1).equals("int");
    }

    /** The row root: RecyclerView's holder base owns the only instance View field (451 binds a subclass). */
    private static Field rowView(Class<?> holder) {
        Field found = null;
        for (Class<?> cls = holder; cls != null && cls != Object.class; cls = cls.getSuperclass()) {
            for (Field field : cls.getDeclaredFields()) {
                if (field.getType() != View.class || Modifier.isStatic(field.getModifiers())) continue;
                if (found != null) throw new IllegalStateException("Ambiguous holder view");
                found = field;
            }
        }
        return found;
    }

    private static Class<?> findMessageClass(DexKitBridge bridge, ClassLoader loader) throws Throwable {
        for (Method method : KeepUnsentMessagesHook.cachedMethods("UnsentIndicator_messageModel", loader,
                () -> KeepUnsentMessagesHook.methodsOf(bridge.findMethod(FindMethod.create().matcher(
                        MethodMatcher.create().usingStrings("item_id", "hide_in_thread"))), loader))) {
            for (Class<?> parameter : method.getParameterTypes()) {
                for (Class<?> cls = parameter; cls != null && cls != Object.class; cls = cls.getSuperclass()) {
                    for (Field field : cls.getDeclaredFields()) {
                        if (field.getType().getName().equals(UnsentIdentity.THREAD)) return cls;
                    }
                }
            }
        }
        return null;
    }

    /**
     * One pre-draw listener per recycled row view, always serving the message bound to it now.
     * A listener per message outlived its binding: RecyclerView rebinds detached views, whose
     * getViewTreeObserver() is a fresh floating observer, so removal missed and the previous
     * message kept re-marking whichever message the view showed next.
     */
    private static void observe(View view) {
        if (observed.containsKey(view)) return;
        java.lang.ref.WeakReference<View> reference = new java.lang.ref.WeakReference<>(view);
        ViewTreeObserver.OnPreDrawListener listener = () -> {
            View current = reference.get();
            Row row = current == null ? null : rows.get(current);
            if (row != null) row.update(current);
            return true;
        };
        observed.put(view, listener);
        view.getViewTreeObserver().addOnPreDrawListener(listener);
    }

    static void refreshVisibleRows() {
        new Handler(Looper.getMainLooper()).post(() -> {
            for (Map.Entry<View, Row> entry : new ArrayList<>(rows.entrySet())) {
                if (entry.getKey() != null) entry.getValue().update(entry.getKey());
            }
        });
    }

    private static final class Row {
        final String account, thread;
        final List<String> ids;
        final java.lang.ref.WeakReference<Object> message;
        final UnsentMediaMark mediaMark;
        long observedRemovals = -1, observedLog = -1;
        boolean wasEnabled, deleted;
        CharSequence originalDescription;
        String markedDescription;

        Row(String account, String thread, List<String> ids, Object message, UnsentMediaMark mediaMark) {
            this.account = account; this.thread = thread; this.ids = ids;
            this.message = new java.lang.ref.WeakReference<>(message);
            this.mediaMark = mediaMark;
        }

        Key findDeletion() {
            Key key = KeepUnsentMessagesHook.protectedIds.find(account, thread, ids);
            if (key != null && UnsentLog.findCleared(account, key.thread, ids) != null) return null;
            if (key != null) return key;
            return UnsentLog.findUnsent(account, thread, ids);
        }

        void update(View view) {
            boolean enabled = FeatureFlags.keepUnsentMessages && FeatureFlags.showUnsentIndicator;
            long removals = KeepUnsentMessagesHook.protectedIds.revision();
            long logged = UnsentLog.revision();
            boolean changed = removals != observedRemovals || logged != observedLog || enabled != wasEnabled;
            // A pending message can acquire a server ID without another removal or row bind.
            // Keep the old client alias too, so the persisted deletion still matches after restart.
            boolean identityChanged = false;
            if (deleted || changed) {
                for (String id : UnsentIdentity.messageIds(message.get())) {
                    if (!ids.contains(id)) { ids.add(id); identityChanged = true; }
                }
            }
            if (identityChanged || changed) {
                Key key = findDeletion();
                if (key != null) KeepUnsentMessagesHook.markUnsent(message.get(), key, ids);
                deleted = key != null;
                observedRemovals = KeepUnsentMessagesHook.protectedIds.revision();
                observedLog = UnsentLog.revision();
                wasEnabled = enabled;
            }
            boolean show = enabled && deleted;
            bubbles.setMarked(view, show, ids);
            // Compose media Instagram already draws red (UnsentBubbleHook) needs no second mark.
            if (mediaMark != null) mediaMark.update(show && !bubbles.marksMedia(view));
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                if (show && markedDescription == null) {
                    String label = I18n.t(view.getContext(), R.string.ig_unsent_indicator_label);
                    originalDescription = view.getStateDescription();
                    markedDescription = originalDescription == null ? label : originalDescription + ". " + label;
                    view.setStateDescription(markedDescription);
                } else if (!show) {
                    restoreDescription(view);
                }
            }
        }

        void restoreDescription(View view) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && markedDescription != null) {
                if (markedDescription.equals(view.getStateDescription())) view.setStateDescription(originalDescription);
                markedDescription = null;
            }
        }

        void detach(View view) {
            bubbles.clear(view);
            if (mediaMark != null) mediaMark.clear();
            restoreDescription(view);
        }
    }
}
