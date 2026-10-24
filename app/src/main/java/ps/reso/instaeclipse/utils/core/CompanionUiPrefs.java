package ps.reso.instaeclipse.utils.core;

import android.content.Context;
import android.content.SharedPreferences;

import java.util.Map;

public final class CompanionUiPrefs {

    public static final String NAME = "instaeclipse_ui";
    public static final String MODULE_CONFIRMED_ONCE = "moduleConfirmedOnce";
    public static final String INSTAGRAM_PACKAGE = "homeInstagramPackage";
    public static final String LAST_UPDATE_CHECK = "lastUpdateCheck";
    public static final String SKIPPED_VERSION = "skippedVersion";
    public static final String HOME_PINNED_SHORTCUTS = "homePinnedShortcuts";
    public static final String BACKGROUND_ACCESS_ASKED = "backgroundAccessAsked";

    private CompanionUiPrefs() {}

    public static SharedPreferences get(Context context) {
        return context.getSharedPreferences(NAME, Context.MODE_PRIVATE);
    }

    public static void migrate(Context context) {
        try {
            Map<String, ?> cache = CommonUtils.companionCache(context).getAll();
            Object pinned = cache.get(HOME_PINNED_SHORTCUTS);
            Object asked = cache.get(BACKGROUND_ACCESS_ASKED);
            if (!(pinned instanceof String) && !(asked instanceof Boolean)) return;
            SharedPreferences ui = get(context);
            SharedPreferences.Editor editor = ui.edit();
            if (pinned instanceof String && !ui.contains(HOME_PINNED_SHORTCUTS)) {
                editor.putString(HOME_PINNED_SHORTCUTS, (String) pinned);
            }
            if (asked instanceof Boolean && !ui.contains(BACKGROUND_ACCESS_ASKED)) {
                editor.putBoolean(BACKGROUND_ACCESS_ASKED, (Boolean) asked);
            }
            editor.apply();
        } catch (Throwable ignored) {}
    }
}
