package ps.reso.instaeclipse.utils.feature;

import android.content.SharedPreferences;

import java.util.Map;

public final class SpoofLastSeenNotice {

    public static final String KEY = "spoofLastSeen";
    public static final String SHOWN = "spoofLastSeenNoticeShown";

    private SpoofLastSeenNotice() {}

    public static boolean shouldShow(Map<String, ?> stored) {
        return stored != null && Boolean.TRUE.equals(stored.get(KEY)) && !stored.containsKey(SHOWN);
    }

    public static boolean shouldShow(SharedPreferences prefs) {
        return prefs != null && shouldShow(prefs.getAll());
    }

    public static void dismiss(SharedPreferences prefs) {
        if (prefs != null) prefs.edit().putBoolean(SHOWN, true).remove(KEY).apply();
    }
}
