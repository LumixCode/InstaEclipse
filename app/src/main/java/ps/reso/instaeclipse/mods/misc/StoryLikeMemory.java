package ps.reso.instaeclipse.mods.misc;

import android.content.Context;

import org.json.JSONObject;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;

import ps.reso.instaeclipse.mods.media.DataBackup;
import ps.reso.instaeclipse.utils.core.AtomicFiles;
import ps.reso.instaeclipse.utils.log.ModuleLog;
import ps.reso.instaeclipse.xposedcompat.AndroidAppHelper;

final class StoryLikeMemory {

    private static final String TAG = "(InstaEclipse | LikeUndo): ";
    private static final String FILE_NAME = "ie_story_likes.json";
    private static final long KEEP_MS = 24 * 60 * 60 * 1000L;
    private static final int MAX_LIKES = 500;
    private static final Map<String, Long> likes = new LinkedHashMap<>();
    private static boolean loaded;

    private StoryLikeMemory() {}

    static synchronized boolean has(String account, String id) {
        ensureLoaded();
        Long at = likes.get(key(account, id));
        return at != null && System.currentTimeMillis() - at < KEEP_MS;
    }

    static synchronized void remember(String account, String id) {
        ensureLoaded();
        String key = key(account, id);
        long now = System.currentTimeMillis();
        likes.remove(key);
        likes.put(key, now);
        trim(now);
        save();
    }

    static synchronized boolean forget(String id) {
        ensureLoaded();
        String end = key("", id);
        if (!likes.keySet().removeIf(key -> key.endsWith(end))) return false;
        save();
        return true;
    }

    private static String key(String account, String id) {
        return account + ":" + id;
    }

    private static void trim(long now) {
        Iterator<Long> times = likes.values().iterator();
        while (times.hasNext()) {
            long at = times.next();
            if (likes.size() > MAX_LIKES || now - at >= KEEP_MS) times.remove();
        }
    }

    private static File file() {
        Context ctx = AndroidAppHelper.currentApplication();
        return ctx == null ? null : new File(ctx.getFilesDir(), FILE_NAME);
    }

    private static void ensureLoaded() {
        if (loaded) return;
        File f = file();
        if (f == null) return;
        loaded = true;
        if (!f.exists()) return;
        try {
            String json = new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8).trim();
            JSONObject o = new JSONObject(json.isEmpty() ? "{}" : json);
            for (Iterator<String> it = o.keys(); it.hasNext(); ) {
                String key = it.next();
                likes.put(key, o.optLong(key));
            }
            trim(System.currentTimeMillis());
        } catch (Throwable t) {
            ModuleLog.warn(TAG + "Could not read the saved story likes: " + t.getClass().getSimpleName() + ": " + t.getMessage());
        }
    }

    private static void save() {
        if (!loaded || DataBackup.restoring()) return;
        try {
            File f = file();
            if (f == null) return;
            JSONObject o = new JSONObject();
            for (Map.Entry<String, Long> like : likes.entrySet()) o.put(like.getKey(), like.getValue().longValue());
            AtomicFiles.writeAsync(f, o.toString());
        } catch (Throwable t) {
            ModuleLog.warn(TAG + "Could not save the story likes: " + t.getClass().getSimpleName() + ": " + t.getMessage());
        }
    }
}
