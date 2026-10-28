package ps.reso.instaeclipse.mods.ui;

public final class PasscodeRecovery {

    public enum Path { NEEDS_ANDROID_11, NEEDS_SCREEN_LOCK, DEVICE_CREDENTIAL }

    public enum Outcome { RECOVERED, CANCELLED, NEEDS_SCREEN_LOCK, FAILED }

    public static final int MIN_SDK = 30;
    static final int ERROR_CANCELED = 5;
    static final int ERROR_USER_CANCELED = 10;
    static final int ERROR_NO_DEVICE_CREDENTIAL = 14;

    private PasscodeRecovery() {}

    public static Path path(int sdk, boolean deviceSecure) {
        if (sdk < MIN_SDK) return Path.NEEDS_ANDROID_11;
        if (!deviceSecure) return Path.NEEDS_SCREEN_LOCK;
        return Path.DEVICE_CREDENTIAL;
    }

    public static boolean asksForCredential(Path path) {
        return path == Path.DEVICE_CREDENTIAL;
    }

    public static Outcome error(int code) {
        if (code == ERROR_NO_DEVICE_CREDENTIAL) return Outcome.NEEDS_SCREEN_LOCK;
        if (code == ERROR_CANCELED || code == ERROR_USER_CANCELED) return Outcome.CANCELLED;
        return Outcome.FAILED;
    }

    public static boolean removesPasscode(Outcome outcome) {
        return outcome == Outcome.RECOVERED;
    }
}
