package ps.reso.instaeclipse.utils.core;

import android.app.Activity;
import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.widget.Toast;

import java.util.Collections;
import java.util.concurrent.atomic.AtomicBoolean;

import ps.reso.instaeclipse.R;
import ps.reso.instaeclipse.mods.ui.UIHookManager;
import ps.reso.instaeclipse.utils.i18n.I18n;
import ps.reso.instaeclipse.utils.ipc.BrokerPayload;
import ps.reso.instaeclipse.utils.ipc.CompanionBridge;
import ps.reso.instaeclipse.utils.log.ModuleLog;
import ps.reso.instaeclipse.utils.toast.IeToast;

public final class IgRelaunch {

    public static final long WATCHDOG_MS = 12_000;
    public static final long ALARM_DELAY_MS = 1_500;

    private static final AtomicBoolean busy = new AtomicBoolean();

    private IgRelaunch() {}

    public static void request(Context context, ComponentName launcher, Runnable exit) {
        if (!busy.compareAndSet(false, true)) {
            ModuleLog.detail("(IE|Restart) A restart is already under way, so this request was ignored");
            return;
        }
        Handler main = new Handler(Looper.getMainLooper());
        Activity front = UIHookManager.topActivity();
        if (front == null || front.isFinishing() || front.isDestroyed()) {
            ModuleLog.line("(IE|Restart) Instagram has no screen in front, so it closes without opening again");
            busy.set(false);
            exit.run();
            return;
        }
        CompanionBridge.openActivity(front, BrokerPayload.RESTART, Collections.emptyMap(), error -> {
            if (error != null) {
                ModuleLog.warn("(IE|Restart) The InstaEclipse app could not take over the restart (" + error + "), so Instagram restarts on its own");
                fallback(context, launcher, exit);
                return;
            }
            ModuleLog.line("(IE|Restart) The InstaEclipse app was asked to close Instagram and open it again");
            main.postDelayed(() -> stuck(context), WATCHDOG_MS);
        });
    }

    private static void stuck(Context context) {
        busy.set(false);
        ModuleLog.warn("(IE|Restart) Instagram is still running " + (WATCHDOG_MS / 1000) + " s after the restart was handed to the InstaEclipse app");
        IeToast.makeText(context, I18n.t(context, R.string.ig_toast_restart_manual), Toast.LENGTH_LONG).show();
    }

    private static void fallback(Context context, ComponentName launcher, Runnable exit) {
        try {
            AlarmManager alarms = (AlarmManager) context.getSystemService(Context.ALARM_SERVICE);
            if (alarms != null && launcher != null) {
                PendingIntent start = PendingIntent.getActivity(context, 0, Intent.makeRestartActivityTask(launcher),
                        PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT, InstagramRestarter.iconSplash());
                alarms.set(AlarmManager.ELAPSED_REALTIME_WAKEUP, SystemClock.elapsedRealtime() + ALARM_DELAY_MS, start);
                ModuleLog.line("(IE|Restart) Instagram closes now and is set to open again in " + ALARM_DELAY_MS + " ms (Android 10 and newer may refuse that)");
            }
        } catch (Throwable t) {
            ModuleLog.warn("(IE|Restart) Instagram could not be set to open again", t);
        }
        busy.set(false);
        exit.run();
    }
}
