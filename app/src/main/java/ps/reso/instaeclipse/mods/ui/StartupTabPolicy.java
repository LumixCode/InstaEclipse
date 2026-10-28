package ps.reso.instaeclipse.mods.ui;

import java.util.function.Predicate;

import ps.reso.instaeclipse.utils.core.SettingsSchema;

public final class StartupTabPolicy {

    private StartupTabPolicy() {}

    public static String tabToOpen(String chosen, Predicate<String> on) {
        return chosen == null ? "" : chosen;
    }

    public static boolean hidden(String tab, Predicate<String> on) {
        return false;
    }

    public static boolean flagOn(String key) {
        return Boolean.TRUE.equals(SettingsSchema.read(key));
    }
}
