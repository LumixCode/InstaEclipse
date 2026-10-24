package ps.reso.instaeclipse.utils.ghost;

import android.app.Activity;
import android.content.Context;
import android.content.ContextWrapper;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;

import java.lang.ref.WeakReference;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import ps.reso.instaeclipse.xposedcompat.XC_MethodHook;
import ps.reso.instaeclipse.xposedcompat.XposedHelpers;
import ps.reso.instaeclipse.utils.log.ModuleLog;

/**
 * Tracks the DM thread that is currently open, independent of which activity hosts it.
 *
 * Every DM thread screen is an androidx Fragment that receives its DirectThreadKey in its arguments
 * (e.g. "DirectFragment.DIRECT_FRAGMENT_ARGUMENT_THREAD_KEY"). androidx.fragment.app.Fragment keeps
 * its public lifecycle names and m* field names in every IG build (verified 436 / 447 / 451), and every
 * subclass must chain to super.onResume()/onPause() — so hooking the base methods sees every
 * fragment. A resumed fragment whose mArguments hold a DirectThreadKey is the open thread.
 *
 * Replaces two fragile id sources: the "igThreadIgid" (DirectThreadKey, boolean) tracker (string moved
 * off that method on IG 451) and the activity intent-extra scan (only works when the thread is hosted
 * in its own ModalActivity, not when it is a fragment inside InstagramMainActivity — issue #254).
 * Listeners are notified on the main thread after a thread fragment resumes, which also gives the
 * header-button hooks a reliable "thread opened" signal (a fragment transaction fires no activity
 * onResume).
 */
public final class CurrentThreadTracker {

    public interface Listener {
        void onThreadResumed(Activity activity, String threadId);

        default void onThreadPaused(Activity activity, String threadId) {}
    }

    interface Args {
        Iterable<String> keys();

        Object get(String key);

        Args nested(Object value);

        boolean isThreadKey(Object value);
    }

    private static final String DTK = "com.instagram.model.direct.DirectThreadKey";
    private static final List<Listener> LISTENERS = new CopyOnWriteArrayList<>();

    private static volatile boolean sInstalled;

    /** Resumed fragments that carry a thread key, oldest first (a thread-keyed sheet/viewer can sit
     *  on top of the open thread; when it pauses, the thread underneath is current again). */
    private static final java.util.ArrayList<Object[]> RESUMED = new java.util.ArrayList<>();

    private static final class Main {
        static final Handler HANDLER = new Handler(Looper.getMainLooper());
    }

    private CurrentThreadTracker() {}

    /** Thread id of the DM thread fragment currently resumed, or null. */
    public static String currentThreadId() {
        synchronized (RESUMED) {
            prune(null);
            return RESUMED.isEmpty() ? null : (String) RESUMED.get(RESUMED.size() - 1)[1];
        }
    }

    public static String currentThreadId(Activity activity) {
        if (activity == null) return null;
        synchronized (RESUMED) {
            prune(null);
            for (int i = RESUMED.size() - 1; i >= 0; i--) {
                Object[] entry = RESUMED.get(i);
                if (((WeakReference<?>) entry[2]).get() == activity) return (String) entry[1];
            }
            return null;
        }
    }

    private static Object[] prune(Object fragment) {
        Object[] removed = null;
        java.util.Iterator<Object[]> it = RESUMED.iterator();
        while (it.hasNext()) {
            Object[] entry = it.next();
            Object f = ((WeakReference<?>) entry[0]).get();
            if (f == null || f == fragment) {
                it.remove();
                if (f != null) removed = entry;
            }
        }
        return removed;
    }

    public static void addListener(Listener l) {
        LISTENERS.add(l);
    }

