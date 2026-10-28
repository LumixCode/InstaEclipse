package ps.reso.instaeclipse.mods.media;

import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.Collection;
import java.util.Comparator;
import java.util.Date;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

import ps.reso.instaeclipse.R;
import ps.reso.instaeclipse.utils.download.DownloadEngine;
import ps.reso.instaeclipse.utils.ghost.EditHistoryStore;
import ps.reso.instaeclipse.utils.i18n.I18n;
import ps.reso.instaeclipse.utils.log.ModuleLog;
import ps.reso.instaeclipse.utils.toast.IeToast;

public final class DataBackup {

    private static final String TAG = "(IE|DataBackup) ";
    public static final String ACTION_RESTORE = "ps.reso.instaeclipse.ACTION_RESTORE_DATA";
    private static final String MANIFEST = "instaeclipse_data.json";
    private static final String UNSENT = "ie_unsent_log.json";
    private static final String HIDDEN = "ie_hidden_threads.json";
    private static final String NAMES = "ie_thread_names.json";
    private static final String EDITS = "ie_edit_history.json";
    private static final String READ_RECEIPTS = "ie_read_receipt_whitelist.json";
    private static final String SET_ASIDE = ".corrupt";
    private static final int MAX_UNSENT = 1000; // UnsentLog keeps the same number of entries
    private static final long MAX_BYTES = 64L << 20;
    static final int PIECE_CHARS = 120_000;
    static final long TIMEOUT_MS = 60_000;
    private static final String FAILED = "Backup could not be restored";
    private static volatile boolean restoring;

    private DataBackup() {}

    public static boolean restoring() {
        return restoring;
    }

    static void setRestoring(boolean on) {
        restoring = on;
    }

    static boolean included(String name) {
        if (name == null || name.contains("/") || name.contains("\\")) return false;
        if (name.endsWith(".tmp") || name.endsWith(".bak") || name.endsWith(".new")) return false;
        String base = name.endsWith(SET_ASIDE) ? name.substring(0, name.length() - SET_ASIDE.length()) : name;
        return base.equals(UNSENT) || base.equals(HIDDEN) || base.equals(NAMES) || base.equals(EDITS)
                || base.equals(READ_RECEIPTS) || base.equals("ie_post_counts.json")
                || base.startsWith("ie_watch") || base.startsWith("ie_followers_history_");
    }

    // ----- backup -----

    public static void export(Activity a) {
        Context app = a.getApplicationContext();
        new Thread(() -> {
            File zip = null;
            try {
                File[] files = app.getFilesDir().listFiles();
                int count = 0;
                zip = File.createTempFile("ie_data_", ".zip", app.getCacheDir());
                try (ZipOutputStream out = new ZipOutputStream(new FileOutputStream(zip))) {
                    if (files != null) for (File f : files) {
                        if (!f.isFile() || !included(f.getName())) continue;
                        out.putNextEntry(new ZipEntry(f.getName()));
                        out.write(java.nio.file.Files.readAllBytes(f.toPath()));
                        out.closeEntry();
                        count++;
                    }
                    JSONObject m = new JSONObject();
                    m.put("app", "InstaEclipse");
                    m.put("type", "data");
                    m.put("version", 1);
                    m.put("created", System.currentTimeMillis());
                    out.putNextEntry(new ZipEntry(MANIFEST));
                    out.write(m.toString().getBytes(StandardCharsets.UTF_8));
                    out.closeEntry();
                }
                String name = "instaeclipse_data_" + new SimpleDateFormat("yyyyMMdd_HHmm", Locale.US).format(new Date()) + ".zip";
                DownloadPublisher.Saved saved = DownloadPublisher.save(app, zip, name, "application/zip", "", "", false,
                        new DownloadEngine.Cancellation());
                String where = saved.displayPath + "/" + saved.name;
                ModuleLog.line(TAG + "Backup saved to " + where + " (" + count + " files, " + zip.length() + " bytes)");
                toast(app, I18n.t(app, R.string.ig_data_backup_saved, where), true);
            } catch (Throwable t) {
                ModuleLog.error(TAG + "Could not save the backup", t);
                toast(app, I18n.t(app, R.string.ig_data_backup_failed), true);
            } finally {
                if (zip != null) zip.delete();
            }
        }, "IE-DataBackup").start();
    }

