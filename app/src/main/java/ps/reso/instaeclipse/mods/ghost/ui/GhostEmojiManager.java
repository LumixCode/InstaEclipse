package ps.reso.instaeclipse.mods.ghost.ui;

import android.app.Activity;
import android.graphics.Color;
import android.graphics.PorterDuff;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;

import ps.reso.instaeclipse.utils.ui.ResIds;

public class GhostEmojiManager {

    public static void addGhostEmojiNextToInbox(Activity activity, boolean showGhost) {
        try {
            View v = activity.findViewById(ResIds.id(activity, "action_bar_inbox_button"));
            if (v == null) v = activity.findViewById(ResIds.id(activity, "direct_tab"));
            if (v == null) return;

            ImageView iv = findImageView(v);
            if (iv == null) return;

            if (showGhost) {
                iv.setColorFilter(Color.parseColor("#FFD700"), PorterDuff.Mode.SRC_ATOP);
            } else {
                iv.clearColorFilter();
            }
        } catch (Exception ignored) {
        }
    }

    /** Recursively finds the first ImageView in the view tree. */
    private static ImageView findImageView(View view) {
        if (view instanceof ImageView iv) return iv;
        if (view instanceof ViewGroup vg) {
            for (int i = 0; i < vg.getChildCount(); i++) {
                ImageView found = findImageView(vg.getChildAt(i));
                if (found != null) return found;
            }
        }
        return null;
    }
}
