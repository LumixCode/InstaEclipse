package ps.reso.instaeclipse.mods.misc;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

final class HideOverlayWalk {
    private static final Set<String> SCROLLING_CONTAINERS = new HashSet<>(Arrays.asList(
            "androidx.recyclerview.widget.RecyclerView",
            "android.widget.AbsListView",
            "androidx.viewpager2.widget.ViewPager2",
            "androidx.viewpager.widget.ViewPager"));

    private HideOverlayWalk() {}

    static boolean stopsAt(Class<?> type) {
        for (Class<?> c = type; c != null; c = c.getSuperclass()) {
            if (SCROLLING_CONTAINERS.contains(c.getName())) return true;
        }
        return false;
    }
}
