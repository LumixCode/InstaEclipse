package ps.reso.instaeclipse.utils.text;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.os.Build;
import android.widget.Toast;

import java.util.function.Consumer;

import ps.reso.instaeclipse.utils.i18n.I18n;
import ps.reso.instaeclipse.utils.toast.IeToast;

/**
 * What a hold-to-copy gesture does once it fires (captions, a profile's username and bio): the
 * text goes on the clipboard, and Android 12 and older get a toast. Android 13+ confirms every
 * copy itself, so a second toast would just repeat it. The hold's haptic tick is the caller's: a
 * View's own long click already plays it.
 */
public final class CopyFeedback {

    private CopyFeedback() {}

    /** Puts {@code text} on the clipboard; {@code toastRes} is the confirmation shown before Android 13. */
    public static void copy(Context ctx, String label, String text, int toastRes) {
        ClipboardManager cm = (ClipboardManager) ctx.getSystemService(Context.CLIPBOARD_SERVICE);
        if (cm == null) return;
        cm.setPrimaryClip(ClipData.newPlainText(label, text));
        if (shouldToast(Build.VERSION.SDK_INT)) {
            IeToast.makeText(ctx, I18n.t(ctx, toastRes), Toast.LENGTH_SHORT).show();
        }
    }

    public static void copy(Context ctx, ClipData clip, CharSequence toastText) {
        ClipboardManager cm = (ClipboardManager) ctx.getSystemService(Context.CLIPBOARD_SERVICE);
        if (cm == null) return;
        copy(cm::setPrimaryClip, clip,
                () -> IeToast.makeText(ctx, toastText, Toast.LENGTH_SHORT).show(), Build.VERSION.SDK_INT);
    }

    static void copy(Consumer<ClipData> clipboard, ClipData clip, Runnable toast, int sdk) {
        clipboard.accept(clip);
        if (shouldToast(sdk)) toast.run();
    }

    public static boolean shouldToast(int sdk) {
        return sdk < 33;
    }
}
