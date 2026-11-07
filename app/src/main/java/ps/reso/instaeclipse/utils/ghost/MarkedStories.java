package ps.reso.instaeclipse.utils.ghost;

import java.util.Iterator;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import ps.reso.instaeclipse.utils.log.ModuleLog;

public final class MarkedStories {

    private static final String TAG = "(IE|ManualStorySeen) ";
    private static final int KEPT = 2000;
    private static final ExecutorService WORKER = Executors.newSingleThreadExecutor();
    private static final IdTitleStore STORE = new IdTitleStore("ie_marked_stories.json",
            failure -> ModuleLog.warn(TAG + "Could not read the stories you marked as seen: " + failure),
            failure -> ModuleLog.warn(TAG + "Could not save the stories you marked as seen: " + failure),
            MarkedStories::trim);

    private MarkedStories() {}

    public static String key(String account, String media) {
        return account == null || media == null ? null : account + ":" + media;
    }

    public static void load() {
        WORKER.execute(STORE::revision);
    }

    public static boolean contains(String key) {
        return STORE.contains(key);
    }

    public static void add(String key) {
        if (key == null) return;
        WORKER.execute(() -> {
            if (!STORE.put(key, String.valueOf(System.currentTimeMillis()))) return;
            while (STORE.ids().size() > KEPT) STORE.remove(STORE.ids().iterator().next());
        });
    }

    private static boolean trim(Map<String, String> kept) {
        int extra = kept.size() - KEPT;
        if (extra <= 0) return false;
        Iterator<String> it = kept.keySet().iterator();
        for (int i = 0; i < extra; i++) {
            it.next();
            it.remove();
        }
        return true;
    }
}
