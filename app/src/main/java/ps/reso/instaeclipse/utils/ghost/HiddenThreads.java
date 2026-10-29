package ps.reso.instaeclipse.utils.ghost;

import java.util.Map;
import java.util.Set;

import ps.reso.instaeclipse.utils.log.ModuleLog;

public class HiddenThreads {

    private static final IdTitleStore store = new IdTitleStore("ie_hidden_threads.json",
            failure -> ModuleLog.error("(IE|HiddenThreads) Could not read the list of hidden chats; changes are not saved until Instagram restarts: " + failure),
            failure -> ModuleLog.error("(IE|HiddenThreads) Could not save the list of hidden chats: " + failure));

    public static Set<String> ids() {
        return store.ids();
    }

    public static long revision() {
        return store.revision();
    }

    public static boolean isHidden(String threadId) {
        return threadId != null && !threadId.isEmpty() && store.contains(threadId);
    }

    public static void hide(String threadId, String title) {
        if (threadId == null || threadId.isEmpty()) return;
        store.put(threadId, title == null ? "" : title);
    }

    public static void setHidden(String threadId, String title, boolean hidden) {
        if (hidden) hide(threadId, title);
        else unhide(threadId);
    }

    public static void unhide(String threadId) {
        store.remove(threadId);
    }

    public static Map<String, String> all() {
        return store.all();
    }
}
