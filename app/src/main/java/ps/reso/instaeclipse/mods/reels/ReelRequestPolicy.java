package ps.reso.instaeclipse.mods.reels;

import java.net.URI;

public final class ReelRequestPolicy {
    private static final String CLIPS = "/clips/";
    private static final String[] CREATION_ENDPOINTS = {"drafts", "delete_draft", "draft_content",
            "draft_reminder", "archive_clip", "music", "creative_tool", "clips_info_for_creation", "effect",
            "write_seen_state"};
    private static final String[] CREATION_PREFIXES = {"template", "translation_", "item/set_", "user/set_"};

    private ReelRequestPolicy() {}

    public static boolean shouldBlock(URI uri, boolean blocked, boolean allowDM) {
        if (!blocked || uri == null || uri.getPath() == null) return false;
        String path = uri.getPath();
        int clipsAt = path.indexOf(CLIPS);
        if (clipsAt >= 0 && isCreation(path.substring(clipsAt + CLIPS.length()))) return false;
        boolean clips = clipsAt >= 0 || path.endsWith("/clips");
        boolean discovery = path.contains("/explore_clips/");
        if (!clips && !discovery) return false;
        if (!allowDM) return true;
        return discovery || !path.endsWith("/clips/items/") || hasPagination(uri.getRawQuery());
    }

    private static boolean isCreation(String endpoint) {
        for (String name : CREATION_ENDPOINTS) {
            if (endpoint.equals(name) || endpoint.startsWith(name + "/")) return true;
        }
        for (String prefix : CREATION_PREFIXES) {
            if (endpoint.startsWith(prefix)) return true;
        }
        return false;
    }

    private static boolean hasPagination(String query) {
        if (query == null) return false;
        for (String part : query.split("&")) {
            String key = part.split("=", 2)[0];
            if (key.equals("next_media_ids") || key.equals("max_id")) return true;
        }
        return false;
    }
}
