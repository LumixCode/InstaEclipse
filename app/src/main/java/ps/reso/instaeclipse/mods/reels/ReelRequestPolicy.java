package ps.reso.instaeclipse.mods.reels;

import java.net.URI;

/** The DM exception changes reel delivery only; it must never skip other network privacy checks. */
public final class ReelRequestPolicy {
    private ReelRequestPolicy() {}

    public static boolean shouldBlock(URI uri, boolean blocked, boolean allowDM) {
        if (!blocked || uri == null || uri.getPath() == null) return false;
        String path = uri.getPath();
        boolean clips = path.contains("/clips/") || path.endsWith("/clips");
        // discover/explore_clips/ is the Explore reels feed; "_clips/" does not match "/clips/".
        boolean discovery = path.contains("/mixed_media/") || path.contains("/explore_clips/");
        if (!clips && !discovery) return false;
        if (!allowDM) return true;
        // DM-opened items remain fetchable while a restriction is active. The viewer validates the
        // source and permits only that first item; this endpoint alone is not proof of a DM entry.
        return discovery || !path.endsWith("/clips/items/") || hasPagination(uri.getRawQuery());
    }

    public static boolean shouldBlock(URI uri, boolean disabled, boolean allowDM, boolean limitReached) {
        return shouldBlock(uri, disabled || limitReached, allowDM);
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
