package ps.reso.instaeclipse.mods.ghost;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.view.ViewGroup;

import org.luckypray.dexkit.DexKitBridge;
import org.luckypray.dexkit.query.FindMethod;
import org.luckypray.dexkit.query.matchers.MethodMatcher;
import org.luckypray.dexkit.result.MethodData;

import java.io.ByteArrayOutputStream;
import java.lang.ref.WeakReference;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Predicate;

import ps.reso.instaeclipse.xposedcompat.XC_MethodHook;
import ps.reso.instaeclipse.xposedcompat.XposedBridge;
import ps.reso.instaeclipse.utils.core.DexKitCache;
import ps.reso.instaeclipse.utils.feature.FeatureFlags;
import ps.reso.instaeclipse.utils.feature.FeatureStatusTracker;
import ps.reso.instaeclipse.utils.ghost.CurrentThreadTracker;
import ps.reso.instaeclipse.utils.ghost.ThreadNames;
import ps.reso.instaeclipse.utils.ghost.UnsentDetail;
import ps.reso.instaeclipse.utils.ghost.UnsentIdentity;
import ps.reso.instaeclipse.utils.ghost.UnsentLog;
import ps.reso.instaeclipse.utils.ghost.UnsentMessageIndex;
import ps.reso.instaeclipse.utils.ghost.UnsentMessageIndex.Key;
import ps.reso.instaeclipse.utils.ghost.UnsentMessageStore;
import ps.reso.instaeclipse.utils.log.ModuleLog;

/**
 * Keeps locally removed messages through both live removal and thread reconciliation.
 * Removal events are scoped to account + thread + message; reconcile restores only the
 * owning store's objects. Saved copies bring back messages Instagram reloads from the server after
 * a restart. The separate indicator annotates rows without changing message text.
 * With "keep in chat" off, a removal is only logged and Instagram removes the message as usual.
 * Obfuscated store/reconcile classes are discovered from their diagnostic strings.
 */
public class KeepUnsentMessagesHook {

    private static final String REMOVE_ANCHOR = "Client context should not be null if messageId is null.";
    private static final String RECONCILE_ANCHOR =
            " is not in cache, may need to double check if this message is deleted locally or not";
    private static final String DTK = "com.instagram.model.direct.DirectThreadKey";
    private static final String MISSING = "missing";

    static final UnsentMessageIndex protectedIds = new UnsentMessageIndex(1000);
    private static Field messageText;
    private static boolean localClearingAvailable;
    private static final ThreadLocal<Boolean> clearingLocally = new ThreadLocal<>();
    private static final Map<Key, NativeThread> nativeThreads = new LinkedHashMap<>();
    private static final Map<Object, String> entryThreads = new WeakHashMap<>();
    /** Messages already described (or found not to need it) this process; see describe(). */
    private static final Set<Key> described = Collections.newSetFromMap(new ConcurrentHashMap<>());
    // Instagram's direct.db message codec; all null when discovery failed (no restart copies).
    private static Method messageTable, encodeMessage, decodeMessage, messageTime;
    private static Field messageThreadKey;

    /** Reconciliation restores objects only into the native thread entry that owned them. */
    private static final Map<Object, Map<Key, Object>> keptByThread =
            Collections.synchronizedMap(new IdentityHashMap<>());

    public void install(DexKitBridge bridge, ClassLoader classLoader) {
        resolveTextField(bridge, classLoader);
        resolveMessageCodec(bridge, classLoader);
        hookRemovePrimitive(bridge, classLoader);
        hookReconcile(bridge, classLoader);
        // Unconditional: chat export and voice downloads name their folders from ThreadNames, and
        // outside the inbox only this listener feeds it.
        CurrentThreadTracker.install(classLoader);
        CurrentThreadTracker.addListener((activity, id) -> rememberThreadName(id, activity));
        new UnsentIndicatorHook().install(bridge, classLoader);
    }

    // ── DexKit results cached per Instagram build ───────────────────────────────

    /** A DexKit search whose result {@link #cachedMethods} keeps per Instagram build. */
    interface MethodLookup { List<Method> find() throws Throwable; }

    interface FieldLookup { Field find() throws Throwable; }

    /**
     * The methods {@code lookup} finds, searched once per Instagram build and then read from
     * DexKitCache: warm launches install hooks inline in Application.attach, where a DexKit scan
     * would stall startup. A miss is cached too; a lookup that throws runs again next launch.
     */
    static List<Method> cachedMethods(String key, ClassLoader loader, MethodLookup lookup) throws Throwable {
        if (DexKitCache.isCacheValid()) {
            if (MISSING.equals(DexKitCache.loadString(key))) return Collections.emptyList();
            List<Method> cached = DexKitCache.loadMethods(key, loader);
            if (cached != null) return cached;
        }
        List<Method> found = lookup.find();
        if (found.isEmpty()) DexKitCache.saveString(key, MISSING);
        else DexKitCache.saveMethods(key, found);
        return found;
    }

    /** {@link #cachedMethods} for one field, kept as "declaringClass#name"; null when not found. */
    static Field cachedField(String key, ClassLoader loader, FieldLookup lookup) throws Throwable {
        String cached = DexKitCache.isCacheValid() ? DexKitCache.loadString(key) : null;
        if (MISSING.equals(cached)) return null;
        if (cached != null) {
            int split = cached.lastIndexOf('#');
            try {
                Field field = Class.forName(cached.substring(0, split), false, loader)
                        .getDeclaredField(cached.substring(split + 1));
                field.setAccessible(true);
                return field;
            } catch (ReflectiveOperationException | RuntimeException ignored) {} // stale entry: search again
        }
        Field found = lookup.find();
        DexKitCache.saveString(key, found == null ? MISSING
                : found.getDeclaringClass().getName() + "#" + found.getName());
        return found;
    }

