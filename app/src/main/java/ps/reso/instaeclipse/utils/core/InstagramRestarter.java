package ps.reso.instaeclipse.utils.core;

import android.app.Activity;
import android.content.Intent;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.widget.Toast;

import java.util.concurrent.TimeUnit;

import ps.reso.instaeclipse.R;
import ps.reso.instaeclipse.utils.toast.IeToast;

public final class InstagramRestarter {

    public interface Callback {
        void onDone(RestartDecision.Outcome outcome);
    }

    private static final long FORCE_STOP_TIMEOUT_S = 15;

    private InstagramRestarter() {}

    public static void restart(Activity activity, String pkg, Callback callback) {
        Handler main = new Handler(Looper.getMainLooper());
        if (pkg == null) {
            finish(activity, null, RestartDecision.decide(null, null), callback, main);
            return;
        }
        new Thread(() -> {
            Integer exit = null;
            try {
                Process p = Runtime.getRuntime().exec(new String[]{"su", "-c", "am force-stop " + pkg});
                if (p.waitFor(FORCE_STOP_TIMEOUT_S, TimeUnit.SECONDS)) {
                    exit = p.exitValue();
                } else {
                    p.destroy();
                }
            } catch (Throwable ignored) { }
            RestartDecision.Outcome outcome = RestartDecision.decide(pkg, exit);
            main.post(() -> finish(activity, pkg, outcome, callback, main));
        }, "ie-restart-ig").start();
    }

    private static void finish(Activity activity, String pkg, RestartDecision.Outcome outcome,
                               Callback callback, Handler main) {
        if (activity.isFinishing() || activity.isDestroyed()) return;
        switch (outcome) {
            case RELAUNCH:
                main.postDelayed(() -> launch(activity, pkg), RestartDecision.RELAUNCH_DELAY_MS);
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
            case MANUAL_TOAST:
                IeToast.makeText(activity, activity.getString(R.string.ig_toast_restart_manual), Toast.LENGTH_LONG).show();
                break;
            default:
                IeToast.makeText(activity, activity.getString(R.string.not_installed_instagram), Toast.LENGTH_SHORT).show();
                break;
        }
        callback.onDone(outcome);
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
