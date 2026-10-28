package ps.reso.instaeclipse.mods.ui;

public final class PasscodeRecovery {

    public enum Path { NEEDS_ANDROID_10, NEEDS_SCREEN_LOCK, CREDENTIAL_ALLOWED, DEVICE_CREDENTIAL }

    public enum Outcome { RECOVERED, CANCELLED, NEEDS_SCREEN_LOCK, FAILED }

    public static final int MIN_SDK = 29;
    public static final int AUTHENTICATORS_SDK = 30;
    static final int ERROR_CANCELED = 5;
    static final int ERROR_USER_CANCELED = 10;
    static final int ERROR_NO_DEVICE_CREDENTIAL = 14;

    private PasscodeRecovery() {}

    public static Path path(int sdk, boolean deviceSecure) {
        if (sdk < MIN_SDK) return Path.NEEDS_ANDROID_10;
        if (!deviceSecure) return Path.NEEDS_SCREEN_LOCK;
        return sdk >= AUTHENTICATORS_SDK ? Path.DEVICE_CREDENTIAL : Path.CREDENTIAL_ALLOWED;
    }

    public static boolean asksForCredential(Path path) {
        return path == Path.CREDENTIAL_ALLOWED || path == Path.DEVICE_CREDENTIAL;
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