    static List<Method> methodsOf(List<MethodData> found, ClassLoader loader) throws NoSuchMethodException {
        List<Method> methods = new ArrayList<>();
        for (MethodData data : found) if (data.isMethod()) methods.add(data.getMethodInstance(loader));
        return methods;
    }

    private void resolveTextField(DexKitBridge bridge, ClassLoader loader) {
        try {
            messageText = cachedField("KeepUnsent_body", loader, () -> {
                Set<Field> fields = new LinkedHashSet<>();
                for (MethodData data : bridge.findMethod(FindMethod.create().matcher(MethodMatcher.create()
                        .usingStrings("DirectMessage.updateIsMessageEmoji").paramCount(0).returnType("void")))) {
                    Class<?> model = data.getMethodInstance(loader).getDeclaringClass();
                    for (var use : data.getUsingFields()) {
                        if (!use.getField().getTypeName().equals("java.lang.String")) continue;
                        Field field = use.getField().getFieldInstance(loader);
                        // The same method also examines avatar text; only the message's own field is its body.
                        if (field.getDeclaringClass().isAssignableFrom(model)) fields.add(field);
                    }
                }
                return fields.size() == 1 ? fields.iterator().next() : null;
            });
            if (messageText != null) messageText.setAccessible(true);
            else ModuleLog.line("(IE|KeepUnsent) message body field unavailable");
        } catch (Throwable t) {
            ModuleLog.line("(IE|KeepUnsent) body lookup unavailable: " + t.getMessage());
        }
    }

    /**
     * Instagram's own row codec for direct messages (the "messages" table of direct.db), so saved
     * copies decode into exactly the objects Instagram loads from disk. The row writer reads one
     * long from the message, its timestamp, which orders restored copies, and one thread-key
     * field, which the decoder requires.
     */
    private void resolveMessageCodec(DexKitBridge bridge, ClassLoader loader) {
        try {
            for (Method decode : cachedMethods("KeepUnsent_decode", loader, () -> methodsOf(bridge.findMethod(
                    FindMethod.create().matcher(MethodMatcher.create()
                            .usingStrings("DirectMessageSQLiteTable.parseMessage").paramTypes("byte[]"))), loader))) {
                Class<?> table = decode.getDeclaringClass();
                Method accessor = null, encode = null;
                for (Method method : table.getDeclaredMethods()) {
                    if (Modifier.isStatic(method.getModifiers()) && method.getReturnType() == table
                            && method.getParameterCount() == 1
                            && method.getParameterTypes()[0].getName().equals(UnsentIdentity.SESSION)) accessor = method;
                }
                for (Class<?> c = table.getSuperclass(); c != null && encode == null; c = c.getSuperclass()) {
                    for (Method method : c.getDeclaredMethods()) {
                        if (method.getReturnType() == byte[].class && List.of(method.getParameterTypes())
                                .equals(List.of(ByteArrayOutputStream.class, Object.class))) encode = method;
                    }
                }
                List<Method> times = cachedMethods("KeepUnsent_time|" + table.getName(), loader, () -> {
                    Method time = null;
                    for (MethodData writer : rowWriters(bridge, table)) {
                        for (MethodData call : writer.getInvokes()) {
                            if (!call.getReturnTypeName().equals("long") || !call.getParamTypeNames().isEmpty()) continue;
                            Method candidate = call.getMethodInstance(loader);
                            if (!candidate.getDeclaringClass().isAssignableFrom(decode.getReturnType())) continue;
                            if (time != null && !time.equals(candidate)) return List.of(); // ambiguous message time
                            time = candidate;
                        }
                    }
                    return time == null ? List.of() : List.of(time);
                });
                Field threadKey = cachedField("KeepUnsent_threadKey|" + table.getName(), loader, () -> {
                    Field key = null;
                    for (MethodData writer : rowWriters(bridge, table)) {
                        for (var use : writer.getUsingFields()) {
                            if (!use.getField().getTypeName().equals(DTK)) continue;
                            Field candidate = use.getField().getFieldInstance(loader);
                            if (!candidate.getDeclaringClass().isAssignableFrom(decode.getReturnType())) continue;
                            if (key != null && !key.equals(candidate)) return null; // ambiguous: lend no key
                            key = candidate;
                        }
                    }
                    return key;
                });
                if (accessor == null || encode == null || times.isEmpty()) continue;
                Method time = times.get(0);
                accessor.setAccessible(true); encode.setAccessible(true);
                decode.setAccessible(true); time.setAccessible(true);
                messageTable = accessor; encodeMessage = encode; decodeMessage = decode; messageTime = time;
                if (threadKey != null) threadKey.setAccessible(true);
                messageThreadKey = threadKey;
            }
            ModuleLog.line("(IE|KeepUnsent) restart copies " + (decodeMessage == null ? "unavailable"
                    : messageThreadKey == null ? "available for in-session messages only" : "available"));
        } catch (Throwable t) {
            ModuleLog.line("(IE|KeepUnsent) message codec unavailable: " + t.getMessage());
        }
    }

    private static List<MethodData> rowWriters(DexKitBridge bridge, Class<?> table) {
        return bridge.findMethod(FindMethod.create().matcher(MethodMatcher.create()
                .declaredClass(table.getName()).returnType("android.content.ContentValues")));
    }

    /** Label the Unsent Messages folder with Instagram's own inbox title for this chat. Before the
     *  inbox has listed it (a chat opened from a notification), the open chat's own header names
     *  it. Chats open in ModalActivity, so only the tracker's host window is read, never the
     *  main activity behind it (that stored the viewer's own handle for every thread). */
    private static void rememberThreadName(String threadId, Activity host) {
        if (threadId == null) return;
        String name = HideChatsHook.knownThreadTitle(threadId);
        if (!name.isEmpty()) {
            ThreadNames.put(threadId, name);
            return;
        }
        if (host == null) return;
        // Posted so the header has laid out; it names this chat only while the chat is still open.
        new Handler(Looper.getMainLooper()).postDelayed(() -> {
            try {
                if (!threadId.equals(CurrentThreadTracker.currentThreadId())) return;
                View root = host.getWindow() != null ? host.getWindow().getDecorView() : null;
                String header = root instanceof ViewGroup group ? findThreadName(host, group) : null;
                if (header != null) ThreadNames.put(threadId, header);
            } catch (Throwable ignored) {}
        }, 600);
    }

