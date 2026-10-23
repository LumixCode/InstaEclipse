package ps.reso.instaeclipse.mods.ghost;

import org.luckypray.dexkit.DexKitBridge;
import org.luckypray.dexkit.query.FindMethod;
import org.luckypray.dexkit.query.matchers.MethodMatcher;
import org.luckypray.dexkit.result.MethodData;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;

import ps.reso.instaeclipse.xposedcompat.XC_MethodHook;
import ps.reso.instaeclipse.xposedcompat.XposedBridge;
import ps.reso.instaeclipse.utils.core.DexKitCache;
import ps.reso.instaeclipse.utils.core.DexKitQueryCache;
import ps.reso.instaeclipse.utils.core.SafeReflect;
import ps.reso.instaeclipse.utils.feature.FeatureFlags;
import ps.reso.instaeclipse.utils.feature.FeatureStatusTracker;
import ps.reso.instaeclipse.utils.ghost.CurrentThreadTracker;
import ps.reso.instaeclipse.utils.ghost.EditHistoryStore;
import ps.reso.instaeclipse.utils.ghost.UnsentIdentity;
import ps.reso.instaeclipse.utils.log.ModuleLog;

/**
 * Message edit history (#243): tapping "Edited" on an edited DM opens its earlier versions.
 *
 * - The DM message class is the return type of the lookup using ": null_direct_message" (X.00E9 on
 *   451). Its body is the one String field of that class read by the method using
 *   "DirectMessage.updateIsMessageEmoji" (X.03Ej.A1D).
 * - Tap: both "Edited" renderers (the Compose HeaderText annotations "edit_history_show/hide" and
 *   the legacy ClickableSpan) call the thread's (MessageIdentifier, boolean) listener that logs
 *   "show_edit_history" / "hide_edit_history" (X.08Rt.FPT). It runs the thread's toggle, the
 *   (String, boolean) method that reads DirectMessageEditHistory's body (X.02Zy.A0L): it finds the
 *   row by id and expands the old versions inline, but only when the server sent a history list
 *   (without one the tap does nothing), and it loops over the message's edit count rather than
 *   the list.
 * - During a "show" tap the toggle's ()List call on the message (the history getter, X.00E9.A12)
 *   gives us the message and the server's list, and returns an empty list so the toggle stops
 *   before its inline expansion. After the tap EditHistoryDialog lists the versions; with none
 *   saved, a toast says so. "Hide" taps and every other read of the getter are left alone.
 * - Recording: the body is compared around the server-update merge (the synchronized
 *   (UserSession, message, x) method, X.00E9.A19) and around the content setter (the synchronized
 *   (type, Object) method using "unsupported message type: ", X.00E9.A1L), which Instagram's sync
 *   engine uses to apply an edit in place. Only the body counts, so id or status changes are never
 *   taken for edits. The toggle's int field read on the message (the edit count, X.03Ej.A01) says
 *   whether the replaced text was the original, its ()long call (X.00E9.A0K) when it was sent.
 * Versions persist in EditHistoryStore (Instagram's filesDir), so they survive restarts. Earlier
 * builds fed recorded versions back through the getter instead; that let the toggle index past
 * them (edit count above the list size) and made the sync engine append new server history to the
 * returned copy instead of the message.
 */
public class EditHistoryHook {

    private static final String TAG = "(IE|EditHistory) ";
    private static final String HISTORY = "com.instagram.direct.model.json.DirectMessageEditHistory";
    private static final String IDENTIFIER = "com.instagram.model.direct.messageid.MessageIdentifier";
    private static final String SESSION = "com.instagram.common.session.UserSession";
    /** DexKitCache keys; bump when the lookups change. */
    private static final String CACHE = "EditHistory_v2_";
    private static final String MISSING = "missing";

    private static Field body, editCount, historyBody, historyTime;
    private static Method sentTime;
    /** {body, edit count} before each running merge / content set on this thread, innermost first;
     *  every before pushes (NONE when not tracked) and every after pops, so nested calls pair up. */
    private static final ThreadLocal<ArrayDeque<Object[]>> BEFORE = ThreadLocal.withInitial(ArrayDeque::new);
    private static final Object[] NONE = new Object[0];
    private static final ThreadLocal<Tap> TAP = new ThreadLocal<>();

