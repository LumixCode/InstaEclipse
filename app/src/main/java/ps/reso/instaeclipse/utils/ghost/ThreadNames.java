package ps.reso.instaeclipse.utils.ghost;

import ps.reso.instaeclipse.xposedcompat.AndroidAppHelper;
import android.content.Context;

import org.json.JSONObject;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import ps.reso.instaeclipse.mods.ui.UIHookManager;
import ps.reso.instaeclipse.utils.log.ModuleLog;

public class ThreadNames {

    static final int MAX_IDS_PER_QUERY = 500;
    private static final IdTitleStore store = new IdTitleStore("ie_thread_names.json",
            failure -> ModuleLog.detail("(IE|ThreadNames) load failed: " + failure),
            failure -> ModuleLog.detail("(IE|ThreadNames) persist failed: " + failure),
            ThreadNames::cleanupOnce);
    private static final Set<String> notFound = new HashSet<>();

    private static boolean cleanupOnce(Map<String, String> names) {
        try {
            Context ctx = AndroidAppHelper.currentApplication();
            if (ctx == null) return false;
            File marker = new File(ctx.getFilesDir(), "ie_thread_names.v2");
            if (marker.exists()) return false;
            Map<String, Integer> counts = new HashMap<>();
            for (String v : names.values()) counts.merge(v, 1, Integer::sum);
            int before = names.size();
            names.values().removeIf(v -> counts.getOrDefault(v, 0) > 1);
            marker.createNewFile();
            ModuleLog.detail("(IE|ThreadNames) cleanup dropped " + (before - names.size()) + " ambiguous name(s)");
            return names.size() != before;
        } catch (Throwable t) {
            ModuleLog.detail("(IE|ThreadNames) cleanup failed: " + t.getMessage());
            return false;
        }
    }

    public static void put(String threadId, String name) {
        if (threadId == null || threadId.isEmpty() || name == null || name.trim().isEmpty()) return;
        store.put(threadId, name.trim());
    }

    public static String get(String threadId) {
        return threadId == null ? "" : store.get(threadId);
    }

    public static String resolve(String threadId) {
        if (threadId == null || threadId.isEmpty()) return "";
        String name = resolveAll(Collections.singletonList(threadId)).get(threadId);
        return name == null ? "" : name;
    }

    public static Map<String, String> resolveAll(Collection<String> threadIds) {
        Map<String, String> found = new LinkedHashMap<>();
        Set<String> missing = new LinkedHashSet<>();
        for (String id : threadIds) {
            if (id == null || id.isEmpty() || found.containsKey(id) || missing.contains(id)) continue;
            String known = store.get(id);
            if (!known.isEmpty()) found.put(id, known);
            else missing.add(id);
        }
        if (missing.isEmpty()) return found;
        String account = UnsentIdentity.account(UIHookManager.topActivity());
        synchronized (notFound) {
            missing.removeIf(id -> notFound.contains(missKey(account, id)));
        }
        if (missing.isEmpty()) return found;
        Map<String, String> looked = lookupDb(account, new ArrayList<>(missing));
        synchronized (notFound) {
            for (String id : missing) if (!looked.containsKey(id)) notFound.add(missKey(account, id));
        }
        store.putAll(looked);
        found.putAll(looked);
        return found;
    }

    private static String missKey(String account, String threadId) {
        return (account == null ? "" : account) + "/" + threadId;
    }

    static String lookupSql(boolean scoped, int count) {
        String[] marks = new String[count];
        Arrays.fill(marks, "?");
        return "SELECT thread_id, thread_info FROM threads WHERE thread_id IN (" + String.join(", ", marks) + ")"
                + (scoped ? " AND user_id = ?" : "");
    }

    static String[] lookupArgs(String account, List<String> threadIds) {
        List<String> args = new ArrayList<>(threadIds);
        if (account != null) args.add(account);
        return args.toArray(new String[0]);
    }

    static List<List<String>> chunks(List<String> threadIds) {
        List<List<String>> out = new ArrayList<>();
        for (int i = 0; i < threadIds.size(); i += MAX_IDS_PER_QUERY) {
            out.add(threadIds.subList(i, Math.min(threadIds.size(), i + MAX_IDS_PER_QUERY)));
        }
        return out;
    }

    static Map<String, String> pick(List<String[]> rows, boolean scoped) {
        Map<String, String> names = new LinkedHashMap<>();
        Set<String> ambiguous = new HashSet<>();
        for (String[] row : rows) {
            String id = row[0];
            String name = row[1] == null ? "" : row[1].trim();
            if (id == null || name.isEmpty()) continue;
            String first = names.putIfAbsent(id, name);
            if (!scoped && first != null && !first.equals(name)) ambiguous.add(id);
        }
        names.keySet().removeAll(ambiguous);
        return names;
    }

    private static Map<String, String> lookupDb(String account, List<String> threadIds) {
        Context ctx = AndroidAppHelper.currentApplication();
        if (ctx == null) return new HashMap<>();
        File db = ctx.getDatabasePath("direct.db");
        if (!db.exists()) return new HashMap<>();
        List<String[]> rows = new ArrayList<>();
        try (android.database.sqlite.SQLiteDatabase d = android.database.sqlite.SQLiteDatabase.openDatabase(
                db.getPath(), null, android.database.sqlite.SQLiteDatabase.OPEN_READONLY)) {
            for (List<String> chunk : chunks(threadIds)) {
                try (android.database.Cursor c = d.rawQuery(lookupSql(account != null, chunk.size()),
                        lookupArgs(account, chunk))) {
                    while (c.moveToNext()) {
                        try {
                            String raw = c.getType(1) == android.database.Cursor.FIELD_TYPE_BLOB
                                    ? new String(c.getBlob(1), StandardCharsets.UTF_8) : c.getString(1);
                            rows.add(new String[]{c.getString(0), nameOf(new JSONObject(raw))});
                        } catch (Throwable t) {
                            ModuleLog.detail("(IE|ThreadNames) db lookup failed: " + t.getMessage());
                        }
                    }
                }
            }
        } catch (Throwable t) {
            ModuleLog.detail("(IE|ThreadNames) db lookup failed: " + t.getMessage());
        }
        return pick(rows, account != null);
    }

    private static String nameOf(JSONObject info) {
        String title = info.optString("thread_title", "").trim();
        if (!title.isEmpty()) return title;
        List<String> names = new ArrayList<>();
        org.json.JSONArray users = info.optJSONArray("recipients");
        if (users != null) {
            for (int i = 0; i < users.length(); i++) {
                JSONObject u = users.optJSONObject(i);
                if (u == null) continue;
                String n = u.optString("full_name", "").trim();
                if (n.isEmpty()) n = u.optString("username", "").trim();
                if (!n.isEmpty()) names.add(n);
            }
        }
        return names.isEmpty() ? null : String.join(", ", names);
    }
}