    /** Opens the companion's file picker, which sends the chosen backup back to Instagram. */
    public static void pickAndRestore(Activity a) {
        Intent i = new Intent();
        i.setComponent(new ComponentName("ps.reso.instaeclipse", "ps.reso.instaeclipse.mods.devops.config.DataImportActivity"));
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        i.putExtra("target_package", a.getPackageName());
        ps.reso.instaeclipse.utils.ipc.CompanionBridge.launchActivity(a, i);
    }

    // ----- restore -----

    private static final Object LOCK = new Object();
    private static final Runnable EXPIRE = DataBackup::expire;
    private static Pieces pending;
    private static Intent pendingRequest;
    private static String failedId;
    private static Handler main;

    public static void registerRestoreReceiver(Context context) {
        BroadcastReceiver receiver = new BroadcastReceiver() {
            @Override public void onReceive(Context ctx, Intent intent) {
                received(ctx.getApplicationContext(), intent);
            }
        };
        try {
            ps.reso.instaeclipse.utils.core.IpcSecurity.registerCompanionOnlyReceiver(context, receiver, new IntentFilter(ACTION_RESTORE));
        } catch (Throwable t) {
            ModuleLog.error(TAG + "Restoring a backup will not work: could not listen for the backup file: " + t.getMessage());
        }
    }

    private static void received(Context app, Intent intent) {
        String id = intent.getStringExtra("id");
        String piece = intent.getStringExtra("piece");
        int index = intent.getIntExtra("index", -1), total = intent.getIntExtra("total", 0);
        if (id == null || piece == null || total <= 0 || total > 2000 || index < 0 || index >= total) return;
        boolean last = index == total - 1;
        Pieces done = null;
        Intent request;
        String failure = null;
        boolean failedBefore = false;
        synchronized (LOCK) {
            if (id.equals(failedId)) {
                if (!last) return;
                failedId = null;
                request = intent;
                failedBefore = true;
            } else {
                long now = SystemClock.uptimeMillis();
                if (pending != null && (!pending.id.equals(id) || pending.expired(now))) {
                    pending = null;
                    pendingRequest = null;
                }
                if (pending == null) {
                    pending = new Pieces(id, total, MAX_BYTES);
                    failedId = null;
                }
                if (pending.total != total) return;
                if (last) pendingRequest = intent;
                try {
                    pending.add(index, piece, now);
                } catch (IllegalArgumentException e) {
                    failure = e.getMessage();
                }
                if (failure == null && !pending.complete()) {
                    expireLater();
                    return;
                }
                if (failure == null) done = pending;
                request = pendingRequest;
                if (failure != null && request == null) failedId = id;
                pending = null;
                pendingRequest = null;
            }
        }
        if (failedBefore) {
            confirm(request, FAILED);
            return;
        }
        if (failure != null) {
            ModuleLog.error(TAG + "Could not restore the backup: " + failure);
            confirm(request, FAILED);
            toast(app, I18n.t(app, R.string.ig_data_restore_failed), true);
            return;
        }
        Pieces all = done;
        new Thread(() -> restore(app, all, request), "IE-DataRestore").start();
    }

    private static void expireLater() {
        if (main == null) main = new Handler(Looper.getMainLooper());
        main.removeCallbacks(EXPIRE);
        main.postDelayed(EXPIRE, TIMEOUT_MS + 1000);
    }

    private static void expire() {
        Intent request;
        synchronized (LOCK) {
            if (pending == null || !pending.expired(SystemClock.uptimeMillis())) return;
            ModuleLog.warn(TAG + "Stopped waiting for the rest of a backup: " + pending.received() + " of "
                    + pending.total + " pieces arrived");
            request = pendingRequest;
            pending = null;
            pendingRequest = null;
        }
        confirm(request, FAILED);
    }

    static final class Pieces {
        final String id;
        final int total;
        private final long maxBytes;
        private final String[] waiting;
        private int next, received, size;
        private String carry = "";
        private byte[] bytes;
        private long last;

