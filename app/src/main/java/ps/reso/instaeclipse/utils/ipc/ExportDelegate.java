package ps.reso.instaeclipse.utils.ipc;

import android.content.Context;
import android.content.Intent;
import android.util.Base64;

import ps.reso.instaeclipse.utils.log.ModuleLog;

public final class ExportDelegate {

    private ExportDelegate() {}

    public static boolean csv(Context context, String saveUri, byte[] bytes, String file, String tag,
                              Runnable writeLocally) {
        Intent request = new Intent();
        request.setClassName("ps.reso.instaeclipse", "ps.reso.instaeclipse.mods.media.DownloadSaveService");
        request.putExtra("chatExportSubdir", "exports");
        request.putExtra("exportMime", "text/csv");
        request.putExtra("htmlFilename", file);
        request.putExtra("htmlB64", Base64.encodeToString(bytes, Base64.NO_WRAP));
        request.putExtra("saveUri", saveUri);
        return delegate(context, request, tag, writeLocally);
    }

    public static boolean delegate(Context context, Intent request, String tag, Runnable writeLocally) {
        try {
            LegacyBrokerPayload.validate(request.getExtras());
        } catch (RuntimeException outsideLimits) {
            return false;
        }
        CompanionBridge.downloadLegacy(context, request, error -> {
            if (error == null) return;
            ModuleLog.warn(tag + "Could not save through the InstaEclipse app (" + error + "), saving directly instead");
            new Thread(writeLocally, "IE-ExportLocal").start();
        });
        return true;
    }
}
