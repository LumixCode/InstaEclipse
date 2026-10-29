package ps.reso.instaeclipse.mods.devops.config;

import android.app.Activity;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;

import java.io.InputStream;
import java.util.List;
import java.util.UUID;

import ps.reso.instaeclipse.mods.media.DataBackup;

public class DataImportActivity extends Activity {

    private static final int PICK = 4321;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        if (savedInstanceState != null) return;
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("*/*");
        intent.putExtra(Intent.EXTRA_MIME_TYPES, new String[]{"application/zip", "application/x-zip-compressed", "application/octet-stream"});
        intent.putExtra(android.provider.DocumentsContract.EXTRA_INITIAL_URI, android.provider.DocumentsContract.buildDocumentUri(
                "com.android.externalstorage.documents", "primary:Download/InstaEclipse"));
        startActivityForResult(intent, PICK);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        if (requestCode == PICK && resultCode == RESULT_OK && data != null && data.getData() != null) {
            restore(data.getData());
        }
        finish();
    }

    private void restore(Uri uri) {
        String target = getIntent().getStringExtra("target_package");
        if (!ps.reso.instaeclipse.utils.ipc.BrokerPayload.HOST.equals(target)) {
            JsonImportActivity.show(this, ImportMessages.Result.CANT_RECEIVE, true, null);
            return;
        }
        if (!ps.reso.instaeclipse.utils.ipc.CompanionBridge.isTrustedHost(this, target)) {
            JsonImportActivity.show(this, ImportMessages.Result.NO_REPLY, true, null);
            return;
        }
        List<String> pieces;
        try (InputStream in = getContentResolver().openInputStream(uri)) {
            pieces = DataBackup.pieces(in);
        } catch (Exception e) {
            JsonImportActivity.show(this, ImportMessages.ofReadError(e), true, null);
            return;
        }
        if (pieces.isEmpty()) {
            JsonImportActivity.show(this, ImportMessages.Result.NOT_VALID, true, null);
            return;
        }
        String id = UUID.randomUUID().toString();
        for (int i = 0; i < pieces.size(); i++) {
            Intent b = new Intent(DataBackup.ACTION_RESTORE);
            b.setPackage(target);
            b.putExtra("id", id);
            b.putExtra("index", i);
            b.putExtra("total", pieces.size());
            b.putExtra("piece", pieces.get(i));
            if (i < pieces.size() - 1) sendBroadcast(b);
            else JsonImportActivity.sendToInstagram(this, b, true);
        }
    }
}
