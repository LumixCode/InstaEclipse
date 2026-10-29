package ps.reso.instaeclipse.utils.ipc;

import android.content.Context;
import android.content.Intent;

import ps.reso.instaeclipse.utils.log.ModuleLog;

public final class ExportDelegate {

    private ExportDelegate() {}

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
