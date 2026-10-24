package ps.reso.instaeclipse.mods.ghost;

import android.annotation.SuppressLint;
import android.content.Context;
import android.graphics.Color;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;
import android.widget.Toast;

import ps.reso.instaeclipse.R;
import ps.reso.instaeclipse.utils.feature.FeatureFlags;
import ps.reso.instaeclipse.utils.ghost.HeaderButtonKind;
import ps.reso.instaeclipse.utils.ghost.SeenPause;
import ps.reso.instaeclipse.utils.i18n.I18n;
import ps.reso.instaeclipse.utils.log.ModuleLog;
import ps.reso.instaeclipse.utils.ui.ViewAttachHooks;

public class GhostChannelMarkAsReadHook {

    private static final String CHANNEL_TAG = "ie_channel_seen";

    // Cached once on first use — resource IDs are constant for a given app install.
    private static volatile int sCachedSeenStateId = 0;
    private static volatile int sCachedHeaderButtonsId = 0;

    public void install(ClassLoader classLoader) {
        try {
            ViewAttachHooks.add("GhostChannel", view -> {
                if (!FeatureFlags.isGhostSeen) return;
                Context context = view.getContext();
                sCachedSeenStateId = ps.reso.instaeclipse.utils.ui.ResIds.id(context, "seen_state_text");
                if (sCachedSeenStateId == 0 || view.getId() != sCachedSeenStateId) return;
                if (!(view instanceof TextView seenTextView)) return;
                sCachedHeaderButtonsId = ps.reso.instaeclipse.utils.ui.ResIds.id(context, "header_right_buttons");
                View container = sCachedHeaderButtonsId == 0 ? null
                        : view.getRootView().findViewById(sCachedHeaderButtonsId);
                if (!(container instanceof ViewGroup header) || HeaderButtonKind.classify(
                        HeaderButtonKind.kinds(header)) != HeaderButtonKind.Header.CHANNEL) return;
                updateChannelSeen(seenTextView);
            });
        } catch (Throwable t) {
            ModuleLog.error("(InstaEclipse): Could not add the mark-as-read button to channels", t);
        }
    }

    private void updateChannelSeen(TextView textView) {
        // Prevent multiple listeners/updates
        if (textView.getTag() != null && textView.getTag().equals(CHANNEL_TAG)) return;
        textView.setTag(CHANNEL_TAG);

        // Make it look interactive
        textView.setTextColor(Color.CYAN); // Distinguish it as a "modded" element

        textView.setOnClickListener(v -> {
            triggerChannelSeen(textView);
        });

        // Optional: Append a ghost emoji to indicate it's modded
        String currentText = textView.getText().toString();
        if (!currentText.contains("👻")) {
            textView.setText(currentText + " 👻");
        }
    }

    private void triggerChannelSeen(View view) {
        try {
            Context ctx = view.getContext();
            @SuppressLint("DiscouragedApi")
            int messageListId = ctx.getResources().getIdentifier("message_list", "id", ctx.getPackageName());

            View root = view.getRootView();
            View messageList = root.findViewById(messageListId);

            if (messageList instanceof ViewGroup group) {
                group.scrollBy(0, 100_000);

                SeenPause.pause(400);
                group.scrollBy(0, -300);

                view.postDelayed(() -> {
                    group.scrollBy(0, 300);
                    ps.reso.instaeclipse.utils.toast.IeToast.makeText(ctx, I18n.t(ctx, R.string.ig_toast_channel_marked_as_seen), Toast.LENGTH_SHORT).show();
                }, 400);
            }
        } catch (Exception ignored) {
        }
    }
}