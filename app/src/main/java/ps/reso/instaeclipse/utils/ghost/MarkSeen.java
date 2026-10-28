package ps.reso.instaeclipse.utils.ghost;

import android.content.Context;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Toast;

import ps.reso.instaeclipse.utils.i18n.I18n;
import ps.reso.instaeclipse.utils.toast.IeToast;

public final class MarkSeen {

    private MarkSeen() {}

    public static void scroll(View anchor, View messageList, long settleMs, int nudgePx, int toast) {
        try {
            if (!(messageList instanceof ViewGroup group)) return;
            Context ctx = anchor.getContext();
            group.scrollBy(0, 100_000);
            SeenPause.pause(settleMs);
            group.scrollBy(0, -nudgePx);
            anchor.postDelayed(() -> {
                group.scrollBy(0, nudgePx);
                IeToast.makeText(ctx, I18n.t(ctx, toast), Toast.LENGTH_SHORT).show();
            }, settleMs);
        } catch (Exception ignored) {
        }
    }
}