        Pieces(String id, int total, long maxBytes) {
            this.id = id;
            this.total = total;
            this.maxBytes = maxBytes;
            waiting = new String[total];
            bytes = new byte[(int) Math.min(maxBytes, (long) total * (PIECE_CHARS / 4 * 3))];
        }

        boolean add(int index, String piece, long now) {
            if (index < 0 || index >= total) throw new IllegalArgumentException("piece " + index + " of " + total);
            last = now;
            if (index < next || waiting[index] != null) return false;
            waiting[index] = piece;
            received++;
            while (next < total && waiting[next] != null) {
                String chars = waiting[next];
                waiting[next] = null;
                next++;
                decode(chars, next == total);
            }
            return true;
        }

        private void decode(String chars, boolean end) {
            String text = carry.isEmpty() ? chars : carry + chars;
            int usable = end ? text.length() : text.length() - text.length() % 4;
            carry = text.substring(usable);
            if (usable == 0) return;
            byte[] out = Base64.getDecoder().decode(usable == text.length() ? text : text.substring(0, usable));
            if (size + (long) out.length > maxBytes) throw new IllegalArgumentException("backup too large");
            if (size + out.length > bytes.length)
                bytes = Arrays.copyOf(bytes, (int) Math.min(maxBytes, Math.max(size + (long) out.length, bytes.length * 2L)));
            System.arraycopy(out, 0, bytes, size, out.length);
            size += out.length;
        }

        boolean complete() {
            return next == total;
        }

        int received() {
            return received;
        }

        boolean expired(long now) {
            return now - last >= TIMEOUT_MS;
        }

        InputStream stream() {
            return new ByteArrayInputStream(bytes, 0, size);
        }
    }

    private static void restore(Context app, Pieces pieces, Intent request) {
        try {
            Map<String, byte[]> files = unzip(pieces.stream());
            if (!isDataBackup(files)) throw new IllegalArgumentException("not an InstaEclipse data backup");
            setRestoring(true);
            int restored = put(app.getFilesDir(), files);
            ModuleLog.line(TAG + "Backup restored (" + restored + " files); restarting Instagram");
            confirm(request, null);
            toast(app, I18n.t(app, R.string.ig_data_restore_done, restored), false);
            new Handler(Looper.getMainLooper()).postDelayed(() -> ps.reso.instaeclipse.utils.dialog.DialogUtils.restart(app, false), 1800);
        } catch (Throwable t) {
            setRestoring(false);
            ModuleLog.error(TAG + "Could not restore the backup: " + t.getClass().getSimpleName() + ": " + t.getMessage());
            confirm(request, FAILED);
            toast(app, I18n.t(app, R.string.ig_data_restore_failed), true);
        }
    }

    static boolean isDataBackup(Map<String, byte[]> files) throws JSONException {
        byte[] manifest = files.get(MANIFEST);
        return manifest != null && "data".equals(new JSONObject(new String(manifest, StandardCharsets.UTF_8)).optString("type"));
    }

    private static int put(File dir, Map<String, byte[]> files) throws Exception {
        int restored = 0;
        for (Map.Entry<String, byte[]> e : files.entrySet()) {
            String name = e.getKey();
            if (!included(name)) continue;
            File target = new File(dir, name);
            if (name.endsWith(SET_ASIDE) && target.exists()) {
                ModuleLog.detail(TAG + "kept this phone's " + name + " over the backup's");
                continue;
            }
            byte[] data;
            try {
                data = merged(name, target, e.getValue());
            } catch (JSONException damaged) {
                ModuleLog.warn(TAG + "Skipped the backup's " + name + ": it could not be read: " + damaged.getMessage());
                continue;
            }
            if (data == null) {
                ModuleLog.warn(TAG + "Kept this phone's " + name + " instead of the backup copy: the phone's file could not be read");
                continue;
            }
            write(target, data);
            restored++;
        }
        return restored;
    }

