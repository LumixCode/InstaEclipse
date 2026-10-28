package ps.reso.instaeclipse.utils.core;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.provider.Settings;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

import ps.reso.instaeclipse.R;
import ps.reso.instaeclipse.utils.ipc.ModuleReplies;
import ps.reso.instaeclipse.utils.toast.IeToast;

public final class InstagramRestarter {

    public interface Callback {
        void onDone(RestartDecision.Outcome outcome);
    }

    public interface Channel {
        Runnable ask(Context context, Intent request, Runnable answered);
    }

    public static final long ACK_TIMEOUT_MS = 1_500;
    public static final long PING_TIMEOUT_MS = 1_000;
    private static final long FORCE_STOP_TIMEOUT_S = 15;
    private static final Channel IPC = InstagramRestarter::askInstagram;
    private static volatile Channel channel = IPC;

    private InstagramRestarter() {}

    public static void overrideChannelForTest(Channel testChannel) {
        channel = testChannel == null ? IPC : testChannel;
    }

    public static List<String> installedPackages(Context context) {
        PackageManager pm = context.getPackageManager();
        List<String> out = new ArrayList<>();
        for (String pkg : CommonUtils.SUPPORTED_PACKAGES) {
            if (isInstalled(pm, pkg)) out.add(pkg);
        }
        return out;
    }

    public static String activePackage(Context context, List<String> installed) {
        if (installed.isEmpty()) return null;
        String remembered = CompanionUiPrefs.get(context).getString(CompanionUiPrefs.INSTAGRAM_PACKAGE, null);
        if (remembered != null && installed.contains(remembered)) return remembered;
        return installed.contains(CommonUtils.IG_PACKAGE_NAME) ? CommonUtils.IG_PACKAGE_NAME : installed.get(0);
    }

    public static boolean ping(Context context, String pkg, Consumer<Boolean> running) {
        if (pkg == null) return false;
        return ask(context, new Intent(CommonUtils.ACTION_REQUEST_PREFS).setPackage(pkg), PING_TIMEOUT_MS,
                new Handler(Looper.getMainLooper()), running);
    }

    public static void restart(Activity activity, String pkg, Callback callback) {
        Handler main = new Handler(Looper.getMainLooper());
        if (pkg == null || !isInstalled(activity.getPackageManager(), pkg)) {
            finish(activity, null, RestartDecision.decide(null, false, false, false, null), 0, callback);
            return;
        }
        Intent request = new Intent(CommonUtils.ACTION_RESTART_INSTAGRAM).setPackage(pkg)
                .putExtra(CommonUtils.EXTRA_REQUEST_TIME, SystemClock.elapsedRealtime());
        boolean asked = ask(activity, request, ACK_TIMEOUT_MS, main, ack -> {
            if (!ack) {
                finish(activity, pkg, RestartDecision.decide(pkg, false, false, false, null), 0, callback);
                return;
            }
            main.postDelayed(() -> {
                boolean pinged = ping(activity, pkg, running -> afterAck(activity, pkg, running, callback));
                if (!pinged) afterAck(activity, pkg, false, callback);
            }, RestartDecision.RELAUNCH_DELAY_MS);
        });
        if (!asked) forceStop(pkg, main, (su, exit) -> finish(activity, pkg,
                RestartDecision.unreachable(pkg, su, exit), RestartDecision.RELAUNCH_DELAY_MS, callback));
    }

    private static void afterAck(Activity activity, String pkg, boolean running, Callback callback) {
        if (!running) {
            finish(activity, pkg, RestartDecision.decide(pkg, true, false, false, null), 0, callback);
            return;
        }
        forceStop(pkg, new Handler(Looper.getMainLooper()), (su, exit) -> finish(activity, pkg,
                RestartDecision.decide(pkg, true, true, su, exit), RestartDecision.RELAUNCH_DELAY_MS, callback));
    }

    private interface Stopped {
        void onStopped(boolean suAvailable, Integer exit);
    }