    /** The DM thread header's title (com.instagram.android:id/header_title, which can hold spaces),
     *  else a handle-like token inside that header. Never a whole-window scan: that picked up
     *  message words and other chats' names. */
    @SuppressLint("DiscouragedApi")
    private static String findThreadName(Activity a, ViewGroup root) {
        int headerId = a.getResources().getIdentifier("direct_thread_header", "id", a.getPackageName());
        if (!(ThreadHeaderMenu.findShown(root, headerId) instanceof ViewGroup header)) return null;
        try {
            int titleId = a.getResources().getIdentifier("header_title", "id", a.getPackageName());
            if (titleId != 0 && header.findViewById(titleId) instanceof android.widget.TextView tv) {
                CharSequence cs = tv.getText();
                if (cs != null && !cs.toString().trim().isEmpty()) return cs.toString().trim();
            }
        } catch (Throwable ignored) {}
        return findHandle(header);
    }

    /** Bounded depth-first scan for a handle-like TextView token (letters/digits/._ with at least
     *  one letter, so a count or a time is never taken for a name). */
    private static String findHandle(ViewGroup vg) {
        java.util.ArrayDeque<View> stack = new java.util.ArrayDeque<>();
        stack.push(vg);
        int visited = 0;
        while (!stack.isEmpty() && visited < 400) {
            View v = stack.pop();
            visited++;
            if (v instanceof android.widget.TextView tv) {
                CharSequence cs = tv.getText();
                if (cs != null) {
                    String s = cs.toString().trim();
                    if (s.matches("[a-zA-Z0-9._]{2,30}") && s.matches(".*[a-zA-Z].*")) return s;
                }
            } else if (v instanceof ViewGroup g) {
                for (int i = 0; i < g.getChildCount(); i++) stack.push(g.getChildAt(i));
            }
        }
        return null;
    }

