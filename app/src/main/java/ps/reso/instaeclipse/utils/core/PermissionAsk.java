package ps.reso.instaeclipse.utils.core;

import android.Manifest;

import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;

public final class PermissionAsk {

    public static final String ASKED = "downloadPermissionAsked";
    public static final Set<String> DOWNLOAD_KEYS = Collections.unmodifiableSet(new LinkedHashSet<>(Arrays.asList(
            "enablePostDownload", "enableStoryDownload", "enableReelDownload", "enableProfileDownload",
            "enableVoiceDownload", "enableStickerDownload", "enableDmMediaDownload", "offerAudioDownload",
            "saveInstants")));

    public enum Trigger { NONE, DOWNLOAD_TURNED_ON, FOLDER_PICKED, FEATURES_OPENED }

    private PermissionAsk() {}

    public static boolean shouldAsk(boolean granted, boolean askedBefore, Trigger trigger) {
        return !granted && !askedBefore && trigger != null && trigger != Trigger.NONE;
    }

    public static Trigger forChanges(Map<String, ?> changes) {
        for (Map.Entry<String, ?> change : changes.entrySet()) {
            if (DOWNLOAD_KEYS.contains(change.getKey()) && Boolean.TRUE.equals(change.getValue())) {
                return Trigger.DOWNLOAD_TURNED_ON;
            }
        }
        return Trigger.NONE;
    }

    public static Trigger forOpening(Predicate<String> isOn) {
        for (String key : DOWNLOAD_KEYS) if (isOn.test(key)) return Trigger.FEATURES_OPENED;
        return Trigger.NONE;
    }

    public static String permission(int sdk) {
        if (sdk >= 33) return Manifest.permission.POST_NOTIFICATIONS;
        if (sdk < 29) return Manifest.permission.WRITE_EXTERNAL_STORAGE;
        return null;
    }
}
