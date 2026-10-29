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
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Predicate;

import ps.reso.instaeclipse.xposedcompat.XC_MethodHook;
import ps.reso.instaeclipse.xposedcompat.XposedHelpers;
import ps.reso.instaeclipse.utils.log.ModuleLog;

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
    private static volatile Field argumentsField, viewField;
    private static final Map<Class<?>, Object[][]> CONTROLLERS = new ConcurrentHashMap<>();

    private static final java.util.ArrayList<Object[]> RESUMED = new java.util.ArrayList<>();

    private static final class Main {
        static final Handler HANDLER = new Handler(Looper.getMainLooper());
    }

    private CurrentThreadTracker() {}

    public static String currentThreadId() {
        synchronized (RESUMED) {
            prune(null);
            return RESUMED.isEmpty() ? null : (String) RESUMED.get(RESUMED.size() - 1)[1];
        }
    }

    public static String currentThreadId(Activity activity) {
        Object[] entry = entryOf(activity);
        if (entry == null) return null;
        String live = threadIdOf(((WeakReference<?>) entry[0]).get());
        return live != null ? live : (String) entry[1];
    }

    public static Object fragment(Activity activity, Predicate<Object> accept) {
        if (activity == null) return null;
        List<Object> fragments = new ArrayList<>();
        synchronized (RESUMED) {
            prune(null);
            for (int i = RESUMED.size() - 1; i >= 0; i--) {
                Object[] entry = RESUMED.get(i);
                Object fragment = ((WeakReference<?>) entry[0]).get();
                if (fragment != null && ((WeakReference<?>) entry[2]).get() == activity) fragments.add(fragment);
            }
        }
        for (Object fragment : fragments) if (accept.test(fragment)) return fragment;
        return null;
    }

    private static Object[] entryOf(Activity activity) {
        if (activity == null) return null;
        synchronized (RESUMED) {
            prune(null);
            for (int i = RESUMED.size() - 1; i >= 0; i--) {
                Object[] entry = RESUMED.get(i);
                if (((WeakReference<?>) entry[2]).get() == activity) return entry;
            }
            return null;
        }
    }

    public static String threadIdOf(Object fragment) {
        if (fragment == null) return null;
        for (Object[] path : controllers(fragment.getClass())) {
            try {
                Object controller = ((Field) path[0]).get(fragment);
                Object key = controller == null ? null : ((Method) path[1]).invoke(controller);
                String id = key == null ? null : UnsentIdentity.threadId(key.toString());
                if (id != null) return id;
            } catch (ReflectiveOperationException | RuntimeException ignored) {
            }
        }
        return null;
    }

    private static Object[][] controllers(Class<?> type) {
        return CONTROLLERS.computeIfAbsent(type, ignored -> {
            List<Object[]> found = new ArrayList<>();
            for (Class<?> c = type; c != null && c != Object.class; c = c.getSuperclass()) {
                if (c.getName().startsWith("android")) break;
                for (Field field : ps.reso.instaeclipse.utils.core.SafeReflect.fields(c)) {
                    if (Modifier.isStatic(field.getModifiers()) || field.getType().isPrimitive()) continue;
                    String owner = field.getType().getName();
                    if (!owner.startsWith("X.") && !owner.startsWith("com.instagram.")) continue;
                    for (Method method : ps.reso.instaeclipse.utils.core.SafeReflect.methods(field.getType())) {
                        if (Modifier.isStatic(method.getModifiers()) || method.getParameterCount() != 0
                                || !DTK.equals(method.getReturnType().getName())) continue;
                        try {
                            field.setAccessible(true);
                            method.setAccessible(true);
                            found.add(new Object[]{field, method});
                        } catch (RuntimeException ignoredAccess) {
                        }
                        break;
                    }
                }
            }
            return found.toArray(new Object[0][]);
        });
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
            argumentsField = field(fragment, "mArguments");
            viewField = field(fragment, "mView");
            XposedHelpers.findAndHookMethod(fragment, "onResume", new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    onFragmentResumed(param.thisObject);
                }
            });
            XposedHelpers.findAndHookMethod(fragment, "onPause", new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
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

    private static Field field(Class<?> type, String name) {
        try {
            Field field = type.getDeclaredField(name);
            field.setAccessible(true);
            return field;
        } catch (Throwable t) {
            return null;
        }
    }

    private static Object read(Field field, Object owner, String name) throws IllegalAccessException {
        return field != null ? field.get(owner) : XposedHelpers.getObjectField(owner, name);
    }

    private static void onFragmentResumed(Object fragment) {
        try {
            Object argsObj = read(argumentsField, fragment, "mArguments");
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
            Object view = read(viewField, fragment, "mView");
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
            List<Object> values = new ArrayList<>();
            for (String k : args.keys()) {
                if (!threadName(k)) continue;
                Object v = args.get(k);
                if (v == null) continue;
                values.add(v);
                if (args.isThreadKey(v)) {
                    String id = UnsentIdentity.threadId(v.toString());
                    if (id != null) return id;
                } else {
                    String id = threadIdFrom(args.nested(v), depth + 1);
                    if (id != null) return id;
                }
            }
            for (Object v : values) {
                if (!v.getClass().getName().contains("ThreadTarget")) continue;
                String id = numericId(v, 0, java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>()));
                if (id != null) return id;
            }
        } catch (Throwable ignored) {
        }
        return null;
    }

    static boolean threadName(String key) {
        return key != null && key.toUpperCase(Locale.ROOT).contains("THREAD");
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
