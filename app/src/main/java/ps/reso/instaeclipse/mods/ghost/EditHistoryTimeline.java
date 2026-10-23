package ps.reso.instaeclipse.mods.ghost;

import java.util.ArrayList;
import java.util.List;

import ps.reso.instaeclipse.utils.ghost.EditHistoryStore;

/**
 * What the "Edited" history dialog lists: the message's earlier versions, oldest first, then its
 * current text. Framework-free, so it is unit-tested.
 */
final class EditHistoryTimeline {

    enum Kind { ORIGINAL, EARLIER, CURRENT }

    static final class Row {
        final Kind kind;
        final String text;
        /** Epoch ms; 0 when unknown. */
        final long time;
        /** {@link #time} is when this phone noticed the edit. */
        final boolean noticed;

        Row(Kind kind, String text, long time, boolean noticed) {
            this.kind = kind; this.text = text; this.time = time; this.noticed = noticed;
        }
    }

    private EditHistoryTimeline() {}

    /**
     * Rows for the stored {@code versions} (oldest first) and the message's {@code current} text. The
     * newest stored version is the current one when it matches it, or when the current text is unknown
     * (null: the message could not be read); otherwise the current text follows, its time unknown.
     */
    static List<Row> rows(List<EditHistoryStore.Version> versions, String current) {
        List<Row> rows = new ArrayList<>();
        int n = versions == null ? 0 : versions.size();
        String stored = EditHistoryStore.cut(current);
        boolean lastIsCurrent = n > 0 && (current == null || versions.get(n - 1).text.equals(stored));
        for (int i = 0; i < n; i++) {
            EditHistoryStore.Version v = versions.get(i);
            if (i == n - 1 && lastIsCurrent) {
                // The stored copy may be cut; show the message's own full text.
                rows.add(new Row(Kind.CURRENT, current != null ? current : v.text, v.time, v.noticed));
            } else {
                rows.add(new Row(v.original ? Kind.ORIGINAL : Kind.EARLIER, v.text, v.time, v.noticed));
            }
        }
        if (!lastIsCurrent && current != null && !current.isEmpty()) rows.add(new Row(Kind.CURRENT, current, 0, false));
        return rows;
    }

    /** The rows that are earlier versions (not the current text). */
    static int earlier(List<Row> rows) {
        int count = 0;
        for (Row row : rows) if (row.kind != Kind.CURRENT) count++;
        return count;
    }

    /** Edits Instagram counts that no saved version covers; 0 when the count is unknown (negative). */
    static int missing(int editCount, int earlier) {
        return editCount > earlier ? editCount - earlier : 0;
    }

    /** Instagram's server history covers every edit when it lists at least as many versions as edits. */
    static boolean complete(int editCount, int serverVersions) {
        return editCount > 0 && serverVersions >= editCount;
    }

    /** Instagram's direct timestamps are microseconds; some fields use milliseconds or seconds. */
    static long toMillis(long t) {
        if (t <= 0) return 0;
        if (t >= 100_000_000_000_000L) return t / 1000;  // µs
        if (t >= 100_000_000_000L) return t;              // ms
        return t * 1000;                                  // s
    }
}
