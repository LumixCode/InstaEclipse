package ps.reso.instaeclipse.mods.ui;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

final class TabOrderPolicy {

    static final String[] ORDERABLE_TABS =
            {"feed_tab", "clips_tab", "search_tab", "creation_tab", "direct_tab", "news_tab", "profile_tab"};

    private boolean reordered;

    static boolean isCustom(String saved) {
        if (saved == null || saved.trim().isEmpty()) return false;
        List<String> order = new ArrayList<>();
        List<String> all = Arrays.asList(ORDERABLE_TABS);
        for (String n : saved.split(",")) {
            String t = n.trim();
            if (all.contains(t) && !order.contains(t)) order.add(t);
        }
        for (String n : ORDERABLE_TABS) if (!order.contains(n)) order.add(n);
        return !order.equals(all);
    }

    boolean sweeps(String order) {
        return !order.isEmpty();
    }

    void reordered(boolean custom) {
        reordered = custom;
    }
}
