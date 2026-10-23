package ps.reso.instaeclipse.utils.core;

import android.content.Context;
import android.content.SharedPreferences;

public final class CompanionUiPrefs {

    public static final String NAME = "instaeclipse_ui";
    public static final String MODULE_CONFIRMED_ONCE = "moduleConfirmedOnce";
    public static final String INSTAGRAM_PACKAGE = "homeInstagramPackage";

    private CompanionUiPrefs() {}

    public static SharedPreferences get(Context context) {
        return context.getSharedPreferences(NAME, Context.MODE_PRIVATE);
    }
}
