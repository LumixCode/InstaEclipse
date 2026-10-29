package ps.reso.instaeclipse.mods.ghost;

import java.util.ArrayList;
import java.util.List;

import ps.reso.instaeclipse.utils.ghost.EditHistoryStore;

final class EditHistoryTimeline {

    enum Kind { ORIGINAL, EARLIER, CURRENT }

    static final class Row {
        final Kind kind;
        final String text;
        final long time;
        final boolean noticed;

        Row(Kind kind, String text, long time, boolean noticed) {
            this.kind = kind; this.text = text; this.time = time; this.noticed = noticed;
        }
    }

    private EditHistoryTimeline() {}

    static List<Row> rows(List<EditHistoryStore.Version> versions, String current) {
        List<Row> rows = new ArrayList<>();
        int n = versions == null ? 0 : versions.size();
        String stored = EditHistoryStore.cut(current);
        boolean lastIsCurrent = n > 0 && (current == null || versions.get(n - 1).text.equals(stored));
        for (int i = 0; i < n; i++) {
            EditHistoryStore.Version v = versions.get(i);
            if (i == n - 1 && lastIsCurrent) {
                rows.add(new Row(Kind.CURRENT, current != null ? current : v.text, v.time, v.noticed));
            } else {
                rows.add(new Row(v.original ? Kind.ORIGINAL : Kind.EARLIER, v.text, v.time, v.noticed));
            }
        }
        if (!lastIsCurrent && current != null && !current.isEmpty()) rows.add(new Row(Kind.CURRENT, current, 0, false));
        return rows;
    }

    static int earlier(List<Row> rows) {
        int count = 0;
        for (Row row : rows) if (row.kind != Kind.CURRENT) count++;
        return count;
    }

    static int missing(int editCount, int earlier) {
        return editCount > earlier ? editCount - earlier : 0;
    }

    static boolean complete(int editCount, int serverVersions) {
        return editCount > 0 && serverVersions >= editCount;
    }

    static long toMillis(long t) {
        if (t <= 0) return 0;
        if (t >= 100_000_000_000_000L) return t / 1000;
        if (t >= 100_000_000_000L) return t;
        return t * 1000;
    }
}
