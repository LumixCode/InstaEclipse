package ps.reso.instaeclipse.mods.ghost;

import android.annotation.SuppressLint;
import android.content.Context;
import android.graphics.drawable.Drawable;

import ps.reso.instaeclipse.R;
import ps.reso.instaeclipse.utils.core.ModuleResources;
import ps.reso.instaeclipse.utils.ui.ResIds;

final class SeenAffordance {

    enum Action { NONE, APPLY, REMOVE }

    private SeenAffordance() {}

    static Action onBind(boolean show, boolean applied) {
        if (show) return Action.APPLY;
        return applied ? Action.REMOVE : Action.NONE;
    }

    static int composerIndex(int childCount) {
        return Math.min(3, childCount);
    }

    @SuppressLint("UseCompatLoadingForDrawables")
    static Drawable eye(Context ctx) {
        int id = ResIds.drawable(ctx, "instagram_eye_pano_outline_24");
        if (id != 0) {
            try {
                Drawable d = ctx.getDrawable(id);
                if (d != null) return d.mutate();
            } catch (Throwable ignored) {
            }
        }
        return ModuleResources.icon(ctx, R.drawable.ic_eye);
    }
}
