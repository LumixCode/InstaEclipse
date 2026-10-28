package ps.reso.instaeclipse.mods.media;

import org.json.JSONException;

final class DataRestoreMerge {

    private DataRestoreMerge() {}

    static boolean merges(String name) {
        return false;
    }

    static byte[] merge(String name, byte[] current, byte[] backup) throws JSONException {
        return backup;
    }
}
