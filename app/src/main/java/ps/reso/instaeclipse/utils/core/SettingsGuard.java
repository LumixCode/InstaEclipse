package ps.reso.instaeclipse.utils.core;

import android.content.Context;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import ps.reso.instaeclipse.R;
import ps.reso.instaeclipse.utils.dialog.LockFlows;
import ps.reso.instaeclipse.utils.feature.FeatureFlags;
import ps.reso.instaeclipse.utils.i18n.I18n;

public final class SettingsGuard {

    public enum Decision { APPLY, AUTHENTICATE, SET_PASSCODE, REFUSE }

    public static final String HIDE_PICKER = "hideChooseChats";

    private static final Set<String> LOCKS = new HashSet<>(Arrays.asList(PasscodeRules.LOCKS));
    private static final Set<String> OFF_WEAKENS = new HashSet<>(Arrays.asList(
            "lockWholeApp", "lockDirectMessages", "lockSpecificMessages", "lockSavedCollections",
            "lockDirectAlways", "hideSpecificChats"));

    private SettingsGuard() {}

    public static boolean weakens(String key, Object value) {
        if (key == null) return false;
        if (HIDE_PICKER.equals(key)) return true;
        if ("lockUseFingerprint".equals(key)) return Boolean.TRUE.equals(value);
        return OFF_WEAKENS.contains(key) && !Boolean.TRUE.equals(value);
    }

    public static Decision decide(String key, Object value, boolean passcodeSet) {
        if (weakens(key, value)) return passcodeSet ? Decision.AUTHENTICATE : Decision.APPLY;
        if (!passcodeSet && LOCKS.contains(key) && Boolean.TRUE.equals(value)) return Decision.SET_PASSCODE;
        return Decision.APPLY;
    }

    public static Decision decideInApp(String key, Object value, Map<String, ?> companion) {
        if (!weakens(key, value)) return Decision.APPLY;
        Object signal = companion == null ? null : companion.get(SettingsManager.PASSCODE_SET);
        if (Boolean.FALSE.equals(signal)) return Decision.APPLY;
        if (Boolean.TRUE.equals(signal)) return Decision.REFUSE;
        return OFF_WEAKENS.contains(key) ? Decision.REFUSE : Decision.APPLY;
    }

    public static boolean request(Context context, String key, Object value, Runnable apply) {
        switch (decide(key, value, SettingsManager.passcodeSet(FeatureFlags.lockDirectPasscode))) {
            case AUTHENTICATE:
                LockFlows.authenticate(LockFlows.activity(context), I18n.t(context, R.string.ig_lock_settings_prompt),
                        "settings", apply);
                return false;
            case SET_PASSCODE:
                LockFlows.setPasscode(LockFlows.activity(context), apply, null);
                return false;
            case REFUSE:
                return false;
            default:
                apply.run();
                return true;
        }
    }
}
