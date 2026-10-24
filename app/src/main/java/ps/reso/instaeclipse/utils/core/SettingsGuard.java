package ps.reso.instaeclipse.utils.core;

import android.content.Context;

import java.util.Map;

public final class SettingsGuard {

    public enum Decision { APPLY, AUTHENTICATE, SET_PASSCODE, REFUSE }

    public static final String HIDE_PICKER = "hideChooseChats";

    private SettingsGuard() {}

    public static Decision decide(String key, Object value, boolean passcodeSet) {
        return Decision.APPLY;
    }

    public static Decision decideInApp(String key, Object value, Map<String, ?> companion) {
        return Decision.APPLY;
    }

    public static boolean request(Context context, String key, Object value, Runnable apply) {
        apply.run();
        return true;
    }
}
