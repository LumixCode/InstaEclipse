package ps.reso.instaeclipse.mods.ui;

import java.util.function.Predicate;

import ps.reso.instaeclipse.utils.core.SettingsSchema;

public final class StartupTabPolicy {

    private StartupTabPolicy() {}

    public static String tabToOpen(String chosen, Predicate<String> on) {
        return chosen == null || hidden(chosen, on) ? "" : chosen;
    }

    public static boolean hidden(String tab, Predicate<String> on) {
        if (tab == null) return false;
        switch (tab) {
            case "FEED":
                return on.test("hideTabHome");
            case "CLIPS":
                return on.test("disableReels") || on.test("hideTabReels");
            case "DIRECT":
                return on.test("hideTabMessages");
            case "NEWS":
                return on.test("hideTabActivity");
            case "PROFILE":
                return on.test("hideTabProfile");
            default:
                return false;
        }
    }

    public static boolean flagOn(String key) {
        return Boolean.TRUE.equals(SettingsSchema.read(key));
    }
}
