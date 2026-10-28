package ps.reso.instaeclipse.mods.ghost;

import android.content.Context;
import android.graphics.drawable.Drawable;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import ps.reso.instaeclipse.R;
import ps.reso.instaeclipse.utils.feature.FeatureFlags;
import ps.reso.instaeclipse.utils.ghost.HeaderButtonKind;
import ps.reso.instaeclipse.utils.ghost.MarkSeen;
import ps.reso.instaeclipse.utils.log.ModuleLog;
import ps.reso.instaeclipse.utils.ui.ResIds;
import ps.reso.instaeclipse.utils.ui.ViewAttachHooks;

public class GhostChannelMarkAsReadHook {

    private static final int EYE_TAG = 0x7e1e0224;

    private record Eye(Drawable icon, Drawable start, int padding, boolean clickable) {}

    public void install(ClassLoader classLoader) {
        try {
            ViewAttachHooks.add("GhostChannel", view -> {
                if (!FeatureFlags.isGhostSeen) {
                    if (view instanceof TextView text) bind(text, false);
                    return;
                }
                Context context = view.getContext();
                int seenId = ResIds.id(context, "seen_state_text");
                if (seenId == 0 || view.getId() != seenId) return;
                if (!(view instanceof TextView seenTextView)) return;
                bind(seenTextView, inChannel(seenTextView));
            });
        } catch (Throwable t) {
            ModuleLog.error("(InstaEclipse): Could not add the mark-as-read button to channels", t);
        }
    }

    private static boolean inChannel(TextView seen) {
        int headerButtonsId = ResIds.id(seen.getContext(), "header_right_buttons");
        View container = headerButtonsId == 0 ? null : seen.getRootView().findViewById(headerButtonsId);
        return container instanceof ViewGroup header && HeaderButtonKind.classify(
                HeaderButtonKind.kinds(header)) == HeaderButtonKind.Header.CHANNEL;
    }

    private static void bind(TextView seen, boolean show) {
        Eye eye = seen.getTag(EYE_TAG) instanceof Eye e ? e : null;
        switch (SeenAffordance.onBind(show, eye != null)) {
            case APPLY -> apply(seen, eye);
            case REMOVE -> remove(seen, eye);
            default -> { }
        }
    }

    private static void apply(TextView seen, Eye eye) {
        Drawable[] d = seen.getCompoundDrawablesRelative();
        if (eye == null) {
            Drawable icon = SeenAffordance.eye(seen.getContext());
            if (icon == null) return;
            eye = new Eye(icon, d[0], seen.getCompoundDrawablePadding(), seen.isClickable());
            seen.setTag(EYE_TAG, eye);
        }
        int size = seen.getLineHeight();
        eye.icon().setBounds(0, 0, size, size);
        eye.icon().setTintList(seen.getTextColors());
        if (d[0] != eye.icon()) {
            seen.setCompoundDrawablesRelative(eye.icon(), d[1], d[2], d[3]);
            seen.setCompoundDrawablePadding((int) (4 * seen.getResources().getDisplayMetrics().density));
        }
        seen.setOnClickListener(v -> MarkSeen.scroll(seen, GhostDMMarkAsReadHook.messageList(seen), 400, 300,
                R.string.ig_toast_channel_marked_as_seen));
    }

    private static void remove(TextView seen, Eye eye) {
        Drawable[] d = seen.getCompoundDrawablesRelative();
        if (d[0] == eye.icon()) {
            seen.setCompoundDrawablesRelative(eye.start(), d[1], d[2], d[3]);
            seen.setCompoundDrawablePadding(eye.padding());
        }
        seen.setOnClickListener(null);
        seen.setClickable(eye.clickable());
        seen.setTag(EYE_TAG, null);
    }
}