    // Capture from the same native cache used by deletion, before suppressing the operation.
    private void hookRemovePrimitive(DexKitBridge bridge, ClassLoader classLoader) {
        try {
            int hooked = 0;
            // Never reuse KeepUnsent_remove: its broad four-argument selector also matched
            // a visual-message playback update, which is not a deletion event.
            for (Method removal : cachedMethods("KeepUnsent_removal", classLoader, () -> methodsOf(bridge.findMethod(
                    FindMethod.create().matcher(MethodMatcher.create().usingStrings(REMOVE_ANCHOR).returnType("void")
                            .paramTypes(DTK, "java.lang.Integer", "java.lang.String", "java.lang.String", "boolean"))),
                    classLoader))) {
                List<Method> parts = cachedMethods("KeepUnsent_native|" + removal.getDeclaringClass().getName()
                        + "." + removal.getName(), classLoader, () -> nativeParts(bridge, classLoader, removal));
                Method entryGetter = parts.size() > 0 ? parts.get(0) : null;
                Method serverLookup = parts.size() > 1 ? parts.get(1) : null;
                Method localRemoval = parts.size() > 2 ? parts.get(2) : null;
                Class<?> messageClass = serverLookup != null ? serverLookup.getReturnType() : null;
                List<Method> lookups = new ArrayList<>();
                if (entryGetter != null && messageClass != null) {
                    entryGetter.setAccessible(true);
                    for (Method method : entryGetter.getReturnType().getDeclaredMethods()) {
                        if (Modifier.isStatic(method.getModifiers()) || method.getReturnType() != messageClass
                                || method.getParameterCount() != 1 || method.getParameterTypes()[0] != String.class) continue;
                        method.setAccessible(true);
                        lookups.add(method);
                    }
                }
                // Both inspected versions expose one server-ID and one client-context query.
                if (lookups.size() != 2) lookups.clear();
                Method cacheGetter = entryGetter;
                Method serverQuery = serverLookup;
                if (localRemoval != null && cacheGetter != null && serverQuery != null && lookups.size() == 2) {
                    localRemoval.setAccessible(true);
                    XposedBridge.hookMethod(cacheGetter, new XC_MethodHook() {
                        @Override protected void afterHookedMethod(MethodHookParam param) { ps.reso.instaeclipse.utils.core.JitGuard.tick(param.method);
                            // Runs on every thread-cache lookup: no reflection while the feature is off.
                            if (!FeatureFlags.keepUnsentMessages || param.hasThrowable()
                                    || Boolean.TRUE.equals(clearingLocally.get())) return;
                            rememberNativeThread(param.thisObject, param.args[0], cacheGetter,
                                    localRemoval, serverQuery, lookups);
                        }
                    });
                    localClearingAvailable = true;
                }
                XposedBridge.hookMethod(removal, new XC_MethodHook() {
                    @Override protected void beforeHookedMethod(MethodHookParam param) { ps.reso.instaeclipse.utils.core.JitGuard.tick(param.method);
                        if (Boolean.TRUE.equals(clearingLocally.get())) return;
                        // Bulk unsend removes the user's own selection on purpose: let Instagram
                        // remove it and forget anything kept for it (also while the feature is off,
                        // so a saved copy cannot bring it back later). Otherwise it would stay red
                        // in the chat and fill the Unsent log.
                        List<String> bulk = bulkUnsendIds(param.args, BulkUnsend::isOwnUnsend);
                        if (!bulk.isEmpty()) {
                            try { releaseBulkUnsend(param.thisObject, param.args[0], cacheGetter, bulk); }
                            catch (Throwable t) {
                                ModuleLog.line("(IE|KeepUnsent) bulk release failed: " + t.getClass().getSimpleName());
                            }
                            ModuleLog.line("(IE|KeepUnsent) bulk unsend passthrough");
                            return;
                        }
                        if (!FeatureFlags.keepUnsentMessages) return;
                        // Fail open: Instagram removes the message unless it was captured and is
                        // kept in the chat. A capture that cannot name its account and thread must
                        // not leave an unmarked message in the chat that Clear cannot reach.
                        boolean block = false;
                        try {
                            String thread = UnsentIdentity.thread(param.args[0]);
                            List<String> ids = new ArrayList<>();
                            for (Object arg : param.args) if (arg instanceof String id && !id.isEmpty()) ids.add(id);
                            Object entry = null;
                            if (cacheGetter != null) {
                                try { entry = cacheGetter.invoke(param.thisObject, param.args[0]); }
                                catch (ReflectiveOperationException ignored) {}
                            }
                            String account = UnsentIdentity.account(param.thisObject);
                            String entryAccount = UnsentIdentity.account(entry);
                            if (account == null) account = entryAccount;
                            if (account != null && entryAccount != null && !account.equals(entryAccount)) {
                                ModuleLog.line("(IE|KeepUnsent) capture rejected: conflicting owner session");
                                return;
                            }
                            Object message = cachedMessage(entry, lookups, ids, thread);
                            if (message != null) ids.addAll(UnsentIdentity.messageIds(message));
                            if (UnsentLog.findCleared(account, thread, ids) != null) return;
                            boolean keepInChat = FeatureFlags.keepUnsentInChat;
                            Key key = protectedIds.recordAliases(account, thread, ids);
                            if (key != null) {
                                block = keepInChat;
                                // One encoding in Instagram's own format serves the restart copy and
                                // the log's description of a message without text (a reel, a voice
                                // message…); a text message in log-only mode needs neither.
                                byte[] copy = message != null && (keepInChat || !hasMessageText(message))
                                        ? encodeCopy(entry, message, param.args[0]) : null;
                                // Log-only mode leaves nothing to re-inject or restore.
                                if (keepInChat && message != null) {
                                    rememberKept(entry, key, message);
                                    saveCopy(key, message, copy);
                                }
                                // An empty copy: the encoding failed, so it is only "without text".
                                markUnsent(message, key, UnsentIdentity.messageIds(message), null,
                                        copy != null ? copy : new byte[0]);
                                UnsentIndicatorHook.refreshVisibleRows();
                            }
                            ModuleLog.line("(IE|KeepUnsent) removal captured=" + (key != null)
                                    + " kept=" + block + " cachedBody=" + (message != null));
                        } catch (Throwable t) {
                            ModuleLog.line("(IE|KeepUnsent) capture failed: " + t.getClass().getSimpleName());
                        } finally {
                            if (block) param.setResult(null);
                        }
                    }
                });
                hooked++;
                ModuleLog.line("(IE|KeepUnsent) remove=" + removal.getName() + " cached-item queries=" + lookups.size()
                        + " local clear=" + localClearingAvailable);
            }
            if (hooked > 0) FeatureStatusTracker.setHooked("KeepUnsentMessages");
            else ModuleLog.line("(IE|KeepUnsent) remove primitive not found");
        } catch (Throwable t) {
            ModuleLog.line("(IE|KeepUnsent) ❌ remove: " + t);
        }
    }

    /** [thread-cache getter, server-ID query, local-only removal]: each is found through the one
     *  before it, so the list holds as many as resolved, in that order. */
    private static List<Method> nativeParts(DexKitBridge bridge, ClassLoader loader, Method removal) throws Throwable {
        List<Method> parts = new ArrayList<>();
        MethodData data = bridge.getMethodData(removal);
        Method entryGetter = null;
        for (MethodData call : data.getInvokes()) {
            if (!call.getUsingStrings().contains("ThreadEntry not found")) continue;
            Method candidate = call.getMethodInstance(loader);
            if (candidate.getDeclaringClass() != removal.getDeclaringClass()
                    || candidate.getParameterCount() != 1 || !candidate.getParameterTypes()[0].getName().equals(DTK)) continue;
            if (entryGetter != null) return parts; // ambiguous thread cache getter
            entryGetter = candidate;
        }
        if (entryGetter == null) return parts;
        parts.add(entryGetter);
        Method serverLookup = null;
        for (MethodData call : data.getInvokes()) {
            if (!call.getClassName().equals(entryGetter.getReturnType().getName())
                    || !call.getParamTypeNames().equals(List.of("java.lang.String"))
                    || call.getReturnTypeName().equals("void")) continue;
            Method candidate = call.getMethodInstance(loader);
            if (serverLookup != null && serverLookup.getReturnType() != candidate.getReturnType()) {
                return parts; // ambiguous cached message type
            }
            serverLookup = candidate;
        }
        if (serverLookup == null) return parts;
        parts.add(serverLookup);
        Method localRemove = null;
        for (Method candidate : removal.getDeclaringClass().getDeclaredMethods()) {
            if (Modifier.isStatic(candidate.getModifiers()) || candidate.getReturnType() != void.class
                    || !List.of(candidate.getParameterTypes()).equals(List.of(
                    removal.getParameterTypes()[0], String.class, String.class, boolean.class))) continue;
            if (localRemove != null) return parts; // ambiguous local removal
            localRemove = candidate;
        }
        // The shape alone also matches unrelated work (a visual-message viewer method on 439); only
        // the wrapper that funnels into the hooked removal is Instagram's own local delete.
        if (localRemove != null && invokes(bridge.getMethodData(localRemove), removal)) parts.add(localRemove);
        return parts;
    }

