package ps.reso.instaeclipse.utils.core;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.SystemClock;

import java.util.concurrent.Executor;

import ps.reso.instaeclipse.utils.ipc.ModuleReplies;
import ps.reso.instaeclipse.utils.log.ModuleLog;

public final class IgSelfRestart {

    public static final long MAX_AGE_MS = 3_000;
    public static final long EXIT_DELAY_MS = 300;

    public interface Steps {
        void save();
        boolean ack();
        void pause(long ms);
        void exit();
    }

    private IgSelfRestart() {}

    public static boolean fresh(long requestTime, long now) {
        return requestTime > 0 && now >= requestTime && now - requestTime <= MAX_AGE_MS;
    }

    public static boolean handle(Context context, Intent request) {
        long requestTime = request.getLongExtra(CommonUtils.EXTRA_REQUEST_TIME, 0);
        long now = SystemClock.elapsedRealtime();
        boolean accepted = handle(requestTime, now, task -> new Thread(task, "ie-self-restart").start(), new Steps() {
            @Override public void save() {
                SettingsManager.saveAllFlags();
                SharedPreferences prefs = SettingsManager.prefs();
                if (prefs != null) prefs.edit().commit();
            }

            @Override public boolean ack() {
                Intent ack = new Intent(CommonUtils.ACTION_RESTART_ACK).setPackage(CommonUtils.MY_PACKAGE_NAME);
                IpcSecurity.echoNonce(request, ack);
                return ModuleReplies.reply(request, ack.getExtras());
            }

            @Override public void pause(long ms) {
                SystemClock.sleep(ms);
            }

            @Override public void exit() {
                android.os.Process.killProcess(android.os.Process.myPid());
            }
        });
        if (accepted) {
            ModuleLog.line("(IE|Restart) The InstaEclipse app asked Instagram to restart");
        } else {
            ModuleLog.detail("(IE|Restart) A restart request from " + (now - requestTime) + " ms ago was ignored");
        }
        return accepted;
    }

    static boolean handle(long requestTime, long now, Executor worker, Steps steps) {
        if (!fresh(requestTime, now)) return false;
        worker.execute(() -> run(steps));
        return true;
    }

    private static void run(Steps steps) {
        try {
            steps.save();
        } catch (Throwable t) {
            ModuleLog.warn("(IE|Restart) Settings could not be saved before the restart", t);
        }
        if (!steps.ack()) return;
        steps.pause(EXIT_DELAY_MS);
        steps.exit();
    }
}