    private static void forceStop(String pkg, Handler main, Stopped stopped) {
        new Thread(() -> {
            boolean su = false;
            Integer exit = null;
            try {
                Process p = Runtime.getRuntime().exec(new String[]{"su", "-c", "am force-stop " + pkg});
                su = true;
                if (p.waitFor(FORCE_STOP_TIMEOUT_S, TimeUnit.SECONDS)) {
                    exit = p.exitValue();
                } else {
                    p.destroy();
                }
            } catch (Throwable ignored) { }
            boolean available = su;
            Integer code = exit;
            main.post(() -> stopped.onStopped(available, code));
        }, "ie-restart-ig").start();
    }

    private static boolean ask(Context context, Intent request, long timeoutMs, Handler main, Consumer<Boolean> answer) {
        AtomicBoolean settled = new AtomicBoolean();
        AtomicReference<Runnable> cancel = new AtomicReference<>();
        Runnable timeout = () -> {
            if (!settled.compareAndSet(false, true)) return;
            Runnable c = cancel.get();
            if (c != null) c.run();
            answer.accept(false);
        };
        Runnable sent;
        try {
            sent = channel.ask(context, request, () -> main.post(() -> {
                if (!settled.compareAndSet(false, true)) return;
                main.removeCallbacks(timeout);
                Runnable c = cancel.get();
                if (c != null) c.run();
                answer.accept(true);
            }));
        } catch (RuntimeException unavailable) {
            sent = null;
        }
        if (sent == null) return false;
        cancel.set(sent);
        main.postDelayed(timeout, timeoutMs);
        return true;
    }

    private static Runnable askInstagram(Context context, Intent request, Runnable answered) {
        String nonce = IpcSecurity.newNonce();
        Intent signed = new Intent(request).putExtra(IpcSecurity.EXTRA_NONCE, nonce);
        ModuleReplies.Pending pending = ModuleReplies.request(context, signed, data -> {
            if (IpcSecurity.nonceMatches(nonce, new Intent().putExtras(data))) answered.run();
        });
        return pending == null ? null : pending::cancel;
    }

    private static void finish(Activity activity, String pkg, RestartDecision.Outcome outcome, long relaunchDelayMs,
                               Callback callback) {
        if (activity.isFinishing() || activity.isDestroyed()) return;
        switch (outcome) {
            case RELAUNCH:
                if (relaunchDelayMs <= 0) {
                    launch(activity, pkg);
                } else {
                    new Handler(Looper.getMainLooper()).postDelayed(() -> launch(activity, pkg), relaunchDelayMs);
                }
                break;
            case NOT_RUNNING:
                IeToast.makeText(activity, activity.getString(R.string.home_restart_applies_on_open), Toast.LENGTH_LONG).show();
                break;
            case APP_INFO:
                IeToast.makeText(activity, activity.getString(R.string.ig_toast_restart_manual), Toast.LENGTH_LONG).show();
                try {
                    Intent intent = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS);
                    intent.setData(Uri.parse("package:" + pkg));
                    intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                    activity.startActivity(intent);
                } catch (Throwable ignored) { }
                break;
            case NOT_INSTALLED:
                IeToast.makeText(activity, activity.getString(R.string.not_installed_instagram), Toast.LENGTH_SHORT).show();
                break;
        }
        callback.onDone(outcome);
    }

    private static boolean isInstalled(PackageManager pm, String pkg) {
        try {
            pm.getPackageInfo(pkg, 0);
            return true;
        } catch (PackageManager.NameNotFoundException e) {
            return false;
        }
    }

    private static void launch(Activity activity, String pkg) {
        if (activity.isFinishing() || activity.isDestroyed()) return;
        Intent launch = activity.getPackageManager().getLaunchIntentForPackage(pkg);
        if (launch != null) {
            activity.startActivity(launch);
        } else {
            IeToast.makeText(activity, activity.getString(R.string.not_installed_instagram), Toast.LENGTH_SHORT).show();
        }
    }
}
