package ps.reso.instaeclipse.mods.ui;

public final class LockRecentsPolicy {

    public static final int MIN_SDK = 33;

    private LockRecentsPolicy() {}

    public static boolean hides(int sdk, boolean passcodeSet, boolean wholeApp, boolean directLock, boolean chatLock,
                                DirectLockSession.Screen screen, boolean chatLocked, boolean hiddenBefore) {
        if (sdk < MIN_SDK || !passcodeSet) return false;
        if (wholeApp) return true;
        if (!directLock && !chatLock) return false;
        switch (screen) {
            case UNKNOWN:
                return hiddenBefore;
            case INBOX:
            case DIRECT:
                return directLock;
            case THREAD:
                return directLock || (chatLock && chatLocked);
            default:
                return false;
        }
    }
}