    private static byte[] merged(String name, File current, byte[] backup) throws Exception {
        if (name.equals(UNSENT)) return mergeUnsent(current, backup);
        if (name.equals(HIDDEN) || name.equals(NAMES) || name.equals(READ_RECEIPTS)) return mergeKeepCurrent(current, backup);
        if (name.equals(EDITS)) return mergeEditHistory(current, backup);
        if (!DataRestoreMerge.merges(name)) return backup;
        byte[] onPhone;
        try { onPhone = current.exists() ? java.nio.file.Files.readAllBytes(current.toPath()) : null; }
        catch (java.io.IOException unreadable) { return null; }
        return DataRestoreMerge.merge(name, onPhone, backup);
    }

    private static void confirm(Intent request, String error) {
        if (request == null) return;
        Intent reply = new Intent();
        ps.reso.instaeclipse.utils.core.IpcSecurity.echoNonce(request, reply);
        if (error != null) reply.putExtra("error", error);
        ps.reso.instaeclipse.utils.ipc.ModuleReplies.reply(request, reply.getExtras());
    }

    static Map<String, byte[]> unzip(InputStream zip) throws Exception {
        Map<String, byte[]> out = new LinkedHashMap<>();
        long total = 0;
        try (ZipInputStream in = new ZipInputStream(zip)) {
            ZipEntry e;
            byte[] buf = new byte[16384];
            while ((e = in.getNextEntry()) != null) {
                if (e.isDirectory()) continue;
                ByteArrayOutputStream bytes = new ByteArrayOutputStream();
                int n;
                while ((n = in.read(buf)) > 0) {
                    total += n;
                    if (total > MAX_BYTES) throw new IllegalArgumentException("backup too large");
                    bytes.write(buf, 0, n);
                }
                out.put(e.getName(), bytes.toByteArray());
            }
        }
        return out;
    }

    static byte[] mergeEditHistory(File current, byte[] backup) throws Exception {
        JSONObject fromBackup = new JSONObject(new String(backup, StandardCharsets.UTF_8));
        JSONObject onPhone = null;
        if (current.exists()) {
            byte[] bytes;
            try { bytes = java.nio.file.Files.readAllBytes(current.toPath()); }
            catch (java.io.IOException unreadable) { return null; }
            try { onPhone = new JSONObject(new String(bytes, StandardCharsets.UTF_8)); }
            catch (JSONException damaged) { ModuleLog.detail(TAG + "phone's edit history unreadable, using the backup's: " + damaged.getMessage()); }
        }
        return EditHistoryStore.merged(onPhone, fromBackup).getBytes(StandardCharsets.UTF_8);
    }

    /**
     * Deleted messages from the backup and the phone, each once, oldest first, newest kept (rule:
     * {@link UnsentMerge}). Null when the phone's log exists but can't be read: it stays as is.
     */
    static byte[] mergeUnsent(File current, byte[] backup) throws Exception {
        List<UnsentMerge.Row> fromBackup = unsentRows(new JSONArray(new String(backup, StandardCharsets.UTF_8)));
        List<UnsentMerge.Row> onPhone = new ArrayList<>();
        if (current.exists()) {
            byte[] bytes;
            try { bytes = new android.util.AtomicFile(current).readFully(); }
            catch (java.io.IOException unreadable) { return null; }
            // A damaged log can't be loaded by UnsentLog either: the backup replaces it.
            try { onPhone = unsentRows(new JSONArray(new String(bytes, StandardCharsets.UTF_8))); }
            catch (org.json.JSONException damaged) { ModuleLog.detail(TAG + "phone's unsent log unreadable, using the backup's: " + damaged.getMessage()); }
        }
        JSONArray out = new JSONArray();
        for (UnsentMerge.Row row : UnsentMerge.merge(onPhone, fromBackup, MAX_UNSENT)) {
            JSONObject o = (JSONObject) row.source;
            if (!row.ids.isEmpty()) o.put("ids", new JSONArray(new ArrayList<>(row.ids)));
            if (row.logOnly) o.put("logOnly", true);
            out.put(o);
        }
        return out.toString().getBytes(StandardCharsets.UTF_8);
    }