    public static synchronized void install(ClassLoader classLoader) {
        if (sInstalled) return;
        try {
            Class<?> fragment = Class.forName("androidx.fragment.app.Fragment", false, classLoader);
            XposedHelpers.findAndHookMethod(fragment, "onResume", new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) { ps.reso.instaeclipse.utils.core.JitGuard.tick(param.method);
                    onFragmentResumed(param.thisObject);
                }
            });
            XposedHelpers.findAndHookMethod(fragment, "onPause", new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) { ps.reso.instaeclipse.utils.core.JitGuard.tick(param.method);
                    Object[] paused;
                    synchronized (RESUMED) {
                        paused = prune(param.thisObject);
                    }
                    if (paused != null) notifyPaused(paused);
                }
            });
            sInstalled = true;
            ModuleLog.detail("(IE|ThreadTracker) Fragment onResume/onPause hooked");
        } catch (Throwable t) {
            ModuleLog.warn("(IE|ThreadTracker) Could not track which chat is open; chat-specific buttons and settings may not work", t);
        }
    }

    private static void onFragmentResumed(Object fragment) {
        try {
            Object argsObj = XposedHelpers.getObjectField(fragment, "mArguments");
            if (!(argsObj instanceof Bundle args)) return;
            String id = threadIdFromBundle(args, fragment.getClass().getClassLoader(), 0);
            if (id == null) return;
            Activity activity = activityOf(fragment);
            synchronized (RESUMED) {
                prune(fragment);
                RESUMED.add(new Object[]{new WeakReference<>(fragment), id, new WeakReference<>(activity)});
            }
            if (LISTENERS.isEmpty() || activity == null) return;
            final Activity a = activity;
            Main.HANDLER.post(() -> {
                if (!id.equals(currentThreadId())) return;
                for (Listener l : LISTENERS) {
                    try {
                        l.onThreadResumed(a, id);
                    } catch (Throwable ignored) {
                    }
                }
            });
        } catch (Throwable ignored) {
        }
    }

    private static void notifyPaused(Object[] entry) {
        Activity activity = (Activity) ((WeakReference<?>) entry[2]).get();
        if (activity == null || LISTENERS.isEmpty()) return;
        String id = (String) entry[1];
        Main.HANDLER.post(() -> {
            for (Listener l : LISTENERS) {
                try {
                    l.onThreadPaused(activity, id);
                } catch (Throwable ignored) {
                }
            }
        });
    }

    private static Activity activityOf(Object fragment) {
        try {
            Object view = XposedHelpers.getObjectField(fragment, "mView");
            return view instanceof View v ? findActivity(v.getContext()) : null;
        } catch (Throwable t) {
            return null;
        }
    }

    private static String threadIdFromBundle(Bundle b, ClassLoader cl, int depth) {
        try {
            return threadIdFrom(new BundleArgs(b, cl), depth);
        } catch (Throwable ignored) {
            return null;
        }
    }

    static String threadIdFrom(Args args, int depth) {
        if (args == null || depth > 2) return null;
        try {
            for (String k : args.keys()) {
                Object v = args.get(k);
                if (v == null) continue;
                if (args.isThreadKey(v)) {
                    String id = UnsentIdentity.threadId(v.toString());
                    if (id == null) id = firstStringField(v);
                    if (id != null) return id;
                } else {
                    String id = threadIdFrom(args.nested(v), depth + 1);
                    if (id != null) return id;
                }
            }
            for (String k : args.keys()) {
                Object v = args.get(k);
                if (v == null || !v.getClass().getName().contains("ThreadTarget")) continue;
                String id = numericId(v, 0, java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>()));
                if (id != null) return id;
            }
        } catch (Throwable ignored) {
        }
        return null;
    }

    private record BundleArgs(Bundle bundle, ClassLoader loader) implements Args {
        BundleArgs {
            if (loader != null) bundle.setClassLoader(loader);
        }

        @Override public Iterable<String> keys() {
            return bundle.keySet();
        }

        @Override public Object get(String key) {
            return bundle.get(key);
        }

        @Override public Args nested(Object value) {
            return value instanceof Bundle inner ? new BundleArgs(inner, loader) : null;
        }

        @Override public boolean isThreadKey(Object value) {
            return DTK.equals(value.getClass().getName());
        }
    }

    /** The first all-digit id (a thread id) in {@code o}'s fields, IG classes only, depth-limited. */
    private static String numericId(Object o, int depth, java.util.Set<Object> seen) {
        if (o == null || depth > 2 || !seen.add(o)) return null;
        if (o instanceof String str) return str.length() >= 10 && str.chars().allMatch(Character::isDigit) ? str : null;
        String cn = o.getClass().getName();
        if (!cn.startsWith("X.") && !cn.startsWith("com.instagram.")) return null;
        for (Class<?> c = o.getClass(); c != null && c != Object.class; c = c.getSuperclass()) {
            for (java.lang.reflect.Field f : ps.reso.instaeclipse.utils.core.SafeReflect.fields(c)) {
                if (java.lang.reflect.Modifier.isStatic(f.getModifiers()) || f.getType().isPrimitive()) continue;
                try {
                    f.setAccessible(true);
                    String r = numericId(f.get(o), depth + 1, seen);
                    if (r != null) return r;
                } catch (Throwable ignored) {}
            }
        }
        return null;
    }

    /** DirectThreadKey's thread id is its first non-empty instance String field (same rule as the hooks). */
    public static String firstStringField(Object o) {
        if (o == null) return null;
        try {
            for (Class<?> c = o.getClass(); c != null && c != Object.class; c = c.getSuperclass()) {
                for (Field f : ps.reso.instaeclipse.utils.core.SafeReflect.fields(c)) {
                    if (f.getType() != String.class || Modifier.isStatic(f.getModifiers())) continue;
                    f.setAccessible(true);
                    Object v = f.get(o);
                    if (v instanceof String s && !s.isEmpty()) return s;
                }
            }
        } catch (Throwable ignored) {
        }
        return null;
    }

    private static Activity findActivity(Context c) {
        while (c instanceof ContextWrapper) {
            if (c instanceof Activity a) return a;
            c = ((ContextWrapper) c).getBaseContext();
        }
        return null;
    }
}
