package ps.reso.instaeclipse.utils.ghost;

import android.content.Context;
import android.widget.Toast;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import ps.reso.instaeclipse.R;
import ps.reso.instaeclipse.mods.ghost.HideChatsHook;
import ps.reso.instaeclipse.mods.media.FeedVideoDownloadHook;
import ps.reso.instaeclipse.mods.misc.CsvCells;
import ps.reso.instaeclipse.utils.core.ExportBrand;
import ps.reso.instaeclipse.utils.dialog.MenuWorker;
import ps.reso.instaeclipse.utils.feature.FeatureFlags;
import ps.reso.instaeclipse.utils.i18n.I18n;
import ps.reso.instaeclipse.utils.ipc.ExportDelegate;
import ps.reso.instaeclipse.utils.log.ModuleLog;
import ps.reso.instaeclipse.utils.toast.IeToast;

public final class UnsentExport {

    private static final String TAG = "(IE|UnsentExport) ";
    private static final String FOLDER = "exports/";
    static final String[] COLUMNS = {"chat", "sender", "time", "message", "type", "chat_id"};

    private UnsentExport() {}

    public static void allChats(Context context, String account) {
        try {
            List<UnsentLog.Entry> entries = UnsentLog.getAllDescribed(account);
            if (entries.isEmpty()) {
                toast(context, I18n.t(context, R.string.ig_dialog_unsent_empty), Toast.LENGTH_SHORT);
                return;
            }
            byte[] bytes = CsvCells.bytes(csv(rows(context, entries, chatNames(entries))));
            String file = fileName(System.currentTimeMillis());
            int count = entries.size();
            Runnable writeLocally = () -> writeLocally(context, bytes, file, count);
            if (!sendToCompanion(context, bytes, file, count, writeLocally)) writeLocally.run();
        } catch (Throwable t) {
            failed(context, t);
        }
    }

    static String fileName(long now) {
        return "unsent_messages_all_chats_" + new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(new Date(now))
                + ".csv";
    }

    public static Map<String, String> chatNames(List<UnsentLog.Entry> entries) {
        Map<String, String> names = new LinkedHashMap<>();
        List<String> unresolved = new ArrayList<>();
        for (UnsentLog.Entry entry : entries) {
            String thread = threadOf(entry);
            if (names.containsKey(thread)) continue;
            String title = thread.isEmpty() ? "" : HideChatsHook.knownThreadTitle(thread);
            names.put(thread, title == null ? "" : title);
            if (!thread.isEmpty() && names.get(thread).isEmpty()) unresolved.add(thread);
        }
        if (!unresolved.isEmpty()) names.putAll(ThreadNames.resolveAll(unresolved));
        for (UnsentLog.Entry entry : entries) {
            String thread = threadOf(entry);
            String captured = entry.sender == null ? "" : entry.sender.trim();
            if (names.get(thread).isEmpty() && !captured.isEmpty()) names.put(thread, captured);
        }
        return names;
    }

    public static String threadOf(UnsentLog.Entry entry) {
        return entry.thread == null ? "" : entry.thread;
    }

    static List<String[]> rows(Context context, List<UnsentLog.Entry> entries, Map<String, String> chats) {
        SimpleDateFormat time = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US);
        Map<String, List<String[]>> byChat = new LinkedHashMap<>();
        for (UnsentLog.Entry entry : entries) {
            String thread = threadOf(entry);
            String type = !UnsentDetailText.isDescription(entry) ? UnsentDetail.Kind.TEXT.id
                    : entry.detail != null ? entry.detail.kind.id : "unknown";
            byChat.computeIfAbsent(thread, ignored -> new ArrayList<>()).add(new String[]{
                    chats.get(thread), entry.by, time.format(new Date(entry.time)),
                    UnsentDetailText.body(context, entry), type, thread});
        }
        List<String[]> rows = new ArrayList<>(entries.size());
        for (List<String[]> chat : byChat.values()) rows.addAll(chat);
        return rows;
    }

    static String csv(List<String[]> rows) {
        StringBuilder csv = new StringBuilder();
        line(csv, COLUMNS);
        for (String[] row : rows) line(csv, row);
        return csv.append(ExportBrand.csvFooter()).toString();
    }

    private static void line(StringBuilder csv, String[] cells) {
        for (int i = 0; i < cells.length; i++) csv.append(i == 0 ? "" : ",").append(CsvCells.cell(cells[i]));
        csv.append('\n');
    }

    private static boolean sendToCompanion(Context context, byte[] bytes, String file, int count,
                                           Runnable writeLocally) {
        String folder = FeatureFlags.downloaderCustomUri;
        if (folder == null || folder.isEmpty()) return false;
        try {
            boolean sent = ExportDelegate.csv(context, folder, bytes, file, TAG, writeLocally);
            if (sent) ModuleLog.line(TAG + "Exporting " + count + " unsent messages through the InstaEclipse app to "
                    + FOLDER + file);
            else ModuleLog.detail(TAG + "The export is outside the InstaEclipse app's limits (" + bytes.length
                    + " bytes), saving it from Instagram");
            return sent;
        } catch (Throwable t) {
            ModuleLog.warn(TAG + "Could not hand the export to the InstaEclipse app, saving it from Instagram instead: " + t);
            return false;
        }
    }

    private static void writeLocally(Context context, byte[] bytes, String file, int count) {
        if (!CsvCells.write(() -> FeedVideoDownloadHook.openExport(context, file, "text/csv"), bytes,
                t -> failed(context, t))) return;
        ModuleLog.line(TAG + "Exported " + count + " unsent messages to " + FOLDER + file);
        toast(context, I18n.t(context, R.string.ig_dialog_unsent_exported, FOLDER + file), Toast.LENGTH_LONG);
    }

    private static void failed(Context context, Throwable t) {
        ModuleLog.error(TAG + "Could not export the unsent messages", t);
        toast(context, I18n.t(context, R.string.ig_unsent_export_failed, CsvCells.reason(t)), Toast.LENGTH_LONG);
    }

    private static void toast(Context context, String text, int length) {
        MenuWorker.post(() -> IeToast.makeText(context, text, length).show());
    }
}
