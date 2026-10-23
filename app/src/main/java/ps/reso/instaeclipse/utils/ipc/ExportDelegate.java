package ps.reso.instaeclipse.utils.ipc;

import android.content.Context;
import android.content.Intent;

import ps.reso.instaeclipse.utils.log.ModuleLog;

/**
 * Hands an HTML export (chat or comments, with its media) to the companion's save service, which
 * writes into the user's SAF folder; otherwise the caller writes it inside Instagram. Local
 * helper, so upstream's CompanionBridge stays as it is.
 */
public final class ExportDelegate {

    private ExportDelegate() {}

    /**
     * Sends {@code request} to the companion and returns true, or returns false when the broker
     * would refuse it (HTML over 240k base64 chars, more than 500 media, a media host outside its
     * policy): the broker answers only asynchronously, so that is checked first and the caller
     * writes the export itself (and logs why). When the companion fails a sent export,
     * {@code writeLocally} runs on a background thread (it downloads media). {@code tag} prefixes
     * the log line.
     */
    public static boolean delegate(Context context, Intent request, String tag, Runnable writeLocally) {
        try {
            LegacyBrokerPayload.validate(request.getExtras());
        } catch (RuntimeException outsideLimits) {
            return false;
        }
        CompanionBridge.downloadLegacy(context, request, error -> {
            if (error == null) return;
            // The broker answers on the main thread; the local write downloads media.
            ModuleLog.warn(tag + "Could not save through the InstaEclipse app (" + error + "), saving directly instead");
            new Thread(writeLocally, "IE-ExportLocal").start();
        });
        return true;
    }
}
