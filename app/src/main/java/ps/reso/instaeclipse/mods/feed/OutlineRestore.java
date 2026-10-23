package ps.reso.instaeclipse.mods.feed;

import android.graphics.Outline;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewOutlineProvider;

final class OutlineRestore {

    static final int RADIUS_TAG = 0x7e1e0276;
    static final int TARGET_TAG = 0x7e1e0279;

    private static final class Saved {
        final ViewOutlineProvider provider;
        final boolean clipToOutline;
        final boolean clipChildren;
        float radius;

        Saved(View v) {
            provider = v.getOutlineProvider();
            clipToOutline = v.getClipToOutline();
            clipChildren = !(v instanceof ViewGroup g) || g.getClipChildren();
        }
    }

    private OutlineRestore() {}

    static boolean applied(View frame) {
        return frame.getTag(TARGET_TAG) != null;
    }

    static void update(View frame, View target, float radius) {
        if (frame.getTag(TARGET_TAG) instanceof View old && (old != target || radius <= 0)) {
            restore(old);
            frame.setTag(TARGET_TAG, null);
        }
        if (radius <= 0) return;
        round(target, radius);
        frame.setTag(TARGET_TAG, target);
    }

    private static void round(View v, float radius) {
        Saved saved;
        if (v.getTag(RADIUS_TAG) instanceof Saved s) {
            if (s.radius == radius && v.getClipToOutline()) return;
            saved = s;
        } else {
            saved = new Saved(v);
            v.setTag(RADIUS_TAG, saved);
        }
        saved.radius = radius;
        v.setOutlineProvider(new ViewOutlineProvider() {
            @Override
            public void getOutline(View view, Outline outline) {
                outline.setRoundRect(0, 0, view.getWidth(), view.getHeight(), radius);
            }
        });
        v.setClipToOutline(true);
        v.invalidateOutline();
    }

    private static void restore(View v) {
        if (!(v.getTag(RADIUS_TAG) instanceof Saved s)) return;
        v.setTag(RADIUS_TAG, null);
        v.setOutlineProvider(s.provider);
        v.setClipToOutline(s.clipToOutline);
        if (v instanceof ViewGroup g) g.setClipChildren(s.clipChildren);
        v.invalidateOutline();
    }
}
