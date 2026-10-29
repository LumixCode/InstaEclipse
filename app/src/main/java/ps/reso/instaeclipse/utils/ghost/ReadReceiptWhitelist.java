package ps.reso.instaeclipse.utils.ghost;

import java.util.Map;

import ps.reso.instaeclipse.utils.feature.FeatureFlags;
import ps.reso.instaeclipse.utils.log.ModuleLog;

public class ReadReceiptWhitelist {

    private static final IdTitleStore store = new IdTitleStore("ie_read_receipt_whitelist.json",
            failure -> ModuleLog.warn("(IE|ReadReceipts) Could not read the chats that may send read receipts; changes are not saved until Instagram restarts: " + failure),
            failure -> ModuleLog.error("(IE|ReadReceipts) Could not save the chats that may send read receipts: " + failure));

    public static boolean isAllowed(String threadId) {
        return FeatureFlags.readReceiptChats && isListed(threadId);
    }

    public static boolean isListed(String threadId) {
        return threadId != null && !threadId.isEmpty() && store.contains(threadId);
    }

    public static boolean pathMentionsAllowed(String path) {
        if (path == null || !FeatureFlags.readReceiptChats) return false;
        for (String id : store.ids()) if (path.contains("/" + id + "/")) return true;
        return false;
    }

    public static void set(String threadId, String title, boolean allowed) {
        if (threadId == null || threadId.isEmpty()) return;
        if (allowed) store.put(threadId, title == null ? "" : title);
        else store.remove(threadId);
    }

    public static Map<String, String> all() {
        return store.all();
    }
}