    /** UnsentLog's JSON entries (t, a, th, id, ids, m, body, cleared, logOnly) as merge rows. */
    private static List<UnsentMerge.Row> unsentRows(JSONArray arr) {
        List<UnsentMerge.Row> rows = new ArrayList<>();
        for (int i = 0; i < arr.length(); i++) {
            JSONObject o = arr.optJSONObject(i);
            if (o == null) continue;
            List<String> aliases = new ArrayList<>();
            JSONArray ids = o.optJSONArray("ids");
            if (ids != null) for (int j = 0; j < ids.length(); j++) aliases.add(ids.optString(j));
            boolean cleared = o.optBoolean("cleared", false);
            String text = o.optString("m", "");
            // UnsentLog reads a missing "body" as true (entries from before the flag existed).
            boolean hasBody = !cleared && o.optBoolean("body", true) && !text.isEmpty();
            rows.add(new UnsentMerge.Row(o, o.optLong("t"), o.optString("a", ""), o.optString("th", ""),
                    o.optString("id", ""), text, cleared, hasBody, o.optBoolean("logOnly", false), aliases));
        }
        return rows;
    }

    /**
     * A {thread id: text} store (hidden chats, chat names) from the backup added to the phone's,
     * whose entries win: a restore never unhides or renames a chat. Null when the phone's file
     * exists but can't be read: it stays as is.
     */
    private static byte[] mergeKeepCurrent(File current, byte[] backup) throws Exception {
        Map<String, Object> onPhone = new LinkedHashMap<>();
        if (current.exists()) {
            byte[] bytes;
            try { bytes = java.nio.file.Files.readAllBytes(current.toPath()); }
            catch (java.io.IOException unreadable) { return null; }
            // A damaged file loads as empty in HiddenThreads/ThreadNames too: nothing to keep.
            try { onPhone = toMap(new JSONObject(new String(bytes, StandardCharsets.UTF_8))); }
            catch (org.json.JSONException damaged) { ModuleLog.detail(TAG + "phone's " + current.getName() + " unreadable, using the backup's: " + damaged.getMessage()); }
        }
        Map<String, Object> merged = unionKeepCurrent(toMap(new JSONObject(new String(backup, StandardCharsets.UTF_8))), onPhone);
        JSONObject out = new JSONObject();
        for (Map.Entry<String, Object> e : merged.entrySet()) out.put(e.getKey(), e.getValue());
        return out.toString().getBytes(StandardCharsets.UTF_8);
    }

    private static Map<String, Object> toMap(JSONObject o) {
        Map<String, Object> map = new LinkedHashMap<>();
        for (Iterator<String> it = o.keys(); it.hasNext(); ) {
            String k = it.next();
            map.put(k, o.opt(k));
        }
        return map;
    }

    /**
     * The backup's ids the phone doesn't have (older, so listed first), then every phone entry with
     * its own value.
     */
    static Map<String, Object> unionKeepCurrent(Map<String, ?> backup, Map<String, ?> current) {
        Map<String, Object> out = new LinkedHashMap<>();
        for (Map.Entry<String, ?> e : backup.entrySet())
            if (!current.containsKey(e.getKey())) out.put(e.getKey(), e.getValue());
        out.putAll(current);
        return out;
    }

    /**
     * The unsent-log merge rule, free of org.json so plain unit tests can run it. One row per
     * message: rows with a message id match on account + thread + any shared id (UnsentLog keeps a
     * message's server and client ids as aliases); id-less rows from older builds match on time +
     * thread + text. On a clash a cleared row wins (a restore never brings back what was cleared),
     * then a row with the message text, then the phone's own row. The ids of all matched rows are
     * kept together and a log-only flag on any of them stays.
     */
    static final class UnsentMerge {

        static final class Row {
            final Object source;
            final long time;
            final String account, thread, id, text;
            final boolean cleared, hasBody, logOnly;
            /** The message id first, then its aliases. */
            final Set<String> ids = new LinkedHashSet<>();

            Row(Object source, long time, String account, String thread, String id, String text,
                boolean cleared, boolean hasBody, boolean logOnly, Collection<String> aliases) {
                this.source = source;
                this.time = time;
                this.account = account == null ? "" : account;
                this.thread = thread == null ? "" : thread;
                this.id = id == null ? "" : id;
                this.text = text == null ? "" : text;
                this.cleared = cleared;
                this.hasBody = !cleared && hasBody;
                this.logOnly = !cleared && logOnly;
                if (!this.id.isEmpty()) ids.add(this.id);
                if (aliases != null) for (String a : aliases) if (a != null && !a.isEmpty()) ids.add(a);
            }

