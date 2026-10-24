package ps.reso.instaeclipse.mods.ui;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

public final class TabOrderPolicy {

    public static final String[] ORDERABLE_TABS =
            {"feed_tab", "clips_tab", "search_tab", "creation_tab", "direct_tab", "news_tab", "profile_tab"};

    private String seen;
    private boolean custom;
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

    boolean custom(String order) {
        if (order != seen) {
            custom = isCustom(order);
            seen = order;
        }
        return custom;
    }

    boolean sweeps(String order) {
        return custom(order) || reordered;
    }

    void reordered(boolean custom) {
        reordered = custom;
    }
}