    /** By name and parameters, so a call through the store's interface counts too. */
    private static boolean invokes(MethodData caller, Method target) {
        List<String> params = new ArrayList<>();
        for (Class<?> type : target.getParameterTypes()) params.add(type.getName());
        for (MethodData call : caller.getInvokes()) {
            if (call.getName().equals(target.getName()) && call.getParamTypeNames().equals(params)) return true;
        }
        return false;
    }

    private static Object cachedMessage(Object entry, List<Method> lookups, List<String> ids, String thread) {
        if (entry == null) return null;
        Object result = null;
        for (Method lookup : lookups) for (String id : ids) {
            try {
                Object candidate = lookup.invoke(entry, id);
                if (candidate == null || Collections.disjoint(ids, UnsentIdentity.messageIds(candidate))) continue;
                String candidateThread = UnsentIdentity.thread(candidate);
                if (candidateThread != null && !candidateThread.equals(thread)) continue;
                if (result != null && result != candidate) return null;
                result = candidate;
            } catch (ReflectiveOperationException ignored) {}
        }
        return result;
    }

    /** The removal's own String ids (server id and client context) when any of them is a message
     *  Bulk unsend just unsent; empty otherwise. Only these arguments are compared: Bulk unsend's
     *  remembered ids also hold timestamp-like Strings that could match unrelated cached fields. */
    static List<String> bulkUnsendIds(Object[] args, Predicate<String> ownUnsend) {
        if (args == null) return Collections.emptyList();
        List<String> ids = new ArrayList<>();
        boolean own = false;
        for (Object arg : args) {
            if (!(arg instanceof String id) || id.isEmpty()) continue;
            ids.add(id);
            if (ownUnsend.test(id)) own = true;
        }
        return own ? ids : Collections.emptyList();
    }

    /** Drop a bulk-unsent message from what reconcile re-injects, from the removal index and from
     *  the restart copies; Instagram then removes it like any other own unsend. */
    private static void releaseBulkUnsend(Object store, Object threadKey, Method cacheGetter, List<String> ids) {
        String thread = UnsentIdentity.thread(threadKey);
        String account = UnsentIdentity.account(store);
        if (account == null && cacheGetter != null) {
            try { account = UnsentIdentity.account(cacheGetter.invoke(store, threadKey)); }
            catch (ReflectiveOperationException ignored) {}
        }
        if (account == null || thread == null) return;
        Key known = protectedIds.find(account, thread, ids);
        if (known != null) protectedIds.remove(known);
        UnsentMessageStore.removeMessage(account, thread, ids);
        String owner = account;
        synchronized (keptByThread) {
            Iterator<Map<Key, Object>> stores = keptByThread.values().iterator();
            while (stores.hasNext()) {
                Map<Key, Object> owned = stores.next();
                synchronized (owned) {
                    owned.keySet().removeIf(key -> key.equals(known) || owner.equals(key.account)
                            && thread.equals(key.thread) && ids.contains(key.message));
                    if (owned.isEmpty()) stores.remove();
                }
            }
        }
    }

    private static void rememberKept(Object owner, Key key, Object message) {
        if (UnsentLog.findCleared(key.account, key.thread, List.of(key.message)) != null) return;
        synchronized (keptByThread) {
            Map<Key, Object> owned = keptByThread.get(owner);
            if (owned == null) {
                owned = Collections.synchronizedMap(new LinkedHashMap<>());
                keptByThread.put(owner, owned);
            }
            owned.put(key, message);
        }
    }

    /** Instagram keeps only each thread's ten newest messages on disk and reloads older history from
     *  the server, which no longer returns unsent ones; keep a copy in Instagram's own format. */
    private static void saveCopy(Key key, Object message, byte[] copy) {
        if (copy == null) return; // encodeCopy logged why
        try {
            UnsentMessageStore.save(key.account, key.thread, protectedIds.aliases(key),
                    (long) messageTime.invoke(message), copy);
        } catch (Throwable t) {
            ModuleLog.line("(IE|KeepUnsent) copy failed: " + t.getClass().getSimpleName());
        }
    }

    /** The message in Instagram's own row format (UTF-8 JSON), or null when the codec or the
     *  owner's session is unavailable. */
    private static byte[] encodeCopy(Object owner, Object message, Object threadKey) {
        Object table = messageTable(owner);
        if (table == null) {
            ModuleLog.line("(IE|KeepUnsent) copy skipped: message table unavailable");
            return null;
        }
        try {
            return encode(table, message, threadKey);
        } catch (Throwable t) {
            ModuleLog.line("(IE|KeepUnsent) copy failed: " + t.getClass().getSimpleName());
            return null;
        }
    }

    /** Messages loaded from the server carry no thread key, and the decoder drops any copy without
     *  one. Lend the removal's key for the encoding only, as Instagram sets it before saving. */
    private static byte[] encode(Object table, Object message, Object threadKey) throws ReflectiveOperationException {
        synchronized (message) {
            boolean lend = messageThreadKey != null && threadKey != null && messageThreadKey.get(message) == null;
            if (lend) messageThreadKey.set(message, threadKey);
            try {
                return (byte[]) encodeMessage.invoke(table, new ByteArrayOutputStream(), message);
            } finally {
                if (lend && messageThreadKey.get(message) == threadKey) messageThreadKey.set(message, null);
            }
        }
    }

    /** The thread entry's own key, exposed by its metadata object through a no-argument getter.
     *  Loaded messages often omit their key, so they cannot name the thread themselves. */
    private static String entryThread(Object entry) {
        synchronized (entryThreads) {
            String known = entryThreads.get(entry);
            if (known != null) return known;
        }
        for (Field field : entry.getClass().getDeclaredFields()) {
            if (Modifier.isStatic(field.getModifiers()) || field.getType().isPrimitive()) continue;
            try {
                field.setAccessible(true);
                Object holder = field.get(entry);
                if (holder == null || holder instanceof Collection) continue;
                for (Method getter : holder.getClass().getMethods()) {
                    if (getter.getParameterCount() != 0 || !getter.getReturnType().getName().equals(DTK)) continue;
                    String thread = UnsentIdentity.thread(getter.invoke(holder));
                    if (thread == null) continue;
                    synchronized (entryThreads) { entryThreads.put(entry, thread); }
                    return thread;
                }
            } catch (Throwable ignored) {}
        }
        return null;
    }

