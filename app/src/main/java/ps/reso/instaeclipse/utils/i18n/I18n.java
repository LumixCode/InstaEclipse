package ps.reso.instaeclipse.utils.i18n;

import android.content.Context;
import android.content.res.Resources;

import androidx.annotation.StringRes;

import ps.reso.instaeclipse.utils.core.ModuleResources;
import ps.reso.instaeclipse.utils.log.ModuleLog;

/**
 * Loads string resources from the InstaEclipse module APK while running inside
 * the host app (Instagram) process.
 *
 * Uses the loaded module's resource table so embedded and companion builds cannot mix IDs.
 */
public final class I18n {

    private I18n() {}

    public static String t(Context hostContext, @StringRes int resId, Object... args) {
        try {
            Resources resources = ModuleResources.get(hostContext);
            return args.length == 0
                    ? resources.getString(resId)
                    : resources.getString(resId, args);
        } catch (Exception e) {
            ModuleLog.warn("(IE|Resources) A text could not be loaded (0x" + Integer.toHexString(resId) + ")", e);
            return "[Text unavailable]";
        }
    }

    /** The module's Resources; kept for upstream callers (ThemePresets, InstantUploadHook). */
    public static Resources moduleResources(Context hostContext) throws Exception {
        return ModuleResources.get(hostContext);
    }
}
