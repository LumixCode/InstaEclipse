package ps.reso.instaeclipse.mods.devops.config;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.widget.Toast;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Scanner;

import ps.reso.instaeclipse.R;

public class JsonImportActivity extends Activity {

    private static final int PICK_JSON_FILE = 1234;
    static final String ACTION_IMPORT_CONFIG = "ps.reso.instaeclipse.ACTION_IMPORT_CONFIG";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        if (savedInstanceState != null) return;
        Intent intent = new Intent(Intent.ACTION_GET_CONTENT);
        intent.setType("application/json");
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        startActivityForResult(Intent.createChooser(intent, getString(R.string.json_select_config)), PICK_JSON_FILE);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        if (requestCode == PICK_JSON_FILE) {
            if (resultCode == RESULT_OK && data != null) {
                Uri uri = data.getData();
                try (InputStream inputStream = getContentResolver().openInputStream(uri)) {
                    String json = ps.reso.instaeclipse.utils.ipc.BrokerPayload.readConfig(inputStream);
                    if (json.startsWith("{") && json.endsWith("}")) {
                        String targetPackage = getIntent().getStringExtra("target_package");
                        if (targetPackage == null || targetPackage.isEmpty()) {
                            show(this, ImportMessages.Result.CANT_RECEIVE, false, null);
                        } else {
                            String action = getIntent().getStringExtra("broadcast_action");
                            if (action == null || action.isEmpty()) action = ACTION_IMPORT_CONFIG;
                            // Exported activity: whoever starts it picks these extras, and the
                            // broadcast goes out with our signature permission. Allow only an
                            // Instagram variant and the two import actions.
                            if (!ps.reso.instaeclipse.utils.ipc.CompanionBridge.isTrustedHost(this, targetPackage)
                                    || !(ACTION_IMPORT_CONFIG.equals(action)
                                    || "ps.reso.instaeclipse.ACTION_RESTORE_SETTINGS".equals(action))) {
                                show(this, ImportMessages.Result.CANT_RECEIVE, false, null);
                                finish();
                                return;
                            }
                            Intent broadcast = new Intent(action);
                            broadcast.setPackage(targetPackage);
                            broadcast.putExtra("json_content", json);
                            sendBroadcast(broadcast);
                            show(this, ImportMessages.Result.OK, false, null);
                        }
                    } else {
                        show(this, ImportMessages.Result.NOT_VALID, false, null);
                    }
                } catch (Exception e) {
                    show(this, ImportMessages.ofReadError(e), false, "Cannot read this configuration (maximum 200,000 characters)");
                }
            }
        }
        finish();
    }

    static void show(Context context, ImportMessages.Result result, boolean data, String detail) {
        ps.reso.instaeclipse.utils.toast.IeToast.makeText(context, context.getString(ImportMessages.forResult(result, data), detail),
                result == ImportMessages.Result.OK ? Toast.LENGTH_SHORT : Toast.LENGTH_LONG).show();
    }

    @SuppressLint("NewApi")
    private String readStream(InputStream inputStream) {
        Scanner scanner = new Scanner(inputStream, StandardCharsets.UTF_8).useDelimiter("\\A");
        return scanner.hasNext() ? scanner.next() : "";
    }
}
