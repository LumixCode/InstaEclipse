package ps.reso.instaeclipse.utils.dialog;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.IntFunction;
import java.util.function.Predicate;

import ps.reso.instaeclipse.utils.core.ExtremeRules;
import ps.reso.instaeclipse.utils.feature.FeatureCatalog;
import ps.reso.instaeclipse.utils.text.SearchText;

public final class MenuSearch {

    public enum Action { TOGGLE, OPEN }

    public static final class Group {
        public final FeatureCatalog.Category category;
        public final List<FeatureCatalog.Item> items;

        Group(FeatureCatalog.Category category, List<FeatureCatalog.Item> items) {
            this.category = category;
            this.items = Collections.unmodifiableList(items);
        }
    }

    public static final Set<String> SHOWN_IN_INSTAGRAM = set("downloadFolder", "profilePhoto");
    public static final Set<String> OPEN_ON_PAGE = set("isExtremeMode", "customThemeEnabled");
    public static final Set<String> LIVE_TEXT = set("reelUsage", "nightReelStatus", "autoClearCacheSizeMb");
    private static final Set<String> OFF_WITHOUT_REELS = set("dailyReelLimitEnabled", "dailyReelLimitAllowDM",
            "nightReelBlockEnabled", "nightReelBlockAllowDM");

    private MenuSearch() {}

    public static List<Group> find(String query, IntFunction<String> text) {
        List<Group> out = new ArrayList<>();
        if (SearchText.fold(query == null ? "" : query.trim()).isEmpty()) return out;
        for (FeatureCatalog.Category category : FeatureCatalog.CATEGORIES) {
            List<FeatureCatalog.Item> hits = new ArrayList<>();
            for (FeatureCatalog.Item item : category.items()) {
                if (shown(item) && FeatureCatalog.matches(item, category, query, text)) hits.add(item);
            }
            if (!hits.isEmpty()) out.add(new Group(category, hits));
        }
        return out;
    }

    public static List<String> keys(List<Group> groups) {
        List<String> out = new ArrayList<>();
        for (Group group : groups) for (FeatureCatalog.Item item : group.items) out.add(item.key);
        return out;
    }

    public static boolean shown(FeatureCatalog.Item item) {
        if (item.kind == FeatureCatalog.Kind.MASTER) return false;
        return item.side != FeatureCatalog.Side.COMPANION_ONLY || SHOWN_IN_INSTAGRAM.contains(item.key);
    }

    public static Action action(FeatureCatalog.Item item) {
        return item.kind == FeatureCatalog.Kind.SWITCH && !OPEN_ON_PAGE.contains(item.key) ? Action.TOGGLE : Action.OPEN;
    }

    public static boolean requiresMet(FeatureCatalog.Item item, Predicate<String> on) {
        if (item.requires.length == 0) return true;
        for (String key : item.requires) if (on.test(key)) return true;
        return false;
    }

    public static boolean editable(FeatureCatalog.Item item, Predicate<String> on, boolean extreme) {
        if (!requiresMet(item, on)) return false;
        if (OFF_WITHOUT_REELS.contains(item.key) && on.test("disableReels")) return false;
        return !extreme || ExtremeRules.canToggle(item.key, on.test(item.key));
    }

    public static Map<String, Boolean> writes(String key, boolean on, boolean extreme) {
        Map<String, Boolean> out = new LinkedHashMap<>();
        out.put(key, on);
        if ("disableReels".equals(key) && !on) out.put("disableReelsExceptDM", false);
        String exception = on && extreme ? ExtremeRules.exceptionOf(key) : null;
        if (exception != null) out.put(exception, false);
        return out;
    }

    private static Set<String> set(String... keys) {
        return Collections.unmodifiableSet(new HashSet<>(Arrays.asList(keys)));
    }
}