    /** One "show" tap on "Edited", filled in while Instagram's toggle runs. */
    private static final class Tap {
        final List<String> ids;
        final String account;
        Object message;
        List<?> server;

        Tap(List<String> ids, String account) { this.ids = ids; this.account = account; }
    }

    private interface Lookup<T> { T find() throws Throwable; }

    public void install(DexKitBridge bridge, ClassLoader cl) {
        try {
            Class<?> msgClass = null;
            for (MethodData md : DexKitQueryCache.findMethod(bridge, FindMethod.create().matcher(MethodMatcher.create()
                    .usingStrings(": null_direct_message")))) {
                msgClass = md.getMethodInstance(cl).getReturnType();
                break;
            }
            if (msgClass == null) { ModuleLog.error(TAG + "Could not turn on edit history: not supported by this Instagram version (message class not found)"); return; }
            final Class<?> message = msgClass;

            Class<?> history = cl.loadClass(HISTORY);
            for (Field f : SafeReflect.fields(history)) {
                if (Modifier.isStatic(f.getModifiers())) continue;
                if (f.getType() == String.class) historyBody = f;
                else if (f.getType() == long.class) historyTime = f;
            }
            if (historyBody == null) { ModuleLog.error(TAG + "Could not turn on edit history: not supported by this Instagram version (history text not found)"); return; }
            historyBody.setAccessible(true);
            if (historyTime != null) historyTime.setAccessible(true);

            body = cachedField("body", cl, () -> bodyField(bridge, cl, message));
            if (body == null) { ModuleLog.error(TAG + "Could not turn on edit history: not supported by this Instagram version (message text not found)"); return; }
            body.setAccessible(true);

            // The toggle is only read for the members it uses on the message; it is not hooked.
            String bodyDesc = "L" + HISTORY.replace('.', '/') + ";->" + historyBody.getName() + ":Ljava/lang/String;";
            Lookup<MethodData> toggle = () -> {
                MethodData found = null;
                for (MethodData md : DexKitQueryCache.findMethod(bridge, FindMethod.create().matcher(MethodMatcher.create()
                        .addUsingField(bodyDesc).paramTypes("java.lang.String", "boolean").returnType("void")))) {
                    if (found != null) return null; // ambiguous
                    found = md;
                }
                return found;
            };
            Method getter = cachedMethod("getter", cl, () -> invoked(toggle.find(), cl, message, List.class));
            sentTime = cachedMethod("time", cl, () -> invoked(toggle.find(), cl, message, long.class));
            editCount = cachedField("count", cl, () -> intRead(toggle.find(), cl, message));
            if (sentTime != null) sentTime.setAccessible(true);
            if (editCount != null) editCount.setAccessible(true);

            String recording = hookRecording(bridge, cl, message);
            boolean tap = getter != null && hookTap(bridge, cl, getter);
            CurrentThreadTracker.install(cl);
            ModuleLog.line(TAG + (tap ? "✅ ready"
                    : "❌ Could not turn on edit history: tapping \"Edited\" is not supported by this Instagram version"));
            ModuleLog.detail(TAG + (tap ? "tap + getter " + getter.getName() : "tap unavailable (getter " + getter + ")")
                    + ", recording " + recording + ", body " + body.getName()
                    + ", count " + (editCount == null ? "?" : editCount.getName())
                    + ", time " + (sentTime == null ? "?" : sentTime.getName()));
            if (tap) FeatureStatusTracker.setHooked("EditHistory");
        } catch (Throwable t) {
            ModuleLog.error(TAG + "Could not turn on edit history", t);
        }
    }

    // ── Recording ────────────────────────────────────────────────────────────

