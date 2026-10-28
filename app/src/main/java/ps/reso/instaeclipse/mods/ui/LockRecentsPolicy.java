package ps.reso.instaeclipse.mods.ui;

public final class LockRecentsPolicy {

    public static final int MIN_SDK = 33;

    private LockRecentsPolicy() {}

    public static boolean hides(int sdk, boolean passcodeSet, boolean wholeApp, boolean directLock, boolean chatLock,
                                DirectLockSession.Screen screen, boolean chatLocked, boolean hiddenBefore) {
        return false;
    }
}
