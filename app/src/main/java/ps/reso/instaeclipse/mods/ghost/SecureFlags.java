package ps.reso.instaeclipse.mods.ghost;

import android.view.WindowManager;

final class SecureFlags {

    private SecureFlags() {}

    static int[] strip(int flags, int mask) {
        return new int[]{flags & ~WindowManager.LayoutParams.FLAG_SECURE, mask};
    }
}