    private static Object messageTable(Object owner) {
        if (messageTable == null) return null;
        try {
            Object session = UnsentIdentity.directValue(owner, messageTable.getParameterTypes()[0]);
            return session == null ? null : messageTable.invoke(null, session);
        } catch (ReflectiveOperationException e) {
            return null;
        }
    }

    /** Put saved copies back once the loaded history reaches them, announced like server additions
     *  so the open chat shows them. Never into a thread or position Instagram has not loaded.
     *  A later pass drops a restored copy again (the server page lacks it); keep that same object
     *  and withdraw its removal instead of flickering it out and back in. */
    @SuppressWarnings("unchecked")
    private static void restoreCopies(Object[] args, List<Object> liveList) throws ReflectiveOperationException {
        if (decodeMessage == null || !(args[3] instanceof List)) return;
        String account = UnsentIdentity.account(args[0]), thread = entryThread(args[0]);
        List<UnsentMessageStore.Saved> copies = UnsentMessageStore.forThread(account, thread);
        if (copies.isEmpty()) return;
        List<Object> live = liveList != null ? liveList : backingList(args);
        Object table = messageTable(args[0]);
        if (live == null || live.size() < 2 || table == null) return;
        List<Object> removed = args[4] instanceof List ? (List<Object>) args[4] : Collections.emptyList();
        Set<String> loadedIds = new HashSet<>();
        long[] times = new long[live.size()];
        for (int i = 0; i < times.length; i++) {
            loadedIds.addAll(UnsentIdentity.messageIds(live.get(i)));
            times[i] = (long) messageTime.invoke(live.get(i));
        }
        int restored = 0;
        for (UnsentMessageStore.Saved copy : copies) {
            if (!Collections.disjoint(loadedIds, copy.ids)) continue;
            if (UnsentLog.findCleared(account, thread, copy.ids) != null) {
                UnsentMessageStore.remove(copy);
                continue;
            }
            int index = UnsentRestorePolicy.restoreIndex(times, copy.time);
            if (index < 0) continue;
            Object message = null;
            for (Iterator<Object> it = removed.iterator(); it.hasNext(); ) {
                Object dropped = it.next();
                if (Collections.disjoint(copy.ids, UnsentIdentity.messageIds(dropped))) continue;
                it.remove();
                message = dropped;
                break;
            }
            if (message == null) {
                message = decodeMessage.invoke(table, (Object) copy.message);
                if (message == null) continue;
                ((List<Object>) args[3]).add(message);
                restored++;
            }
            live.add(index, message);
            loadedIds.addAll(copy.ids);
            times = inserted(times, index, copy.time);
        }
        if (restored > 0) ModuleLog.line("(IE|KeepUnsent) restored " + restored + " saved unsent message(s)");
    }

    private static long[] inserted(long[] values, int index, long value) {
        long[] grown = new long[values.length + 1];
        System.arraycopy(values, 0, grown, 0, index);
        grown[index] = value;
        System.arraycopy(values, index, grown, index + 1, values.length - index);
        return grown;
    }

    /** Release only this viewer's retained copies, then use Instagram's local cache/SQLite removal.
     *  Without that removal only log-only entries may go: a message kept in the chat whose entry
     *  is cleared would stay there for good as an unmarked ordinary message. */
    public static boolean clearSaved(Context context, String thread) {
        if (!localClearingAvailable && UnsentLog.hasRetained(context, thread)) return false;
        String account = thread == null ? UnsentLog.clear(context) : UnsentLog.clearThread(context, thread);
        if (account == null) return false;
        protectedIds.clear(account, thread);
        UnsentMessageStore.clear(account, thread);
        synchronized (keptByThread) {
            Iterator<Map<Key, Object>> stores = keptByThread.values().iterator();
            while (stores.hasNext()) {
                Map<Key, Object> owned = stores.next();
                synchronized (owned) {
                    owned.keySet().removeIf(key -> account.equals(key.account)
                            && (thread == null || thread.equals(key.thread)));
                    if (owned.isEmpty()) stores.remove();
                }
            }
        }
        synchronized (nativeThreads) {
            for (NativeThread target : nativeThreads.values()) {
                if (account.equals(target.account) && (thread == null || thread.equals(target.thread))) target.schedule();
            }
        }
        UnsentIndicatorHook.refreshVisibleRows();
        return true;
    }

    private static void rememberNativeThread(Object store, Object threadKey, Method getter,
                                             Method remove, Method serverQuery, List<Method> queries) {
        String account = UnsentIdentity.account(store), thread = UnsentIdentity.thread(threadKey);
        if (account == null || thread == null) return;
        Key scope = new Key(account, thread, "");
        synchronized (nativeThreads) {
            NativeThread target = nativeThreads.get(scope);
            if (target == null || target.store.get() != store) {
                target = new NativeThread(account, thread, store, threadKey, getter, remove, serverQuery, queries);
                nativeThreads.put(scope, target);
                if (nativeThreads.size() > 1000) nativeThreads.remove(nativeThreads.keySet().iterator().next());
            }
            target.schedule();
        }
    }

    private static final class NativeThread {
        final String account, thread;
        final WeakReference<Object> store;
        final Object threadKey;
        final Method getter, remove, serverQuery;
        final List<Method> queries;
        boolean scheduled;

