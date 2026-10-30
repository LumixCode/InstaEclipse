package ps.reso.instaeclipse.mods.ghost.ui;

import android.app.Activity;
import android.graphics.Color;
import android.graphics.ColorFilter;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffColorFilter;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewParent;
import android.widget.ImageView;

import java.lang.ref.WeakReference;

import ps.reso.instaeclipse.R;
import ps.reso.instaeclipse.utils.i18n.I18n;
import ps.reso.instaeclipse.utils.log.ModuleLog;
import ps.reso.instaeclipse.utils.ui.IePalette;
import ps.reso.instaeclipse.utils.ui.ResIds;

public class GhostEmojiManager {

    public static final int GOLD = 0xFFFFD700;
    public static final double MIN_CONTRAST = 3.0;
    private static final int LABEL_TAG = 0x7e1e02a1;
    private static final int TINT_TAG = 0x7e1e02a2;
    private static final String[] ICONS = {"action_bar_inbox_button", "direct_tab"};

    private record Label(CharSequence original, String ghost) {}

    private record Tint(PorterDuffColorFilter ours, int color, ColorFilter viewFilter,
                        WeakReference<Drawable> drawable, ColorFilter drawableFilter) {}

    public static void addGhostEmojiNextToInbox(Activity activity, boolean showGhost) {
        try {
            int iconId = ResIds.id(activity, "tab_icon");
            String ghost = showGhost ? I18n.t(activity, R.string.ig_ghost_messages_tab_label) : null;
            for (String name : ICONS) {
                int id = ResIds.id(activity, name);
                View v = id != 0 ? activity.findViewById(id) : null;
                if (v == null) continue;

                ImageView iv = icon(v, iconId);
                if (iv == null) continue;

                paint(name, iv, showGhost);
                label(v, ghost);
            }
        } catch (Exception ignored) {
        }
    }

    private static void paint(String name, ImageView iv, boolean showGhost) {
        Tint saved = iv.getTag(TINT_TAG) instanceof Tint t ? t : null;
        ColorFilter now = iv.getColorFilter();
        boolean ours = saved != null && now == saved.ours();
        Drawable d = iv.getDrawable();
        boolean sameDrawable = ours && saved.drawable().get() == d;

        if (showGhost) {
            int color = tint(iv);
            if (ours && saved.color() == color) return;
            ColorFilter viewFilter = ours ? saved.viewFilter() : now;
            ColorFilter drawableFilter = sameDrawable ? saved.drawableFilter() : ours || d == null ? null : d.getColorFilter();
            PorterDuffColorFilter gold = new PorterDuffColorFilter(color, PorterDuff.Mode.SRC_ATOP);
            iv.setTag(TINT_TAG, new Tint(gold, color, viewFilter, new WeakReference<>(d), drawableFilter));
            iv.setColorFilter(gold);
            ModuleLog.detail("(IE|GhostTint) " + name + " icon tinted gold; Instagram's own icon colour kept: view filter "
                    + kind(viewFilter) + ", drawable " + (d == null ? "none" : d.getClass().getSimpleName())
                    + " filter " + kind(drawableFilter) + ", tint list " + (iv.getImageTintList() != null ? "yes" : "no"));
        } else if (saved != null) {
            iv.setTag(TINT_TAG, null);
            if (!ours) {
                ModuleLog.detail("(IE|GhostTint) " + name + " icon left alone: Instagram set its own colour filter after the ghost tint");
                return;
            }
            ColorFilter live = d == null ? null : d.getColorFilter();
            iv.setColorFilter(saved.viewFilter());
            ColorFilter keep = live != null && live != saved.ours() ? live : sameDrawable ? saved.drawableFilter() : null;
            if (keep != null && d != null) d.setColorFilter(keep);
            ModuleLog.detail("(IE|GhostTint) " + name + " icon back to Instagram's own colour: view filter "
                    + kind(saved.viewFilter()) + ", drawable filter " + kind(keep) + ", tint list "
                    + (iv.getImageTintList() != null ? "yes" : "no"));
        }
    }

    private static String kind(ColorFilter filter) {
        return filter == null ? "none" : filter.getClass().getSimpleName();
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
