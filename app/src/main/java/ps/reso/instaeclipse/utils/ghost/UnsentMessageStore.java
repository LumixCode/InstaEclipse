package ps.reso.instaeclipse.utils.ghost;

import android.content.Context;
import android.util.AtomicFile;
import android.util.Base64;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

import ps.reso.instaeclipse.utils.log.ModuleLog;

/**
 * Copies of retained messages in Instagram's own serialized form, one file each in Instagram's
 * filesDir. Instagram keeps only each thread's newest messages on disk and reloads older history
 * from the server, which no longer returns unsent ones; these copies let them be restored.
 * Clearing the unsent log deletes the matching copies.
 */
public final class UnsentMessageStore {
    public static final class Saved {
        public final String account, thread;
        public final List<String> ids;
        public final long time;
        public final byte[] message;
        final File file;

        Saved(String account, String thread, List<String> ids, long time, byte[] message, File file) {
            this.account = account; this.thread = thread; this.ids = ids;
            this.time = time; this.message = message; this.file = file;
        }
    }

    private static final String DIR = "ie_unsent_messages";
    private static final int MAX_SAVED = 500;

    private static File dir;
    private static List<Saved> saved;

    private UnsentMessageStore() {}

    public static synchronized void init(Context context) {
        if (dir == null) dir = new File(context.getFilesDir(), DIR);
    }

    public static synchronized boolean isEmpty() { return load().isEmpty(); }

    public static synchronized List<Saved> forThread(String account, String thread) {
        List<Saved> out = new ArrayList<>();
        if (account == null || thread == null) return out;
        for (Saved copy : load()) if (account.equals(copy.account) && thread.equals(copy.thread)) out.add(copy);
        return out;
    }

    /** The copy of one message, matched by any of its ids within the account's thread, or null.
     *  Before the copies are loaded only that message's own file is read (it is named after the
     *  first id it was saved under), so the Unsent viewer never reads every copy to label a row. */
    public static synchronized Saved find(String account, String thread, List<String> ids) {
        if (account == null || thread == null || ids == null || ids.isEmpty()) return null;
        if (saved == null && dir != null) {
            for (String id : ids) {
                try {
                    File file = new File(dir, name(account, thread, id));
                    Saved copy = file.isFile() ? read(file) : null;
                    if (copy != null && account.equals(copy.account) && thread.equals(copy.thread)
                            && !Collections.disjoint(copy.ids, ids)) return copy;
                } catch (Exception e) {
                    ModuleLog.detail("(IE|UnsentStore) unreadable copy skipped: " + e.getClass().getSimpleName());
                }
            }
            return null;
        }
        for (Saved copy : load()) {
            if (account.equals(copy.account) && thread.equals(copy.thread)
                    && !Collections.disjoint(copy.ids, ids)) return copy;
        }
        return null;
    }

    public static synchronized void save(String account, String thread, List<String> ids, long time, byte[] message) {
        if (dir == null || account == null || thread == null || ids.isEmpty() || message == null) return;
        List<Saved> all = load();
        for (Saved copy : all) {
            if (account.equals(copy.account) && thread.equals(copy.thread)
                    && !Collections.disjoint(copy.ids, ids)) return;
        }
        try {
            if (!dir.isDirectory() && !dir.mkdirs()) return;
            JSONObject o = new JSONObject();
            o.put("a", account); o.put("th", thread); o.put("ids", new JSONArray(ids));
            o.put("ts", time); o.put("m", Base64.encodeToString(message, Base64.NO_WRAP));
            File file = new File(dir, name(account, thread, ids.get(0)));
            AtomicFile atomic = new AtomicFile(file);
            FileOutputStream out = atomic.startWrite();
            try {
                out.write(o.toString().getBytes(StandardCharsets.UTF_8));
                atomic.finishWrite(out);
            } catch (Exception e) {
                atomic.failWrite(out);
                throw e;
            }
            all.add(new Saved(account, thread, new ArrayList<>(ids), time, message, file));
            while (all.size() > MAX_SAVED) {
                Saved oldest = Collections.min(all, Comparator.comparingLong(copy -> copy.time));
                remove(oldest);
            }
        } catch (Exception e) {
            ModuleLog.warn("(IE|UnsentStore) Could not keep a copy of an unsent message: " + e.getClass().getSimpleName() + ": " + e.getMessage());
        }
    }

    public static synchronized void remove(Saved copy) {
        load().remove(copy);
        new AtomicFile(copy.file).delete();
    }

    /** Delete the copies of one message, matched by any of its ids within the account's thread. */
    public static synchronized void removeMessage(String account, String thread, List<String> ids) {
        if (account == null || thread == null || ids == null || ids.isEmpty()) return;
        for (Saved copy : new ArrayList<>(load())) {
            if (account.equals(copy.account) && thread.equals(copy.thread)
                    && !Collections.disjoint(copy.ids, ids)) remove(copy);
        }
    }

    /** Delete copies for one thread, or for the whole account when {@code thread} is null. */
    public static synchronized void clear(String account, String thread) {
        if (account == null) return;
        for (Saved copy : new ArrayList<>(load())) {
            if (account.equals(copy.account) && (thread == null || thread.equals(copy.thread))) remove(copy);
        }
    }

    private static List<Saved> load() {
        if (saved != null) return saved;
        if (dir == null) return new ArrayList<>();
        saved = new ArrayList<>();
        File[] files = dir.listFiles();
        if (files == null) return saved;
        for (File file : files) {
            if (file.getName().endsWith(".bak") || file.getName().endsWith(".new")) continue;
            try {
                saved.add(read(file));
            } catch (Exception e) {
                ModuleLog.detail("(IE|UnsentStore) unreadable copy skipped: " + e.getClass().getSimpleName());
            }
        }
        return saved;
    }

    private static Saved read(File file) throws Exception {
        JSONObject o = new JSONObject(new String(new AtomicFile(file).readFully(), StandardCharsets.UTF_8));
        JSONArray idArray = o.getJSONArray("ids");
        List<String> ids = new ArrayList<>();
        for (int i = 0; i < idArray.length(); i++) ids.add(idArray.getString(i));
        return new Saved(o.getString("a"), o.getString("th"), ids, o.getLong("ts"),
                Base64.decode(o.getString("m"), Base64.NO_WRAP), file);
    }

    private static String name(String account, String thread, String message) throws Exception {
        byte[] hash = MessageDigest.getInstance("SHA-256")
                .digest((account + '\n' + thread + '\n' + message).getBytes(StandardCharsets.UTF_8));
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < 16; i++) b.append(String.format("%02x", hash[i]));
        return b.append(".json").toString();
    }
}
