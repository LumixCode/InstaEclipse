package ps.reso.instaeclipse.mods.ghost;

import android.view.WindowManager;

final class SecureFlags {

    private SecureFlags() {}

    static int[] strip(int flags, int mask) {
        int secure = WindowManager.LayoutParams.FLAG_SECURE;
        return new int[]{flags & ~secure, mask & ~secure};
    }
}
