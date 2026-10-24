package ps.reso.instaeclipse.mods.media;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

final class DmMessageMatch {
    static final String MENU_TAG = "DirectThreadFragment.showMessageActionDialog";
    private static final int CAPACITY = 8;
    private static final long MENU_MS = 2000;
    private static final long RECENT_MS = 15000;

    private final Map<String, Object> byId = new LinkedHashMap<>(16, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, Object> eldest) {
            return size() > CAPACITY;
        }
    };
    private Object last;
    private String lastTag;
    private long lastAt;

    synchronized void onLookup(String id, String tag, Object message, long now) {
        if (id != null) byId.put(id, message);
        last = message;
        lastTag = tag;
        lastAt = now;
    }

    synchronized Object recent(long now) {
        return now - lastAt < RECENT_MS ? last : null;
    }

    synchronized Object pressed(List<String> ids, long now) {
        for (String id : ids) {
            Object message = byId.get(id);
            if (message != null) return message;
        }
        return MENU_TAG.equals(lastTag) && now - lastAt < MENU_MS ? last : null;
    }

    static boolean stickerType(String type) {
        return type != null && ("ANIMATED_MEDIA".equals(type) || "STORE_STICKER".equals(type) || type.endsWith("_STICKER"));
    }

    static String owner(String person, String chat) {
        if (person != null) return person;
        if (chat != null && !chat.isEmpty()) return chat.replaceAll("[^\\p{L}\\p{N}._-]", "_");
        return null;
    }
}
