package ps.reso.instaeclipse.ui;

import android.app.Activity;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;

import ps.reso.instaeclipse.utils.core.InstagramRestarter;
import ps.reso.instaeclipse.utils.ipc.BrokerPayload;
import ps.reso.instaeclipse.utils.log.ModuleLog;

public class RestartInstagramActivity extends Activity {

    private static final long GIVE_UP_MS = 30_000;

    private final Handler main = new Handler(Looper.getMainLooper());

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        if (savedInstanceState != null) {
            finish();
            return;
        }
        main.postDelayed(this::finish, GIVE_UP_MS);
        try {
            ModuleLog.line("(IE|Restart) Instagram opened the InstaEclipse app to restart it");
            InstagramRestarter.restart(this, BrokerPayload.HOST, outcome -> {
                ModuleLog.detail("(IE|Restart) The restart asked for by Instagram ended with " + outcome);
                finish();
            });
        } catch (Throwable t) {
            ModuleLog.warn("(IE|Restart) The InstaEclipse app could not restart Instagram", t);
            finish();
        }
    }

    @Override
    protected void onDestroy() {
        main.removeCallbacksAndMessages(null);
        super.onDestroy();
    }
}
