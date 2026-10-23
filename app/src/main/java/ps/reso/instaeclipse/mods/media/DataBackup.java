package ps.reso.instaeclipse.mods.media;

import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Handler;
import android.os.Looper;
import android.util.Base64;
import android.widget.Toast;

import org.json.JSONArray;
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
import ps.reso.instaeclipse.utils.i18n.I18n;
import ps.reso.instaeclipse.utils.log.ModuleLog;
import ps.reso.instaeclipse.utils.toast.IeToast;

/**
 * Back up / restore the module's own data kept in Instagram's private files, so it survives a
 * reinstall: deleted (unsent) messages, analyzed profiles (watchlist), followers history, hidden
 * chats and chat names.
 *
 * <p>Backup: one zip saved from Instagram's process to Download/InstaEclipse (no size limit).
 * Restore: the companion's picker (DataImportActivity) reads the zip and sends it here in Base64
 * pieces, since one broadcast can't carry more than about a megabyte. Deleted messages, hidden
 * chats and chat names are merged with what's on the phone, so a restore never brings back a
 * cleared message or unhides a chat; the other files are put back as they were. Instagram then
 * restarts so every store reloads from disk.
 */
public final class DataBackup {

    private static final String TAG = "(IE|DataBackup) ";
    public static final String ACTION_RESTORE = "ps.reso.instaeclipse.ACTION_RESTORE_DATA";
    private static final String MANIFEST = "instaeclipse_data.json";
    private static final String UNSENT = "ie_unsent_log.json";
    private static final String HIDDEN = "ie_hidden_threads.json";
    private static final String NAMES = "ie_thread_names.json";
    private static final int MAX_UNSENT = 1000; // UnsentLog keeps the same number of entries
    private static final long MAX_BYTES = 64L << 20;

    private DataBackup() {}

    /**
     * Files that belong in a backup: our stores only (not caches or logs). Top-level files only, so
     * the saved copies of unsent messages' media (ie_unsent_messages/) stay out.
     */
    private static boolean included(String name) {
        return name.equals(UNSENT) || name.equals(HIDDEN) || name.equals(NAMES) || name.equals("ie_post_counts.json")
                || name.startsWith("ie_watch") || name.startsWith("ie_followers_history_");
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
                DownloadPublisher.save(app, zip, name, "application/zip", "", "", false, new DownloadEngine.Cancellation());
                ModuleLog.line(TAG + "Backup saved to Download/InstaEclipse/" + name + " (" + count + " files, " + zip.length() + " bytes)");
                toast(app, I18n.t(app, R.string.ig_data_backup_saved, "Download/InstaEclipse/" + name), true);
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

    private static final Map<String, String[]> PENDING = new HashMap<>();

    public static void registerRestoreReceiver(Context context) {
        BroadcastReceiver receiver = new BroadcastReceiver() {
            @Override public void onReceive(Context ctx, Intent intent) {
                String id = intent.getStringExtra("id");
                String piece = intent.getStringExtra("piece");
                int index = intent.getIntExtra("index", -1), total = intent.getIntExtra("total", 0);
                if (id == null || piece == null || total <= 0 || total > 2000 || index < 0 || index >= total) return;
                String[] parts;
                synchronized (PENDING) {
                    parts = PENDING.get(id);
                    if (parts == null) {
                        PENDING.clear(); // one restore at a time
                        parts = new String[total];
                        PENDING.put(id, parts);
                    }
                    if (parts.length != total) return;
                    parts[index] = piece;
                    for (String p : parts) if (p == null) return;
                    PENDING.remove(id);
                }
                final String[] all = parts;
                Context app = ctx.getApplicationContext();
                new Thread(() -> restore(app, all), "IE-DataRestore").start();
            }
        };
        try {
            ps.reso.instaeclipse.utils.core.IpcSecurity.registerCompanionOnlyReceiver(context, receiver, new IntentFilter(ACTION_RESTORE));
        } catch (Throwable t) {
            ModuleLog.error(TAG + "Restoring a backup will not work: could not listen for the backup file: " + t.getMessage());
        }
    }

    private static void restore(Context app, String[] parts) {
        try {
            StringBuilder b64 = new StringBuilder();
            for (String p : parts) b64.append(p);
            byte[] zip = Base64.decode(b64.toString(), Base64.NO_WRAP);
            Map<String, byte[]> files = unzip(zip);
            if (!files.containsKey(MANIFEST) || !"data".equals(new JSONObject(new String(files.get(MANIFEST), StandardCharsets.UTF_8)).optString("type")))
                throw new IllegalArgumentException("not an InstaEclipse data backup");
            File dir = app.getFilesDir();
            int restored = 0;
            for (Map.Entry<String, byte[]> e : files.entrySet()) {
                String name = e.getKey();
                if (!included(name) || name.contains("/") || name.contains("\\")) continue;
                File target = new File(dir, name);
                byte[] data = name.equals(UNSENT) ? mergeUnsent(target, e.getValue())
                        : name.equals(HIDDEN) || name.equals(NAMES) ? mergeKeepCurrent(target, e.getValue())
                        : e.getValue();
                if (data == null) {
                    ModuleLog.warn(TAG + "Kept this phone's " + name + " instead of the backup copy: the phone's file could not be read");
                    continue;
                }
                write(target, data);
                restored++;
            }
            ModuleLog.line(TAG + "Backup restored (" + restored + " files); restarting Instagram");
            toast(app, I18n.t(app, R.string.ig_data_restore_done, restored), false);
            // Every store keeps a copy in memory: restart so they all read the restored files.
            new Handler(Looper.getMainLooper()).postDelayed(() -> restart(app), 1800);
        } catch (Throwable t) {
            ModuleLog.error(TAG + "Could not restore the backup: " + t.getClass().getSimpleName() + ": " + t.getMessage());
            toast(app, I18n.t(app, R.string.ig_data_restore_failed), true);
        }
    }

    private static Map<String, byte[]> unzip(byte[] zip) throws Exception {
        Map<String, byte[]> out = new LinkedHashMap<>();
        long total = 0;
        try (ZipInputStream in = new ZipInputStream(new ByteArrayInputStream(zip))) {
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

    private static void restart(Context app) {
        Intent launch = app.getPackageManager().getLaunchIntentForPackage(app.getPackageName());
        if (launch != null) {
            launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
            android.app.PendingIntent pi = android.app.PendingIntent.getActivity(app, 0x1EB, launch,
                    android.app.PendingIntent.FLAG_IMMUTABLE | android.app.PendingIntent.FLAG_CANCEL_CURRENT);
            android.app.AlarmManager am = (android.app.AlarmManager) app.getSystemService(Context.ALARM_SERVICE);
            if (am != null) am.set(android.app.AlarmManager.RTC, System.currentTimeMillis() + 400, pi);
        }
        android.os.Process.killProcess(android.os.Process.myPid());
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
            if (total > MAX_BYTES) throw new IllegalArgumentException("backup too large");
            bytes.write(buf, 0, n);
        }
        String b64 = Base64.encodeToString(bytes.toByteArray(), Base64.NO_WRAP);
        List<String> out = new ArrayList<>();
        int size = 120_000;
        for (int i = 0; i < b64.length(); i += size) out.add(b64.substring(i, Math.min(b64.length(), i + size)));
        return out;
    }
}
