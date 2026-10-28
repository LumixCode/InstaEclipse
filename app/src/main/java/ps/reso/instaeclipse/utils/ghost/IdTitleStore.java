package ps.reso.instaeclipse.utils.ghost;

import android.content.Context;

import org.json.JSONObject;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Collections;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;
import java.util.function.Predicate;
import java.util.function.Supplier;

import ps.reso.instaeclipse.mods.media.DataBackup;
import ps.reso.instaeclipse.utils.core.AtomicFiles;
import ps.reso.instaeclipse.xposedcompat.AndroidAppHelper;

final class IdTitleStore {

    private final Supplier<File> file;
    private final Consumer<String> readFailed;
    private final Consumer<String> saveFailed;
    private final Predicate<Map<String, String>> afterLoad;
    private final Map<String, String> map = new LinkedHashMap<>();
    private volatile Set<String> ids = Collections.emptySet();
    private volatile long revision;
    private volatile boolean loaded;
    private boolean loadFailed;

    IdTitleStore(String fileName, Consumer<String> readFailed, Consumer<String> saveFailed) {
        this(fileName, readFailed, saveFailed, titles -> false);
    }

    IdTitleStore(String fileName, Consumer<String> readFailed, Consumer<String> saveFailed,
                 Predicate<Map<String, String>> afterLoad) {
        this(() -> appFile(fileName), readFailed, saveFailed, afterLoad);
    }

    IdTitleStore(Supplier<File> file, Consumer<String> readFailed, Consumer<String> saveFailed,
                 Predicate<Map<String, String>> afterLoad) {
        this.file = file;
        this.readFailed = readFailed;
        this.saveFailed = saveFailed;
        this.afterLoad = afterLoad;
    }

    private static File appFile(String fileName) {
        Context ctx = AndroidAppHelper.currentApplication();
        return ctx == null ? null : new File(ctx.getFilesDir(), fileName);
    }

    private synchronized void ensureLoaded() {
        if (loaded || loadFailed) return;
        File f = file.get();
        if (f == null) return;
        try {
            boolean existed = f.exists();
            if (existed) {
                String json = new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8).trim();
                JSONObject o = new JSONObject(json.isEmpty() ? "{}" : json);
                for (Iterator<String> it = o.keys(); it.hasNext(); ) {
                    String k = it.next();
                    map.put(k, o.optString(k, ""));
                }
            }
            boolean changed = existed && afterLoad.test(map);
            loaded = true;
            publish();
            if (changed) persist();
        } catch (Throwable t) {
            loadFailed = true;
            publish();
            readFailed.accept(t.getClass().getSimpleName() + ": " + t.getMessage());
        }
    }

    private synchronized void persist() {
        if (!loaded || DataBackup.restoring()) return;
        try {
            File f = file.get();
            if (f == null) return;
            JSONObject o = new JSONObject();
            for (Map.Entry<String, String> e : map.entrySet()) o.put(e.getKey(), e.getValue());
            AtomicFiles.write(f, o.toString());
        } catch (Throwable t) {
            saveFailed.accept(t.getClass().getSimpleName() + ": " + t.getMessage());
        }
    }

    private void publish() {
        ids = Collections.unmodifiableSet(new LinkedHashSet<>(map.keySet()));
        revision++;
    }

    Set<String> ids() {
        if (!loaded) ensureLoaded();
        return ids;
    }

    long revision() {
        if (!loaded) ensureLoaded();
        return revision;
    }

    boolean contains(String id) {
        return id != null && ids().contains(id);
    }

    synchronized String get(String id) {
        ensureLoaded();
        String title = map.get(id);
        return title == null ? "" : title;
    }

    synchronized Map<String, String> all() {
        ensureLoaded();
        return new LinkedHashMap<>(map);
    }

    synchronized boolean put(String id, String title) {
        ensureLoaded();
        if (title.equals(map.get(id))) return false;
        if (map.put(id, title) == null) publish();
        persist();
        return true;
    }

    synchronized boolean putAll(Map<String, String> titles) {
        ensureLoaded();
        boolean changed = false, added = false;
        for (Map.Entry<String, String> e : titles.entrySet()) {
            if (e.getValue().equals(map.get(e.getKey()))) continue;
            added |= map.put(e.getKey(), e.getValue()) == null;
            changed = true;
        }
        if (added) publish();
        if (changed) persist();
        return changed;
    }

    synchronized boolean remove(String id) {
        ensureLoaded();
        if (map.remove(id) == null) return false;
        publish();
        persist();
        return true;
    }
}
