package ps.reso.instaeclipse.utils.core;

import android.content.Context;
import android.content.SharedPreferences;

public final class CompanionUiPrefs {

    public static final String NAME = "instaeclipse_ui";
    public static final String MODULE_CONFIRMED_ONCE = "moduleConfirmedOnce";
    public static final String INSTAGRAM_PACKAGE = "homeInstagramPackage";
    public static final String LAST_UPDATE_CHECK = "lastUpdateCheck";
    public static final String SKIPPED_VERSION = "skippedVersion";

    private CompanionUiPrefs() {}

    public static SharedPreferences get(Context context) {
        return context.getSharedPreferences(NAME, Context.MODE_PRIVATE);
    }
}
