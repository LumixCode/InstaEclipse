package ps.reso.instaeclipse.mods.ghost;

import android.content.Context;
import android.graphics.drawable.Drawable;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageButton;

import ps.reso.instaeclipse.R;
import ps.reso.instaeclipse.utils.feature.FeatureFlags;
import ps.reso.instaeclipse.utils.ghost.MarkSeen;
import ps.reso.instaeclipse.utils.i18n.I18n;
import ps.reso.instaeclipse.utils.log.ModuleLog;
import ps.reso.instaeclipse.utils.ui.IgTint;
import ps.reso.instaeclipse.utils.ui.ResIds;
import ps.reso.instaeclipse.utils.ui.ViewAttachHooks;

public class GhostDMMarkAsReadHook {

    static final String GHOST_BTN_TAG = "ie_ghost_seen_btn";

    private static volatile int sCachedContainerId = 0;

    public void install(ClassLoader classLoader) {
        try {
            ViewAttachHooks.add("GhostDM", view -> {
                if (!FeatureFlags.isGhostSeen) return;
                sCachedContainerId = ps.reso.instaeclipse.utils.ui.ResIds.id(view.getContext(), "row_thread_composer_buttons_container");
                if (sCachedContainerId == 0 || view.getId() != sCachedContainerId) return;
                if (!(view.getParent() instanceof ViewGroup parent)) return;
                inject(parent, view);
            });
        } catch (Throwable t) {
            ModuleLog.error("(InstaEclipse): Could not add the mark-as-read button to chats", t);
        }
    }

    static void inject(ViewGroup parent, View container) {
        if (parent.findViewWithTag(GHOST_BTN_TAG) != null) return;

        Context ctx = parent.getContext();
        ImageButton ghostBtn = new ImageButton(ctx);
        ghostBtn.setTag(GHOST_BTN_TAG);

        Drawable icon = SeenAffordance.eye(ctx);
        if (icon != null) {
            ghostBtn.setImageDrawable(icon);
        } else {
            ghostBtn.setImageResource(android.R.drawable.ic_menu_view);
        }
        IgTint.copy(container, ghostBtn);
        ghostBtn.setBackground(null);
        ghostBtn.setContentDescription(I18n.t(ctx, R.string.ig_story_mark_seen));

        int size = dp(ctx, 35);

        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(size, size);

        lp.gravity = Gravity.CENTER_VERTICAL | Gravity.START;
        lp.setMargins(dp(ctx, 5), dp(ctx, 8), 0, 0);

        ghostBtn.setLayoutParams(lp);
        ghostBtn.setOnClickListener(v -> MarkSeen.scroll(parent, messageList(parent), 300, 200,
                R.string.ig_toast_marked_as_seen));

        parent.post(() -> {
            try {
                if (parent.findViewWithTag(GHOST_BTN_TAG) != null) return;
                parent.addView(ghostBtn, SeenAffordance.composerIndex(parent.getChildCount()));
            } catch (Throwable t) {
                ModuleLog.detail("(IE|MarkAsRead) The eye button could not be added to the chat: " + t);
            }
        });
    }

    static View messageList(View view) {
        return ThreadHeaderMenu.findShown(view.getRootView(), ResIds.id(view.getContext(), "message_list"));
    }

    private static int dp(Context ctx, int v) {
        return (int) (v * ctx.getResources().getDisplayMetrics().density);
    }
}