    private static String hookRecording(DexKitBridge bridge, ClassLoader cl, Class<?> message) {
        List<String> hooked = new ArrayList<>();
        try {
            Method merge = null;
            for (Method m : SafeReflect.methods(message)) {
                Class<?>[] p = m.getParameterTypes();
                if (p.length == 3 && p[1] == message && p[0].getName().equals(SESSION)
                        && Modifier.isSynchronized(m.getModifiers()) && !Modifier.isStatic(m.getModifiers())) { merge = m; break; }
            }
            if (merge != null) {
                // stored.merge(session, incoming, x): the server's copy replaces the stored text.
                XposedBridge.hookMethod(merge, new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) { ps.reso.instaeclipse.utils.core.JitGuard.tick(param.method);
                        BEFORE.get().push(FeatureFlags.keepEditHistory ? snapshot(param.thisObject) : NONE);
                    }

                    @Override
                    protected void afterHookedMethod(MethodHookParam param) { ps.reso.instaeclipse.utils.core.JitGuard.tick(param.method);
                        compare(param.thisObject, BEFORE.get().poll(), param.args[0], "merge");
                    }
                });
                hooked.add(merge.getName());
            }
        } catch (Throwable t) {
            ModuleLog.warn(TAG + "Edits that arrive from the server may not be saved", t);
        }
        try {
            Method setter = null;
            for (MethodData md : DexKitQueryCache.findMethod(bridge, FindMethod.create().matcher(MethodMatcher.create()
                    .declaredClass(message.getName()).usingStrings("unsupported message type: ")
                    .paramCount(2).returnType("void")))) {
                Method m = md.getMethodInstance(cl);
                if (Modifier.isStatic(m.getModifiers()) || m.getParameterTypes()[1] != Object.class) continue;
                if (setter != null) { setter = null; break; } // ambiguous
                setter = m;
            }
            if (setter != null) {
                // message.setContent(type, value): the sync engine's in-place edit (no session at hand).
                XposedBridge.hookMethod(setter, new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) { ps.reso.instaeclipse.utils.core.JitGuard.tick(param.method);
                        BEFORE.get().push(FeatureFlags.keepEditHistory && param.args[1] instanceof String
                                ? snapshot(param.thisObject) : NONE);
                    }

                    @Override
                    protected void afterHookedMethod(MethodHookParam param) { ps.reso.instaeclipse.utils.core.JitGuard.tick(param.method);
                        compare(param.thisObject, BEFORE.get().poll(), null, "in place");
                    }
                });
                hooked.add(setter.getName());
            }
        } catch (Throwable t) {
            ModuleLog.warn(TAG + "Edits applied in place may not be saved", t);
        }
        return hooked.isEmpty() ? "unavailable" : String.join("+", hooked);
    }

    private static Object[] snapshot(Object msg) {
        try {
            String text = (String) body.get(msg);
            if (text == null || text.isEmpty()) return NONE; // a new message being filled in
            return new Object[]{text, count(msg)};
        } catch (Throwable t) {
            return NONE;
        }
    }

    /** Records the replaced text when the body changed; {@code session} is the merge's UserSession (null in place). */
    private static void compare(Object msg, Object[] before, Object session, String how) {
        if (before == null || before == NONE) return;
        try {
            String old = (String) before[0], now = (String) body.get(msg);
            if (now == null || now.isEmpty() || now.equals(old)) return;
            List<String> ids = UnsentIdentity.messageIds(msg);
            if (ids.isEmpty()) return;
            boolean original = (Integer) before[1] == 0;
            String account = session == null ? null : UnsentIdentity.account(session);
            if (EditHistoryStore.shared().recordEdit(account, UnsentIdentity.thread(msg), ids, old, now,
                    original, sent(msg), System.currentTimeMillis())) {
                EditHistoryStore.persist();
                ModuleLog.detail(TAG + "recorded an earlier version (" + how + ", " + old.length() + " chars"
                        + (original ? ", original" : "") + ")");
            }
        } catch (Throwable t) {
            ModuleLog.warn(TAG + "Could not save an earlier version of an edited message", t);
        }
    }

    // ── Tap on "Edited" ──────────────────────────────────────────────────────

    private static boolean hookTap(DexKitBridge bridge, ClassLoader cl, Method getter) throws Throwable {
        Method tap = null;
        for (MethodData md : DexKitQueryCache.findMethod(bridge, FindMethod.create().matcher(MethodMatcher.create()
                .usingStrings("show_edit_history", "hide_edit_history").paramTypes(IDENTIFIER, "boolean").returnType("void")))) {
            Method m = md.getMethodInstance(cl);
            if (Modifier.isStatic(m.getModifiers())) continue;
            if (tap != null) { ModuleLog.detail(TAG + "tap listener ambiguous"); return false; }
            tap = m;
        }
        if (tap == null) { ModuleLog.detail(TAG + "tap listener not found"); return false; }

        XposedBridge.hookMethod(tap, new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) { ps.reso.instaeclipse.utils.core.JitGuard.tick(param.method);
                TAP.remove();
                // "Hide" (Instagram's own expansion is open) stays Instagram's.
                if (!FeatureFlags.keepEditHistory || !Boolean.TRUE.equals(param.args[1]) || param.args[0] == null) return;
                List<String> ids = UnsentIdentity.strings(param.args[0]);
                if (!ids.isEmpty()) TAP.set(new Tap(ids, UnsentIdentity.account(param.thisObject)));
            }

            @Override
            protected void afterHookedMethod(MethodHookParam param) { ps.reso.instaeclipse.utils.core.JitGuard.tick(param.method);
                Tap t = TAP.get();
                TAP.remove();
                if (t != null) show(t);
            }
        });

        XposedBridge.hookMethod(getter, new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam param) { ps.reso.instaeclipse.utils.core.JitGuard.tick(param.method);
                Tap t = TAP.get();
                if (t == null || t.message != null) return;
                // Only the tapped message (the toggle reads it right after finding its row).
                if (Collections.disjoint(UnsentIdentity.messageIds(param.thisObject), t.ids)) return;
                t.message = param.thisObject;
                t.server = param.getResult() instanceof List<?> l ? new ArrayList<>(l) : null;
                param.setResult(new ArrayList<>()); // the toggle returns before its inline expansion
            }
        });
        return true;
    }

    private static void show(Tap t) {
        try {
            String current = null;
            int count = -1;
            LinkedHashSet<String> ids = new LinkedHashSet<>(t.ids);
            if (t.message != null) {
                current = (String) body.get(t.message);
                count = count(t.message);
                ids.addAll(UnsentIdentity.messageIds(t.message));
            }
            List<EditHistoryStore.Version> server = serverVersions(t.server);
            String thread = CurrentThreadTracker.currentThreadId();
            EditHistoryStore store = EditHistoryStore.shared();
            if (store.mergeServer(t.account, thread, ids, server,
                    EditHistoryTimeline.complete(count, server.size()), System.currentTimeMillis())) {
                EditHistoryStore.persist();
            }
            List<EditHistoryTimeline.Row> rows = EditHistoryTimeline.rows(store.versions(t.account, thread, ids), current);
            int earlier = EditHistoryTimeline.earlier(rows);
            ModuleLog.detail(TAG + "tap: " + earlier + " earlier version(s) (server " + server.size()
                    + ", edit count " + count + (t.message == null ? ", message not found" : "") + ")");
            if (earlier == 0) EditHistoryDialog.none();
            else EditHistoryDialog.show(rows, EditHistoryTimeline.missing(count, earlier));
        } catch (Throwable e) {
            ModuleLog.error(TAG + "Could not show the edit history", e);
        }
    }

    /** Instagram's DirectMessageEditHistory list as versions (body, timestamp), oldest first. */
    private static List<EditHistoryStore.Version> serverVersions(List<?> list) {
        List<EditHistoryStore.Version> out = new ArrayList<>();
        if (list == null) return out;
        for (Object h : list) {
            try {
                if (h == null || !historyBody.getDeclaringClass().isInstance(h)) continue;
                String text = (String) historyBody.get(h);
                long time = historyTime == null ? 0 : EditHistoryTimeline.toMillis(historyTime.getLong(h));
                if (text != null && !text.isEmpty()) out.add(new EditHistoryStore.Version(text, time, false, false));
            } catch (Throwable ignored) {}
        }
        return out;
    }

    private static int count(Object msg) {
        try { return editCount == null ? -1 : editCount.getInt(msg); } catch (Throwable t) { return -1; }
    }

    private static long sent(Object msg) {
        try { return sentTime == null ? 0 : EditHistoryTimeline.toMillis((long) sentTime.invoke(msg)); } catch (Throwable t) { return 0; }
    }

    // ── Lookups ──────────────────────────────────────────────────────────────

    /** The message's own String field read by its "is emoji" check: the text. */
    private static Field bodyField(DexKitBridge bridge, ClassLoader cl, Class<?> message) throws Throwable {
        Field found = null;
        for (MethodData md : DexKitQueryCache.findMethod(bridge, FindMethod.create().matcher(MethodMatcher.create()
                .usingStrings("DirectMessage.updateIsMessageEmoji").paramCount(0).returnType("void")))) {
            for (var use : md.getUsingFields()) {
                if (!use.getField().getTypeName().equals("java.lang.String")) continue;
                Field f = use.getField().getFieldInstance(cl);
                // The same method also reads avatar text; only the message's own field is its body.
                if (!f.getDeclaringClass().isAssignableFrom(message) || Modifier.isStatic(f.getModifiers())) continue;
                if (found != null && !found.equals(f)) return null; // ambiguous
                found = f;
            }
        }
        return found;
    }

    /** The one no-arg method returning {@code type} that {@code caller} invokes on the message. */
    private static Method invoked(MethodData caller, ClassLoader cl, Class<?> message, Class<?> type) throws Throwable {
        if (caller == null) return null;
        Method found = null;
        for (MethodData inv : caller.getInvokes()) {
            if (!inv.isMethod() || inv.getParamCount() != 0) continue;
            Method m = inv.getMethodInstance(cl);
            if (m.getReturnType() != type || Modifier.isStatic(m.getModifiers())
                    || !m.getDeclaringClass().isAssignableFrom(message)) continue;
            if (found != null && !found.equals(m)) return null; // ambiguous
            found = m;
        }
        return found;
    }

    /** The one int field of the message that {@code caller} reads. */
    private static Field intRead(MethodData caller, ClassLoader cl, Class<?> message) throws Throwable {
        if (caller == null) return null;
        Field found = null;
        for (var use : caller.getUsingFields()) {
            if (!use.getUsingType().isRead() || !use.getField().getTypeName().equals("int")) continue;
            Field f = use.getField().getFieldInstance(cl);
            if (Modifier.isStatic(f.getModifiers()) || !f.getDeclaringClass().isAssignableFrom(message)) continue;
            if (found != null && !found.equals(f)) return null; // ambiguous
            found = f;
        }
        return found;
    }

    /** {@code lookup}'s method, found once per Instagram build and then read from DexKitCache. */
    private static Method cachedMethod(String key, ClassLoader cl, Lookup<Method> lookup) {
        String k = CACHE + key;
        try {
            if (DexKitCache.isCacheValid()) {
                if (MISSING.equals(DexKitCache.loadString(k))) return null;
                Method cached = DexKitCache.loadMethod(k, cl);
                if (cached != null) return cached;
            }
            Method found = lookup.find();
            if (found == null) DexKitCache.saveString(k, MISSING);
            else DexKitCache.saveMethod(k, found);
            return found;
        } catch (Throwable t) {
            ModuleLog.detail(TAG + "lookup " + key + " failed: " + t);
            return null;
        }
    }

    /** {@link #cachedMethod} for a field, kept as "declaringClass#name". */
    private static Field cachedField(String key, ClassLoader cl, Lookup<Field> lookup) {
        String k = CACHE + key;
        try {
            String cached = DexKitCache.isCacheValid() ? DexKitCache.loadString(k) : null;
            if (MISSING.equals(cached)) return null;
            if (cached != null) {
                int split = cached.lastIndexOf('#');
                try {
                    return Class.forName(cached.substring(0, split), false, cl).getDeclaredField(cached.substring(split + 1));
                } catch (ReflectiveOperationException | RuntimeException ignored) {} // stale: search again
            }
            Field found = lookup.find();
            DexKitCache.saveString(k, found == null ? MISSING : found.getDeclaringClass().getName() + "#" + found.getName());
            return found;
        } catch (Throwable t) {
            ModuleLog.detail(TAG + "lookup " + key + " failed: " + t);
            return null;
        }
    }
}
