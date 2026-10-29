package ps.reso.instaeclipse.utils.text;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.os.Build;
import android.widget.Toast;

import java.util.function.Consumer;

import ps.reso.instaeclipse.utils.i18n.I18n;
import ps.reso.instaeclipse.utils.toast.IeToast;

public final class CopyFeedback {

    private CopyFeedback() {}

    public static void copy(Context ctx, String label, String text, int toastRes) {
        ClipboardManager cm = (ClipboardManager) ctx.getSystemService(Context.CLIPBOARD_SERVICE);
        if (cm == null) return;
        cm.setPrimaryClip(ClipData.newPlainText(label, text));
        if (shouldToast(Build.VERSION.SDK_INT)) {
            IeToast.makeText(ctx, I18n.t(ctx, toastRes), Toast.LENGTH_SHORT).show();
        }
    }

    public static void copy(Context ctx, String label, String text, CharSequence toastText) {
        copy(ctx, ClipData.newPlainText(label, text), toastText);
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
