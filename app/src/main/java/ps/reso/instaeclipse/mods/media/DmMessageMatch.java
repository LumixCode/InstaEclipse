package ps.reso.instaeclipse.mods.media;

import java.util.List;

final class DmMessageMatch {
    static final String MENU_TAG = "DirectThreadFragment.showMessageActionDialog";
    private static final long RECENT_MS = 15000;

    private Object last;
    private long lastAt;

    synchronized void onLookup(String id, String tag, Object message, long now) {
        last = message;
        lastAt = now;
    }

    synchronized Object recent(long now) {
        return now - lastAt < RECENT_MS ? last : null;
    }

    synchronized Object pressed(List<String> ids, long now) {
        return recent(now);
    }

    static boolean stickerType(String type) {
        return !"TEXT".equals(type) && !"LINK".equals(type) && !"VOICE_MEDIA".equals(type);
    }

    static String owner(String person, String chat) {
        if (person != null) return person;
        if (chat != null && !chat.isEmpty()) return chat.replaceAll("[^\\p{L}\\p{N}._-]", "_");
        return "voice";
    }
}