        NativeThread(String account, String thread, Object store, Object threadKey, Method getter,
                     Method remove, Method serverQuery, List<Method> queries) {
            this.account = account; this.thread = thread;
            this.store = new WeakReference<>(store); this.threadKey = threadKey;
            this.getter = getter; this.remove = remove; this.serverQuery = serverQuery; this.queries = queries;
        }

        synchronized void schedule() {
            if (scheduled || UnsentLog.getClearedIds(account, thread).isEmpty()) return;
            scheduled = true;
            // Getter/removal can run under native store locks. Wait until those calls return before
            // removing anything, and coalesce repeated cache lookups in the same UI turn.
            new Handler(Looper.getMainLooper()).post(() -> {
                try { purge(); }
                finally { synchronized (this) { scheduled = false; } }
            });
        }

        void purge() {
            Object owner = store.get();
            if (owner == null || !account.equals(UnsentIdentity.account(owner))) return;
            clearingLocally.set(true);
            try {
                Object entry = getter.invoke(owner, threadKey);
                if (entry == null || !account.equals(UnsentIdentity.account(entry))) return;
                for (List<String> ids : UnsentLog.getClearedIds(account, thread)) {
                    Object message = cachedMessage(entry, queries, ids, thread);
                    if (message == null) continue;
                    String server = null, client = null;
                    for (Method query : queries) for (String id : ids) {
                        if (query.invoke(entry, id) != message) continue;
                        if (query.equals(serverQuery)) server = id;
                        else client = id;
                    }
                    if (server == null && client == null) continue;
                    // The native wrapper removes from the thread cache and local SQLite, then
                    // publishes the normal UI delta. It does not send a remote unsend request.
                    remove.invoke(owner, threadKey, server, client, true);
                }
            } catch (Throwable t) {
                ModuleLog.line("(IE|KeepUnsent) local trash removal failed: " + t.getClass().getSimpleName());
            } finally { clearingLocally.remove(); }
        }
    }

    // ── 2. REFRESH/SYNC reconcile: keep protected messages in the live list ──────
    private void hookReconcile(DexKitBridge bridge, ClassLoader classLoader) {
        XC_MethodHook hook = new XC_MethodHook() {
            // BEFORE: while the message is still present in the live list (or arriving), cache the
            // actual message object so we can re-inject it on later passes that no longer carry it.
            @Override
            @SuppressWarnings("unchecked")
            protected void beforeHookedMethod(MethodHookParam param) { ps.reso.instaeclipse.utils.core.JitGuard.tick(param.method);
                if (!FeatureFlags.keepUnsentMessages || !FeatureFlags.keepUnsentInChat || protectedIds.isEmpty()) return;
                Object[] args = param.args;
                if (args == null || args.length == 0 || args[0] == null) return;
                try {
                    for (int i = 1; i < args.length; i++) {
                        if (!(args[i] instanceof List)) continue;
                        for (Object msg : (List<Object>) args[i]) {
                            Key hit = matchProtected(msg, args[0]);
                            if (hit == null) continue;
                            rememberKept(args[0], hit, msg);
                            // The entry's session can describe a kept message whose removal was
                            // captured before its content was cached.
                            markUnsent(msg, hit, UnsentIdentity.messageIds(msg), args[0], null);
                        }
                    }
                } catch (Throwable ignored) {}
            }

            // AFTER: ensure every cached protected message is present in the live list, and scrub it
            // from any removed/delta out-list so the removal delta won't fire. Log-only mode keeps
            // nothing in the chat, so it neither re-injects nor restores.
            @Override
            @SuppressWarnings("unchecked")
            protected void afterHookedMethod(MethodHookParam param) { ps.reso.instaeclipse.utils.core.JitGuard.tick(param.method);
                if (!FeatureFlags.keepUnsentMessages || !FeatureFlags.keepUnsentInChat) return;
                Object[] args = param.args;
                if (args == null || args.length < 4 || args[0] == null) return;
                // Only act for THIS thread's own kept messages — never touch other chats.
                Map<Key, Object> owned = keptByThread.get(args[0]);
                boolean keeping = owned != null && !owned.isEmpty();
                if (!keeping && UnsentMessageStore.isEmpty()) return;
                try {
                    List<Object> liveList = findLiveList(args);
                    if (keeping && liveList != null) keepOwned(args, liveList, owned);
                    // After the kept messages, so the delta scrub cannot drop a restored addition.
                    restoreCopies(args, liveList);
                } catch (Throwable t) {
                    ModuleLog.line("(IE|KeepUnsent) ❌ reconcile body: " + t);
                }
            }

            @SuppressWarnings("unchecked")
            private void keepOwned(Object[] args, List<Object> liveList, Map<Key, Object> owned) {
                // Scrub this thread's protected messages out of the non-live (delta) lists.
                for (int i = 1; i < args.length; i++) {
                    if (!(args[i] instanceof List) || args[i] == liveList) continue;
                    Iterator<Object> it = ((List<Object>) args[i]).iterator();
                    while (it.hasNext()) {
                        Key hit = matchProtected(it.next(), args[0]);
                        if (hit != null && owned.containsKey(hit)) it.remove();
                    }
                }

                // Re-inject this thread's cached protected messages if missing from its live list.
                int added = 0;
                synchronized (owned) {
                    owned.keySet().removeIf(key -> UnsentLog.findCleared(
                            key.account, key.thread, List.of(key.message)) != null);
                    for (Object msg : owned.values()) {
                        boolean present = false;
                        for (Object cur : liveList) if (cur == msg) { present = true; break; }
                        if (!present) { liveList.add(msg); added++; }
                    }
                }
                if (added > 0) ModuleLog.line("(IE|KeepUnsent) ✅ re-injected " + added + " kept msg(s) into this thread");
            }
        };

        try {
            List<Method> reconcile = cachedMethods("KeepUnsent_reconcile", classLoader, () -> {
                LinkedHashSet<Class<?>> classes = new LinkedHashSet<>();
                for (MethodData md : bridge.findMethod(FindMethod.create()
                        .matcher(MethodMatcher.create().usingStrings(RECONCILE_ANCHOR)))) {
                    try { classes.add(md.getMethodInstance(classLoader).getDeclaringClass()); } catch (Throwable ignored) {}
                }
                for (Class<?> c : classes) {
                    for (Method m : c.getDeclaredMethods()) {
                        if (m.getReturnType() != void.class) continue;
                        Class<?>[] p = m.getParameterTypes();
                        // A0C: (01gH, List, List, List, List, List) — first param is the declaring class,
                        // the rest are Lists.
                        if (p.length != 6) continue;
                        if (p[0] != c) continue;
                        boolean rest = true;
                        for (int i = 1; i < 6; i++) if (!List.class.isAssignableFrom(p[i])) { rest = false; break; }
                        if (rest) return List.of(m);
                    }
                }
                return List.of();
            });
            if (reconcile.isEmpty()) {
                ModuleLog.line("(IE|KeepUnsent) ❌ reconcile method not found");
                return;
            }
            Method m = reconcile.get(0);
            m.setAccessible(true);
            XposedBridge.hookMethod(m, hook);
            ModuleLog.line("(IE|KeepUnsent) ✅ reconcile hook → " + m.getDeclaringClass().getName() + "." + m.getName());
        } catch (Throwable t) {
            ModuleLog.line("(IE|KeepUnsent) ❌ reconcile resolve: " + t);
        }
    }

