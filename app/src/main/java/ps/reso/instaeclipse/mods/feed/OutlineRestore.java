package ps.reso.instaeclipse.mods.feed;

import android.graphics.Outline;
import android.view.View;
import android.view.ViewOutlineProvider;

final class OutlineRestore {

    static final int RADIUS_TAG = 0x7e1e0276;

    private OutlineRestore() {}

    static void update(View frame, View target, float radius) {
        Object applied = target.getTag(RADIUS_TAG);
        if (radius <= 0) {
            if (applied == null) return;
            target.setTag(RADIUS_TAG, null);
            target.setClipToOutline(false);
            target.setOutlineProvider(ViewOutlineProvider.BACKGROUND);
            return;
        }
        if (applied instanceof Float f && f == radius && target.getClipToOutline()) return;
        target.setTag(RADIUS_TAG, radius);
        target.setOutlineProvider(new ViewOutlineProvider() {
            @Override
            public void getOutline(View view, Outline outline) {
                outline.setRoundRect(0, 0, view.getWidth(), view.getHeight(), radius);
            }
        });
        target.setClipToOutline(true);
        target.invalidateOutline();
    }
}
