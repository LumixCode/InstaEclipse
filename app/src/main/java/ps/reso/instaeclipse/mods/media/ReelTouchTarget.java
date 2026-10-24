package ps.reso.instaeclipse.mods.media;

import android.app.Activity;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewParent;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

final class ReelTouchTarget {
    static final Pattern TWO_X = Pattern.compile("(?<![\\d.,])2(\\s?[xX×])(?![\\p{L}\\d])");
    private static int containerId = -1;
    private static int layoutId = -1;

    private ReelTouchTarget() { }

    static int containerId(Activity a) {
        if (containerId == -1) containerId = ps.reso.instaeclipse.utils.ui.ResIds.id(a, "clips_video_container");
        return containerId;
    }

    static int layoutId(Activity a) {
        if (layoutId == -1) layoutId = ps.reso.instaeclipse.utils.ui.ResIds.id(a, "clips_viewer_video_layout");
        return layoutId;
    }

    static boolean onReel(Activity a, float x, float y) {
        if (a.getWindow() == null) return false;
        return video(a.getWindow().getDecorView(), x, y, containerId(a), layoutId(a)) != null;
    }

    static View page(Activity a, float x, float y) {
        if (a.getWindow() == null) return null;
        return page(a.getWindow().getDecorView(), x, y, containerId(a), layoutId(a));
    }

    static View hit(View v, float x, float y) {
        if (v.getVisibility() != View.VISIBLE || !contains(v, x, y)) return null;
        if (v instanceof ViewGroup g) {
            for (int i = g.getChildCount() - 1; i >= 0; i--) {
                View h = hit(g.getChildAt(i), x, y);
                if (h != null) return h;
            }
        }
        return v;
    }

    static View video(View root, float x, float y, int containerId, int layoutId) {
        if (root == null || containerId == 0) return null;
        View v = hit(root, x, y);
        while (v != null) {
            if (v.getId() == containerId || (layoutId != 0 && v.getId() == layoutId)) return v;
            ViewParent parent = v.getParent();
            v = parent instanceof View p ? p : null;
        }
        return null;
    }

    static View page(View root, float x, float y, int containerId, int layoutId) {
        View video = video(root, x, y, containerId, layoutId);
        if (video == null) return null;
        View page = video;
        for (ViewParent p = video.getParent(); p instanceof View parent; p = parent.getParent()) {
            if (pager(parent)) return page;
            page = parent;
        }
        return video;
    }

    static CharSequence halfX(CharSequence text) {
        Matcher m = TWO_X.matcher(text);
        if (!m.find()) return text;
        if (!(text instanceof Spanned)) return m.replaceAll("½$1");
        SpannableStringBuilder out = new SpannableStringBuilder(text);
        do out.replace(m.start(), m.start() + 1, "½"); while (m.find());
        return out;
    }

    private static boolean contains(View v, float x, float y) {
        int[] at = new int[2];
        v.getLocationOnScreen(at);
        return x >= at[0] && x < at[0] + v.getWidth() && y >= at[1] && y < at[1] + v.getHeight();
    }

    private static boolean pager(View v) {
        for (Class<?> c = v.getClass(); c != null && c != View.class; c = c.getSuperclass()) {
            String name = c.getSimpleName();
            if (name.contains("RecyclerView") || name.contains("ViewPager")) return true;
        }
        return false;
    }
}
