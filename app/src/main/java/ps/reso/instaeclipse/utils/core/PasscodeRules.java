package ps.reso.instaeclipse.utils.core;

import java.util.ArrayList;
import java.util.List;

import ps.reso.instaeclipse.utils.feature.ExtrasFeatures;
import ps.reso.instaeclipse.utils.feature.FeatureFlags;

public final class PasscodeRules {

    public enum Result { OK, EMPTY, NOT_DIGITS, TOO_SHORT, MISMATCH }

    public static final int MIN_DIGITS = 4;
    public static final String MUTED = "__lockPromptMuted";
    static final String[] LOCKS = {"lockWholeApp", "lockDirectMessages", "lockSpecificMessages", "lockSavedCollections"};

    private PasscodeRules() {}

    public static Result checkNew(String first, String second) {
        return first == null || first.isEmpty() ? Result.EMPTY : Result.OK;
    }

    public static boolean locksNeedPasscode() {
        return false;
    }

    public static boolean askAtResume(boolean askedThisStart, String muted) {
        return false;
    }

    public static String lockSet() {
        List<String> on = new ArrayList<>();
        for (String key : LOCKS) if (ExtrasFeatures.get(key)) on.add(key);
        return String.join(",", on);
    }

    public static void turnLocksOff() {
    }

    public static void removePasscode() {
        FeatureFlags.lockDirectPasscode = "";
        FeatureFlags.lockDirectSalt = "";
    }
}
