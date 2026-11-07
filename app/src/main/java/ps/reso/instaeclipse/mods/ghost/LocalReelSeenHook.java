package ps.reso.instaeclipse.mods.ghost;

import android.app.Activity;
import android.os.Looper;
import android.os.SystemClock;

import org.luckypray.dexkit.query.FindMethod;
import org.luckypray.dexkit.query.matchers.MethodMatcher;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;

import ps.reso.instaeclipse.mods.ui.UIHookManager;
import ps.reso.instaeclipse.utils.core.DexKitCache;
import ps.reso.instaeclipse.utils.core.DexKitQueryCache;
import ps.reso.instaeclipse.utils.core.DexKitQueryCache.Found;
import ps.reso.instaeclipse.utils.feature.FeatureFlags;
import ps.reso.instaeclipse.utils.ghost.ReelSeenTimes;
import ps.reso.instaeclipse.utils.ghost.UnsentIdentity;
import ps.reso.instaeclipse.utils.log.ModuleLog;
import ps.reso.instaeclipse.xposedcompat.XC_MethodHook;
import ps.reso.instaeclipse.xposedcompat.XposedBridge;

public final class LocalReelSeenHook {

    private static final String TAG = "(IE|ReelSeen) ";
    private static final String CACHE_KEY = "LocalReelSeen_container_v1";
    private static final String ANCHOR = "LocalReelSeenStateSerialize";
    private static final long GUESS_EVERY_MS = 1000;
    private static final Map<Object, String> accounts = new WeakHashMap<>();
    private static volatile long lastGuess = -GUESS_EVERY_MS;

    public void install(ClassLoader loader) {
        try {
            Constructor<?> container = container(loader);
            Class<?> state = container.getParameterTypes()[1];
            Method getter = null, putter = null, reset = null;
            for (Method m : state.getDeclaredMethods()) {
                Class<?>[] p = m.getParameterTypes();
                boolean isStatic = Modifier.isStatic(m.getModifiers());
                if (!isStatic && m.getReturnType() == long.class && p.length == 1 && p[0] == String.class) {
                    getter = only(getter, m, "seen time lookup");
                } else if (isStatic && m.getReturnType() == void.class && p.length == 3 && p[0] == state
                        && p[1] == String.class && p[2] == long.class) {
                    putter = only(putter, m, "seen time write");
                }
            }
            List<Method> resets = new ArrayList<>();
            for (Method m : container.getDeclaringClass().getDeclaredMethods()) {
                Class<?>[] p = m.getParameterTypes();
                if (!Modifier.isStatic(m.getModifiers()) && m.getReturnType() == void.class
                        && p.length == 1 && p[0] == String.class) resets.add(m);
            }
            if (resets.size() == 1) reset = only(null, resets.get(0), "seen time reset");
            if (getter == null || putter == null) throw new NoSuchMethodException("seen time lookup or write");
            if (FeatureFlags.isGhostStory) ReelSeenTimes.load();
            hook(container, getter, putter, reset);
            ModuleLog.detail(TAG + "keeping watched stories and highlights through " + state.getName()
                    + (reset == null ? " (Instagram's own seen reset not found)" : ""));
        } catch (Throwable t) {
            ModuleLog.warn(TAG + "Watched highlights may show as unwatched again after a restart: " + t);
        }
    }

    private static Constructor<?> container(ClassLoader loader) throws Exception {
        if (DexKitCache.isCacheValid()) {
            String cached = DexKitCache.loadString(CACHE_KEY);
            if (cached != null) {
                try {
                    return containerOf(Class.forName(cached, false, loader));
                } catch (Throwable t) {
                    ModuleLog.detail(TAG + "saved lookup failed: " + t);
                }
            }
        }
        List<Found> found = new ArrayList<>();
        for (Found f : DexKitQueryCache.methods(FindMethod.create().matcher(MethodMatcher.create()
                .usingStrings(ANCHOR)))) {
            if (f.isConstructor()) found.add(f);
        }
        if (found.size() != 1) throw new IllegalStateException(found.size() + " seen state holders");
        Constructor<?> container = containerOf(found.get(0).getConstructorInstance(loader).getDeclaringClass());
        DexKitCache.saveString(CACHE_KEY, container.getDeclaringClass().getName());
        return container;
    }

    private static Constructor<?> containerOf(Class<?> type) throws NoSuchMethodException {
        Constructor<?> picked = null;
        for (Constructor<?> c : type.getDeclaredConstructors()) {
            Class<?>[] p = c.getParameterTypes();
            if (p.length != 2 || !p[0].getName().equals(ManualStorySeenHook.SESSION) || p[1].isPrimitive()) continue;
            if (picked != null) throw new IllegalStateException("two seen state holder constructors");
            picked = c;
        }
        if (picked == null) throw new NoSuchMethodException("seen state holder constructor");
        return picked;
    }

    private static Method only(Method known, Method found, String what) {
        if (known != null) throw new IllegalStateException("two candidates for " + what);
        found.setAccessible(true);
        return found;
    }

    private static String account(Object owner) {
        if (owner == null) return null;
        synchronized (accounts) {
            String known = accounts.get(owner);
            if (known != null) return known;
        }
        long now = SystemClock.uptimeMillis();
        if (Looper.myLooper() != Looper.getMainLooper() || now - lastGuess < GUESS_EVERY_MS) return null;
        lastGuess = now;
        Activity top = UIHookManager.topActivity();
        String current = top == null ? null : HideChatsHook.currentAccount(top);
        if (current == null) return null;
        synchronized (accounts) { accounts.putIfAbsent(owner, current); }
        ModuleLog.detail(TAG + "seen state was made before the hooks; using the open account");
        return current;
    }

    private static void hook(Constructor<?> container, Method getter, Method putter, Method reset) {
        XposedBridge.hookMethod(container, new XC_MethodHook() {
            @Override protected void afterHookedMethod(MethodHookParam p) {
                String account = UnsentIdentity.account(p.args[0]);
                if (account == null) return;
                synchronized (accounts) {
                    accounts.put(p.thisObject, account);
                    if (p.args[1] != null) accounts.put(p.args[1], account);
                }
            }
        });
        XposedBridge.hookMethod(getter, new XC_MethodHook() {
            @Override protected void afterHookedMethod(MethodHookParam p) {
                if (!FeatureFlags.isGhostStory || !(p.args[0] instanceof String reel)
                        || !(p.getResult() instanceof Long seen)) return;
                long kept = ReelSeenTimes.get(account(p.thisObject), reel);
                if (kept > seen) p.setResult(kept);
            }
        });
        XposedBridge.hookMethod(putter, new XC_MethodHook() {
            @Override protected void afterHookedMethod(MethodHookParam p) {
                if (!FeatureFlags.isGhostStory || !(p.args[1] instanceof String reel)
                        || !(p.args[2] instanceof Long seen) || seen <= 0) return;
                ReelSeenTimes.put(account(p.args[0]), reel, seen);
            }
        });
        if (reset == null) return;
        XposedBridge.hookMethod(reset, new XC_MethodHook() {
            @Override protected void beforeHookedMethod(MethodHookParam p) {
                if (p.args[0] instanceof String reel) ReelSeenTimes.remove(account(p.thisObject), reel);
            }
        });
    }
}
