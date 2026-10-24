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
        if (first == null || first.isEmpty()) return Result.EMPTY;
        for (int i = 0; i < first.length(); i++) {
            char c = first.charAt(i);
            if (c < '0' || c > '9') return Result.NOT_DIGITS;
        }
        if (first.length() < MIN_DIGITS) return Result.TOO_SHORT;
        return first.equals(second) ? Result.OK : Result.MISMATCH;
    }

    public static boolean isLock(String key) {
        for (String lock : LOCKS) if (lock.equals(key)) return true;
        return false;
    }

    public static boolean locksNeedPasscode() {
        return !lockSet().isEmpty() && !SettingsManager.passcodeSet(FeatureFlags.lockDirectPasscode);
    }

    public static boolean askAtResume(boolean askedThisStart, String muted) {
        return !askedThisStart && locksNeedPasscode() && !lockSet().equals(muted);
    }

    public static String lockSet() {
        List<String> on = new ArrayList<>();
        for (String key : LOCKS) if (ExtrasFeatures.get(key)) on.add(key);
        return String.join(",", on);
    }

    public static void turnLocksOff() {
        for (String key : LOCKS) ExtrasFeatures.set(key, false);
    }

    public static void removePasscode() {
        FeatureFlags.lockDirectPasscode = "";
        FeatureFlags.lockDirectSalt = "";
        turnLocksOff();
    }
}
