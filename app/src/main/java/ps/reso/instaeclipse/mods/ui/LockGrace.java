package ps.reso.instaeclipse.mods.ui;

import android.app.Activity;
import android.app.Instrumentation;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.ResolveInfo;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;

import java.lang.ref.WeakReference;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.LongSupplier;

import ps.reso.instaeclipse.utils.feature.FeatureFlags;
import ps.reso.instaeclipse.utils.log.ModuleLog;
import ps.reso.instaeclipse.xposedcompat.XC_MethodHook;
import ps.reso.instaeclipse.xposedcompat.XposedBridge;

public final class LockGrace {

    static final long ARM_MS = 2_000L;
    static final long GRACE_MS = 60_000L;

    private enum State { IDLE, MARKED, ARMED, CHECKING }

    private static final LockGrace GRACE = new LockGrace(SystemClock::elapsedRealtime);
    private static final Map<String, Boolean> OUTSIDE = new ConcurrentHashMap<>();
    private static final Runnable EXPIRE = LockGrace::endGrace;
    private static Handler handler;
    private static BroadcastReceiver receiver;
    private static Context receiverContext;

    private final LongSupplier clock;
    private State state = State.IDLE;
    private WeakReference<Object> starter = new WeakReference<>(null);
    private long since;

    LockGrace(LongSupplier clock) {
        this.clock = clock;
    }

    synchronized boolean mark(Object activity, boolean unlocked, boolean outside) {
        if (activity == null || !unlocked || !outside) return false;
        state = State.MARKED;
        starter = new WeakReference<>(activity);
        since = clock.getAsLong();
        return true;
    }

    synchronized boolean background() {
        long now = clock.getAsLong();
        if (state == State.MARKED && now >= since && now - since <= ARM_MS && starter.get() != null) {
            state = State.ARMED;
            since = now;
            return true;
        }
        clear();
        return false;
    }

    synchronized void end() {
        clear();
    }

    synchronized boolean waiting() {
        return state != State.IDLE;
    }

    synchronized boolean resume(Object activity) {
        if (state == State.ARMED && inWindow() && activity != null && starter.get() == activity) {
            state = State.CHECKING;
            return true;
        }
        clear();
        return false;
    }

    synchronized boolean holding(Object activity) {
        return state == State.CHECKING && activity != null && starter.get() == activity;
    }

    synchronized boolean use(Object activity) {
        boolean passes = holding(activity) && inWindow();
        clear();
        return passes;
    }

    static boolean endsGrace(String action) {
        return Intent.ACTION_CLOSE_SYSTEM_DIALOGS.equals(action) || Intent.ACTION_SCREEN_OFF.equals(action);
    }

    private boolean inWindow() {
        long now = clock.getAsLong();
        return now >= since && now - since <= GRACE_MS;
    }

    private void clear() {
        state = State.IDLE;
        starter = new WeakReference<>(null);
    }

    public static void install() {
        try {
            int n = XposedBridge.hookAllMethods(Instrumentation.class, "execStartActivity", new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) { ps.reso.instaeclipse.utils.core.JitGuard.tick(param.method);
                    if (!FeatureFlags.lockWholeApp) return;
                    try {
                        started(param.args);
                    } catch (Throwable t) {
                        ModuleLog.detail("(IE|LockGrace) Could not check this screen change: " + t);
                    }
                }
            }).size();
            ModuleLog.detail("(IE|LockGrace) hooked execStartActivity x" + n);
        } catch (Throwable t) {
            ModuleLog.warn("(IE|LockGrace) Lock Instagram asks again after every photo picker, camera or link trip", t);
        }
    }

    private static void started(Object[] args) {
        if (args == null || args.length < 4) return;
        Context who = args[0] instanceof Context c ? c : null;
        Activity target = args[3] instanceof Activity ta ? ta : who instanceof Activity wa ? wa : null;
        Intent intent = null;
        for (Object arg : args) {
            if (arg instanceof Intent i) {
                intent = i;
                break;
            }
        }
        starting(who, target, intent);
    }

    static void starting(Context who, Activity target, Intent intent) {
        if (who == null || target == null || intent == null || !LockDirectMessagesHook.appUnlocked()) return;
        if (GRACE.mark(target, true, leavesInstagram(who, intent))) listen(who);
    }

    static void appBackground() {
        if (!GRACE.background()) {
            unlisten();
            return;
        }
        Handler h = main();
        h.removeCallbacks(EXPIRE);
        h.postDelayed(EXPIRE, GRACE_MS);
    }

    static void appResumed(Activity activity) {
        if (!GRACE.resume(activity)) {
            unlisten();
            return;
        }
        Handler h = main();
        h.post(() -> h.post(() -> {
            boolean passes = GRACE.use(activity);
            unlisten();
            LockDirectMessagesHook.graceChecked(activity, passes);
        }));
    }

    static boolean holds(Activity activity) {
        return GRACE.holding(activity);
    }

    static void endGrace() {
        GRACE.end();
        unlisten();
    }

    private static boolean leavesInstagram(Context who, Intent intent) {
        ComponentName component = intent.getComponent();
        if (component == null) return resolvesOutside(who, intent, null);
        String key = component.flattenToShortString();
        Boolean cached = OUTSIDE.get(key);
        if (cached != null) return cached;
        boolean outside = resolvesOutside(who, intent, component.getPackageName());
        OUTSIDE.put(key, outside);
        return outside;
    }

    private static boolean resolvesOutside(Context who, Intent intent, String fallback) {
        ResolveInfo info = who.getPackageManager().resolveActivity(intent, 0);
        String pkg = info != null && info.activityInfo != null ? info.activityInfo.packageName : fallback;
        return pkg != null && !pkg.equals(who.getPackageName());
    }

    private static synchronized void listen(Context who) {
        if (receiver != null) return;
        Context app = who.getApplicationContext() != null ? who.getApplicationContext() : who;
        BroadcastReceiver r = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                if (intent != null && endsGrace(intent.getAction())) endGrace();
            }
        };
        IntentFilter filter = new IntentFilter(Intent.ACTION_CLOSE_SYSTEM_DIALOGS);
        filter.addAction(Intent.ACTION_SCREEN_OFF);
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) app.registerReceiver(r, filter, Context.RECEIVER_NOT_EXPORTED);
            else app.registerReceiver(r, filter);
            receiver = r;
            receiverContext = app;
        } catch (Throwable t) {
            GRACE.end();
            ModuleLog.detail("(IE|LockGrace) Could not listen for Home or screen off, so this trip asks again: " + t);
        }
    }

    private static synchronized void unlisten() {
        if (handler != null) handler.removeCallbacks(EXPIRE);
        if (receiver == null) return;
        try {
            receiverContext.unregisterReceiver(receiver);
        } catch (Throwable ignored) {
        }
        receiver = null;
        receiverContext = null;
    }

    private static synchronized Handler main() {
        if (handler == null) handler = new Handler(Looper.getMainLooper());
        return handler;
    }
}
