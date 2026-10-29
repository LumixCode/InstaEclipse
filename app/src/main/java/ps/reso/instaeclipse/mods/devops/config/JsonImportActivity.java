package ps.reso.instaeclipse.mods.devops.config;

import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.widget.Toast;

import java.io.InputStream;

import ps.reso.instaeclipse.R;
import ps.reso.instaeclipse.utils.core.IpcSecurity;
import ps.reso.instaeclipse.utils.ipc.BrokerPayload;
import ps.reso.instaeclipse.utils.ipc.ModuleReplies;

public class JsonImportActivity extends Activity {

    private static final int PICK_JSON_FILE = 1234;
    static final String ACTION_IMPORT_CONFIG = "ps.reso.instaeclipse.ACTION_IMPORT_CONFIG";
    private static final String ACTION_RESTORE_SETTINGS = "ps.reso.instaeclipse.ACTION_RESTORE_SETTINGS";
    private static final String ACTION_IMPORT_RESULT = "ps.reso.instaeclipse.ACTION_IMPORT_RESULT";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        if (savedInstanceState != null) return;
        Intent intent = new Intent(Intent.ACTION_GET_CONTENT);
        intent.setType("*/*");
        intent.putExtra(Intent.EXTRA_MIME_TYPES, new String[]{"application/json", "text/plain", "application/octet-stream"});
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        startActivityForResult(Intent.createChooser(intent, getString(R.string.json_select_config)), PICK_JSON_FILE);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        if (requestCode == PICK_JSON_FILE && resultCode == RESULT_OK && data != null && data.getData() != null) {
            importFile(data.getData());
        }
        finish();
    }

    private void importFile(Uri uri) {
        String json;
        try (InputStream inputStream = getContentResolver().openInputStream(uri)) {
            json = BrokerPayload.readConfig(inputStream);
        } catch (Exception e) {
            show(this, ImportMessages.ofReadError(e), false, e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName());
            return;
        }
        String targetPackage = getIntent().getStringExtra("target_package");
        String action = getIntent().getStringExtra("broadcast_action");
        if (action == null || action.isEmpty()) action = ACTION_IMPORT_CONFIG;
        if (!BrokerPayload.HOST.equals(targetPackage)
                || !(ACTION_IMPORT_CONFIG.equals(action) || ACTION_RESTORE_SETTINGS.equals(action))) {
            show(this, ImportMessages.Result.CANT_RECEIVE, false, null);
            return;
        }
        Intent broadcast = new Intent(action);
        broadcast.setPackage(targetPackage);
        broadcast.putExtra("json_content", json);
        sendToInstagram(this, broadcast, false);
    }

    static void sendToInstagram(Context context, Intent request, boolean data) {
        new Confirmation(context.getApplicationContext(), data).send(request);
    }

    static void show(Context context, ImportMessages.Result result, boolean data, String detail) {
        ImportMessages.report(result);
        ps.reso.instaeclipse.utils.toast.IeToast.makeText(context, context.getString(ImportMessages.forResult(result, data), detail),
                result == ImportMessages.Result.OK ? Toast.LENGTH_SHORT : Toast.LENGTH_LONG).show();
    }

    private static final class Confirmation extends BroadcastReceiver implements Runnable {
        private final Context app;
        private final boolean data;
        private final String nonce = IpcSecurity.newNonce();
        private final Handler main = new Handler(Looper.getMainLooper());
        private ModuleReplies.Pending pending;
        private boolean answered;

        Confirmation(Context app, boolean data) {
            this.app = app;
            this.data = data;
        }

        void send(Intent request) {
            request.putExtra(IpcSecurity.EXTRA_NONCE, nonce);
            pending = IpcSecurity.requestReply(app, request, ACTION_IMPORT_RESULT, this);
            if (pending == null) {
                answer(ImportMessages.Result.NO_REPLY);
                return;
            }
            ps.reso.instaeclipse.utils.toast.IeToast.makeText(app, app.getString(R.string.import_sending), Toast.LENGTH_SHORT).show();
            main.postDelayed(this, ImportMessages.TIMEOUT_MS);
        }

        @Override
        public void onReceive(Context context, Intent reply) {
            if (IpcSecurity.nonceMatches(nonce, reply)) answer(ImportMessages.ofReply(reply.getStringExtra("error")));
        }

        @Override
        public void run() {
            if (pending != null) pending.cancel();
            answer(ImportMessages.Result.NO_REPLY);
        }

        private void answer(ImportMessages.Result result) {
            if (answered) return;
            answered = true;
            main.removeCallbacks(this);
            show(app, result, data, null);
        }
    }
}
