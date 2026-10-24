package ps.reso.instaeclipse.mods.ghost;

import android.os.Build;

final class HiddenOnlyRecents {

    private HiddenOnlyRecents() {}

    static boolean hideFromRecents(int sdk, boolean hiddenOnly) {
        return hiddenOnly && sdk >= Build.VERSION_CODES.TIRAMISU;
    }
}
