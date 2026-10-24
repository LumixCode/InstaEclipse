package ps.reso.instaeclipse.utils.ghost;

import java.util.Map;

import ps.reso.instaeclipse.utils.feature.FeatureFlags;
import ps.reso.instaeclipse.utils.log.ModuleLog;

/**
 * Per-chat read-receipt whitelist (#227): DM thread ids that still get "Seen" while Hide DM Seen
 * (ghost mode) is on for everyone else. Same storage scheme as {@link HiddenThreads} (JSON in IG's
 * filesDir, id -> title), read in the Instagram process by GhostDMSeenHook and the network
 * interceptor.
 *
 * JSON: { "<threadId>": "<title>", ... }
 */
public class ReadReceiptWhitelist {

    private static final IdTitleStore store = new IdTitleStore("ie_read_receipt_whitelist.json",
            failure -> ModuleLog.warn("(IE|ReadReceipts) Could not read the chats that may send read receipts; changes are not saved until Instagram restarts: " + failure),
            failure -> ModuleLog.error("(IE|ReadReceipts) Could not save the chats that may send read receipts: " + failure));

    /** True when Instagram should send read receipts for this thread despite ghost mode. */
    public static boolean isAllowed(String threadId) {
        return FeatureFlags.readReceiptChats && isListed(threadId);
    }

    /** True when the chat is on the list (regardless of the feature switch), for the chat menu. */
    public static boolean isListed(String threadId) {
        return threadId != null && !threadId.isEmpty() && store.contains(threadId);
    }

    /** True when the request path names a whitelisted thread (/direct_v2/threads/<id>/…). */
    public static boolean pathMentionsAllowed(String path) {
        if (path == null || !FeatureFlags.readReceiptChats) return false;
        for (String id : store.ids()) if (path.contains("/" + id + "/")) return true;
        return false;
    }

    /** Adds or removes a thread (title kept for the manager label). */
    public static void set(String threadId, String title, boolean allowed) {
        if (threadId == null || threadId.isEmpty()) return;
        if (allowed) store.put(threadId, title == null ? "" : title);
        else store.remove(threadId);
    }

    public static Map<String, String> all() {
        return store.all();
    }
}
