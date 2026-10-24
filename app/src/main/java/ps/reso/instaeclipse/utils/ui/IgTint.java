package ps.reso.instaeclipse.utils.ui;

import android.content.Context;
import android.content.res.ColorStateList;
import android.content.res.Configuration;
import android.graphics.ColorFilter;
import android.util.TypedValue;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;

public final class IgTint {

    private IgTint() {}

    public static void copy(View source, ImageView target) {
        ImageView from = source instanceof ImageView iv ? iv : source instanceof ViewGroup g ? firstImage(g) : null;
        if (from != null) {
            ColorStateList list = from.getImageTintList();
            if (list != null) {
                target.setImageTintList(list);
                return;
            }
            ColorFilter filter = from.getColorFilter();
            if (filter == null && from.getDrawable() != null) filter = from.getDrawable().getColorFilter();
            if (filter != null) {
                target.setColorFilter(filter);
                return;
            }
        }
        target.setImageTintList(ColorStateList.valueOf(primaryIcon(target.getContext())));
    }

    public static int primaryIcon(Context context) {
        try {
            int attr = ResIds.attr(context, "igds_color_primary_icon");
            TypedValue v = new TypedValue();
            if (attr != 0 && context.getTheme().resolveAttribute(attr, v, true)) {
                return v.resourceId != 0 ? context.getColor(v.resourceId) : v.data;
            }
        } catch (Throwable ignored) {
        }
        boolean night = (context.getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK)
                == Configuration.UI_MODE_NIGHT_YES;
        return night ? 0xFFF5F5F5 : 0xFF000000;
    }

    private static ImageView firstImage(ViewGroup g) {
        for (int i = 0; i < g.getChildCount(); i++) {
            View c = g.getChildAt(i);
            if (c instanceof ImageView iv) return iv;
            ImageView inner = c instanceof ViewGroup cg ? firstImage(cg) : null;
            if (inner != null) return inner;
        }
        return null;
    }
}