            int rank() { return cleared ? 2 : hasBody ? 1 : 0; }

            /** The keys this row can be found under. */
            List<String> keys() {
                if (id.isEmpty()) return Arrays.asList("t|" + time + "|" + thread + "|" + text);
                List<String> keys = new ArrayList<>();
                for (String alias : ids) keys.add(idKey(alias));
                return keys;
            }

            String idKey(String alias) { return "i|" + account + "|" + thread + "|" + alias; }
        }

        private static final class Group {
            Row best;
            final Set<String> ids = new LinkedHashSet<>();
            boolean logOnly;

            void add(Row row) {
                // Strictly better only: on a tie the row seen first (the phone's) stays.
                if (best == null || row.rank() > best.rank()) best = row;
                ids.addAll(row.ids);
                logOnly |= row.logOnly;
            }

            Row result() {
                Set<String> all = new LinkedHashSet<>(best.ids);
                all.addAll(ids);
                return new Row(best.source, best.time, best.account, best.thread, best.id, best.text,
                        best.cleared, best.hasBody, logOnly, all);
            }
        }

        private UnsentMerge() {}

        /** Phone rows first so they win ties; returns oldest first, at most {@code cap} (the newest). */
        static List<Row> merge(List<Row> phone, List<Row> backup, int cap) {
            Map<String, Group> byKey = new HashMap<>();
            List<Group> groups = new ArrayList<>();
            for (List<Row> source : Arrays.asList(phone, backup)) {
                if (source == null) continue;
                for (Row row : source) {
                    Group group = find(byKey, row);
                    if (group == null) {
                        group = new Group();
                        groups.add(group);
                    }
                    group.add(row);
                    for (String key : row.keys()) byKey.putIfAbsent(key, group);
                }
            }
            List<Row> out = new ArrayList<>();
            for (Group group : groups) out.add(group.result());
            out.sort(Comparator.comparingLong((Row r) -> r.time));
            if (out.size() > cap) out = new ArrayList<>(out.subList(out.size() - cap, out.size()));
            return out;
        }

        /** The row's own id decides; otherwise one alias match. Aliases that point at two
         *  different messages join neither, as in UnsentMessageIndex. */
        private static Group find(Map<String, Group> byKey, Row row) {
            if (row.id.isEmpty()) return byKey.get(row.keys().get(0));
            Group own = byKey.get(row.idKey(row.id));
            if (own != null) return own;
            Group match = null;
            for (String alias : row.ids) {
                Group g = byKey.get(row.idKey(alias));
                if (g == null) continue;
                if (match != null && match != g) return null;
                match = g;
            }
            return match;
        }
    }

    private static void write(File f, byte[] data) throws Exception {
        android.util.AtomicFile atomic = new android.util.AtomicFile(f);
        FileOutputStream out = atomic.startWrite();
        try {
            out.write(data);
            atomic.finishWrite(out);
        } catch (Exception e) {
            atomic.failWrite(out);
            throw e;
        }
    }

    private static void toast(Context ctx, String text, boolean longer) {
        new Handler(Looper.getMainLooper()).post(() ->
                IeToast.makeText(ctx, text, longer ? Toast.LENGTH_LONG : Toast.LENGTH_SHORT).show());
    }

    /** For the companion: the zip's Base64 split into broadcast-sized pieces. */
    public static List<String> pieces(InputStream in) throws Exception {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        byte[] buf = new byte[16384];
        int n;
        long total = 0;
        while ((n = in.read(buf)) > 0) {
            total += n;
            if (total > MAX_BYTES) throw new ps.reso.instaeclipse.utils.ipc.BrokerPayload.TooLarge("backup too large");
            bytes.write(buf, 0, n);
        }
        String b64 = Base64.getEncoder().encodeToString(bytes.toByteArray());
        List<String> out = new ArrayList<>();
        for (int i = 0; i < b64.length(); i += PIECE_CHARS) out.add(b64.substring(i, Math.min(b64.length(), i + PIECE_CHARS)));
        return out;
    }
}