    /** The live message list = the List arg that IS a field on the thread entry (args[0]) —
     *  reconcile rewrites that field in place, so adding to it persists into the store. */
    @SuppressWarnings("unchecked")
    private static List<Object> findLiveList(Object[] args) {
        if (args[0] == null) return null;
        Set<Object> fieldLists = Collections.newSetFromMap(new IdentityHashMap<>());
        for (Field f : args[0].getClass().getDeclaredFields()) {
            if (!List.class.isAssignableFrom(f.getType())) continue;
            f.setAccessible(true);
            try { Object v = f.get(args[0]); if (v != null) fieldLists.add(v); } catch (Throwable ignored) {}
        }
        for (int i = 1; i < args.length; i++) {
            if (args[i] instanceof List && fieldLists.contains(args[i])) return (List<Object>) args[i];
        }
        return null;
    }

    /** Opening a chat reconciles only a range, passed as a view of the thread entry's list; the
     *  entry's list holding the range's messages is the whole loaded history. */
    @SuppressWarnings("unchecked")
    private static List<Object> backingList(Object[] args) {
        if (!(args[1] instanceof List<?> range) || range.isEmpty()) return null;
        Object first = range.get(0);
        for (Field f : args[0].getClass().getDeclaredFields()) {
            if (!List.class.isAssignableFrom(f.getType())) continue;
            f.setAccessible(true);
            try {
                if (!(f.get(args[0]) instanceof List<?> list) || list == range) continue;
                for (Object message : list) if (message == first) return (List<Object>) list;
            } catch (IllegalAccessException ignored) {}
        }
        return null;
    }
    static boolean hasMessageText(Object message) {
        String text = UnsentIdentity.messageText(message, messageText);
        return text != null && !text.trim().isEmpty();
    }

    static void markUnsent(Object msg, Key key, List<String> knownIds) {
        markUnsent(msg, key, knownIds, null, null);
    }

    /** @param owner the thread entry, whose session encodes a message without text to describe it
     *               (null: never encode); @param copy the message already encoded (empty when that
     *               failed), or null. */
    private static void markUnsent(Object msg, Key key, List<String> knownIds, Object owner, byte[] copy) {
        if (key == null) return;
        if (UnsentLog.findCleared(key.account, key.thread, knownIds) != null) return;
        try {
            List<String> ids = new ArrayList<>(protectedIds.aliases(key));
            if (ids.isEmpty()) ids.add(key.message);
            ids.addAll(knownIds);
            String text = UnsentIdentity.messageText(msg, messageText);
            UnsentDetail detail = msg == null || (text != null && !text.trim().isEmpty())
                    ? null : describe(msg, key, ids, owner, copy);
            UnsentLog.add(key.account, key.thread, ids, ThreadNames.get(key.thread), text,
                    !FeatureFlags.keepUnsentInChat, detail);
        } catch (Throwable t) {
            ModuleLog.line("(IE|KeepUnsent) log failed: " + t.getMessage());
        }
    }

    /** What a message without text was (a reel, a voice message…), read from Instagram's own
     *  serialized form. At most one encoding per message, and only while its log entry lacks a
     *  description: reconcile passes call this for every kept message. */
    private static UnsentDetail describe(Object msg, Key key, List<String> ids, Object owner, byte[] copy) {
        if (described.size() > 1000) described.clear();
        if (copy == null) {
            if (owner == null || !described.add(key) || !UnsentLog.needsDetail(key.account, key.thread, ids)) {
                return null;
            }
            copy = encodeCopy(owner, msg, null);
        } else {
            described.add(key);
        }
        UnsentDetail detail = copy == null || copy.length == 0 ? null
                : UnsentDetail.fromMessageJson(new String(copy, StandardCharsets.UTF_8));
        ModuleLog.line("(IE|KeepUnsent) described as " + (detail != null ? detail.kind.id : "none"));
        return detail != null ? detail : UnsentDetail.NO_TEXT;
    }

    private static Key matchProtected(Object message, Object owner) {
        String account = UnsentIdentity.account(owner), thread = UnsentIdentity.thread(message);
        List<String> ids = UnsentIdentity.messageIds(message);
        if (UnsentLog.findCleared(account, thread, ids) != null) return null;
        return protectedIds.find(account, thread, ids);
    }
}
