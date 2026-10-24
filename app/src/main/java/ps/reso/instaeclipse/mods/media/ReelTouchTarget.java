package ps.reso.instaeclipse.mods.media;

import android.app.Activity;
import android.view.View;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

final class ReelTouchTarget {
    static final Pattern TWO_X = Pattern.compile("(?<![\\d.,])2(\\s?[xX×])(?![\\p{L}\\d])");
    private static int containerId = -1;

    private ReelTouchTarget() { }

    static int containerId(Activity a) {
        if (containerId == -1) containerId = ps.reso.instaeclipse.utils.ui.ResIds.id(a, "clips_video_container");
        return containerId;
    }

    static boolean onReel(Activity a, float x, float y) {
        if (a.getWindow() == null) return false;
        return onReel(a.getWindow().getDecorView(), x, y, containerId(a));
    }

    static boolean onReel(View root, float x, float y, int id) {
        if (root == null || id == 0) return false;
        View container = root.findViewById(id);
        return container != null && container.isShown();
    }

    static View page(View root, float x, float y, int id) {
        return root;
    }

    static CharSequence halfX(CharSequence text) {
        Matcher m = TWO_X.matcher(text);
        return m.find() ? m.replaceAll("½$1") : text;
    }
}
