package ps.reso.instaeclipse.mods.ghost;

import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;

import org.luckypray.dexkit.query.FindMethod;
import org.luckypray.dexkit.query.matchers.MethodMatcher;

import java.lang.ref.WeakReference;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.function.BooleanSupplier;

import ps.reso.instaeclipse.utils.core.DexKitQueryCache;
import ps.reso.instaeclipse.utils.dialog.MenuWorker;
import ps.reso.instaeclipse.utils.ghost.ThreadNames;
import ps.reso.instaeclipse.utils.ghost.UnsentIdentity;
import ps.reso.instaeclipse.utils.log.ModuleLog;

final class HiddenChatLoader {

    private static final String LOADER_ANCHOR = "DirectThreadLoader";
    private static final String LOAD_REASON = "LOAD_AND_OPEN_GROUP_THREAD";
    private static final int PACE_MS = 250;
    private static final int SETTLE_MS = 3000;

    private static volatile Method loader;

    private final String account;
    private final WeakReference<Object> session;
    private final Set<String> requested = new HashSet<>();
    private final Set<String> elsewhere = new HashSet<>();
    private long settlesAt;

    HiddenChatLoader(Object owner, String account) {
        this.account = account;
        this.session = new WeakReference<>(sessionOf(owner));
    }

    static void install(ClassLoader classLoader) {
        try {
            List<Method> found = KeepUnsentMessagesHook.cachedMethods("HideChats_threadLoader_v1", classLoader,
                    () -> KeepUnsentMessagesHook.methodsOf(DexKitQueryCache.methods(FindMethod.create()
                            .matcher(MethodMatcher.create().usingStrings(LOADER_ANCHOR)
                                    .paramTypes(UnsentIdentity.SESSION, null, null, "java.lang.String", "boolean")
                                    .returnType("void"))), classLoader));
            Method method = found.size() == 1 ? found.get(0) : null;
            if (method == null || !Modifier.isStatic(method.getModifiers())
                    || !method.getParameterTypes()[1].isEnum() || !method.getParameterTypes()[2].isInterface()) {
                ModuleLog.detail("(IE|HideChats) thread loader not usable, " + found.size() + " match(es)");
                return;
            }
            method.setAccessible(true);
            loader = method;
            ModuleLog.detail("(IE|HideChats) thread loader ready: " + method.getDeclaringClass().getName()
                    + "." + method.getName());
        } catch (Throwable t) {
            ModuleLog.detail("(IE|HideChats) thread loader lookup failed: " + t.getMessage());
        }
    }

    private static Object sessionOf(Object owner) {
        Method method = loader;
        if (method == null) return null;
        try {
            return UnsentIdentity.directValue(owner, method.getParameterTypes()[0]);
        } catch (Throwable t) {
            ModuleLog.detail("(IE|HideChats) no session for the thread loader: " + t.getMessage());
            return null;
        }
    }

    private static Object reasonOf(Class<?> type) {
        Object[] reasons = type.getEnumConstants();
        for (Object reason : reasons) if (LOAD_REASON.equals(((Enum<?>) reason).name())) return reason;
        return reasons[0];
    }

    synchronized void reopen() {
        requested.clear();
        elsewhere.clear();
    }

    void load(Set<String> missing, BooleanSupplier wanted, Runnable settled) {
        Set<String> fresh = new LinkedHashSet<>(missing);
        synchronized (this) {
            fresh.removeAll(requested);
            if (fresh.isEmpty()) return;
            requested.addAll(fresh);
            settlesAt = Long.MAX_VALUE;
        }
        MenuWorker.load(() -> ThreadNames.otherAccounts(account, fresh), others -> fetch(fresh, others, wanted, settled));
    }

    private void fetch(Set<String> ids, Set<String> others, BooleanSupplier wanted, Runnable settled) {
        Handler main = new Handler(Looper.getMainLooper());
        int calls = 0;
        try {
            if (others != null) {
                ids.removeAll(others);
                synchronized (this) { elsewhere.addAll(others); }
            }
            Object user = session.get();
            Method method = loader;
            if (method != null && user != null) {
                Object reason = reasonOf(method.getParameterTypes()[1]);
                for (String id : ids) {
                    main.postDelayed(() -> call(method, user, reason, id, wanted), (long) calls * PACE_MS);
                    calls++;
                }
            }
            ModuleLog.detail("(IE|HideChats) hidden chats without a row: loading " + calls + " of " + ids.size()
                    + ", " + (others == null ? 0 : others.size()) + " on another account");
        } catch (Throwable t) {
            ModuleLog.detail("(IE|HideChats) could not ask for hidden chats: " + t.getMessage());
        }
        long wait = calls == 0 ? 0 : (long) calls * PACE_MS + SETTLE_MS;
        synchronized (this) { settlesAt = SystemClock.uptimeMillis() + wait; }
        main.postDelayed(settled, wait);
    }

    private static void call(Method method, Object user, Object reason, String id, BooleanSupplier wanted) {
        try {
            if (!wanted.getAsBoolean()) return;
            method.invoke(null, user, reason, null, id, false);
            ModuleLog.detail("(IE|HideChats) asked Instagram to load hidden chat " + id);
        } catch (Throwable t) {
            ModuleLog.detail("(IE|HideChats) could not load hidden chat " + id + ": " + t);
        }
    }

    synchronized List<String> unloaded(Set<String> missing) {
        if (SystemClock.uptimeMillis() < settlesAt) return Collections.emptyList();
        List<String> left = new ArrayList<>(missing);
        left.removeAll(elsewhere);
        return left;
    }
}
