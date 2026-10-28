package ps.reso.instaeclipse.mods.ghost.ui;

import android.app.Activity;
import android.graphics.Color;
import android.graphics.PorterDuff;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewParent;
import android.widget.ImageView;

import ps.reso.instaeclipse.R;
import ps.reso.instaeclipse.utils.i18n.I18n;
import ps.reso.instaeclipse.utils.ui.IePalette;
import ps.reso.instaeclipse.utils.ui.ResIds;

public class GhostEmojiManager {

    public static final int GOLD = 0xFFFFD700;
    public static final double MIN_CONTRAST = 3.0;
    private static final int LABEL_TAG = 0x7e1e02a1;

    private record Label(CharSequence original, String ghost) {}

    public static void addGhostEmojiNextToInbox(Activity activity, boolean showGhost) {
        try {
            View v = activity.findViewById(ResIds.id(activity, "action_bar_inbox_button"));
            if (v == null) v = activity.findViewById(ResIds.id(activity, "direct_tab"));
            if (v == null) return;

            ImageView iv = icon(v, ResIds.id(activity, "tab_icon"));
            if (iv == null) return;

            if (showGhost) {
                iv.setColorFilter(tint(iv), PorterDuff.Mode.SRC_ATOP);
            } else {
                iv.clearColorFilter();
            }
            label(v, showGhost ? I18n.t(activity, R.string.ig_ghost_messages_tab_label) : null);
        } catch (Exception ignored) {
        }
    }

    private static int tint(View icon) {
        return IePalette.readable(GOLD, background(icon), MIN_CONTRAST);
    }

    private static int background(View icon) {
        for (ViewParent p = icon.getParent(); p instanceof View view; p = view.getParent()) {
            Integer color = opaque(view.getBackground());
            if (color != null) return color;
        }
        return IePalette.of(icon.getContext()).backdrop;
    }

    private static Integer opaque(Drawable d) {
        int color;
        if (d instanceof ColorDrawable c) color = c.getColor();
        else if (d instanceof GradientDrawable g && g.getColor() != null) color = g.getColor().getDefaultColor();
        else return null;
        return Color.alpha(color) == 0xFF ? color : null;
    }

    private static void label(View tab, String ghost) {
        CharSequence now = tab.getContentDescription();
        Label saved = tab.getTag(LABEL_TAG) instanceof Label l ? l : null;
        boolean ours = saved != null && now != null && saved.ghost().contentEquals(now);
        if (ghost != null) {
            tab.setTag(LABEL_TAG, new Label(ours ? saved.original() : now, ghost));
            tab.setContentDescription(ghost);
        } else if (saved != null) {
            if (ours) tab.setContentDescription(saved.original());
            tab.setTag(LABEL_TAG, null);
        }
    }

    private static ImageView icon(View tab, int iconId) {
        View icon = iconId != 0 ? tab.findViewById(iconId) : null;
        return icon instanceof ImageView iv ? iv : findImageView(tab);
    }

    private static ImageView findImageView(View view) {
        if (view instanceof ImageView iv) return iv;
        if (view instanceof ViewGroup vg && !view.getClass().getName().contains("Badge")) {
            for (int i = 0; i < vg.getChildCount(); i++) {
                ImageView found = findImageView(vg.getChildAt(i));
                if (found != null) return found;
            }
        }
        return null;
    }
}
