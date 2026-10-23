package ps.reso.instaeclipse.fragments;

import java.util.List;
import java.util.regex.Pattern;

import ps.reso.instaeclipse.utils.log.LogLine;

final class LogEntries {

    static final int KIND_INFO = 0;
    static final int KIND_OK = 1;
    static final int KIND_WARN = 2;
    static final int KIND_ERROR = 3;
    static final int KIND_DETAIL = 4;

    private static final Pattern TS_PREFIX = Pattern.compile("^\\d{4}-\\d{2}-\\d{2} \\d{2}:\\d{2}:\\d{2}\\.\\d{3} .*", Pattern.DOTALL);
    private static final int TS_LEN = 23; // "yyyy-MM-dd HH:mm:ss.SSS"

    private LogEntries() {}

    static final class Entry {
        final String ts;
        final String full;
        /** Technical view: time with milliseconds, caller, tag and the whole entry. */
        final String display;
        /** Simple view: time, feature name and the first line of the message. */
        final String friendly;
        final int kind;

        Entry(String ts, String full, String display, String friendly, int kind) {
            this.ts = ts;
            this.full = full;
            this.display = display;
            this.friendly = friendly;
            this.kind = kind;
        }
    }

    /** Groups raw lines into entries: a timestamped line starts one, others (stack traces) continue it. */
    static void parseInto(List<Entry> out, List<String> lines, String prefix) {
        String curTs = null;
        StringBuilder cur = null;
        String lastTs = "";
        for (String line : lines) {
            boolean starts = line.length() > TS_LEN && TS_PREFIX.matcher(line).matches();
            if (starts || cur == null) {
                if (cur != null) out.add(makeEntry(curTs, cur.toString(), prefix));
                curTs = starts ? line.substring(0, TS_LEN) : lastTs;
                lastTs = curTs;
                cur = new StringBuilder(line);
            } else {
                cur.append('\n').append(line);
            }
        }
        if (cur != null) out.add(makeEntry(curTs, cur.toString(), prefix));
    }

    private static Entry makeEntry(String ts, String full, String prefix) {
        boolean timed = ts != null && !ts.isEmpty() && full.length() > TS_LEN && full.startsWith(ts);
        String body = timed ? full.substring(TS_LEN + 1) : full;
        String display, friendly;
        String message = LogLine.friendly(firstLine(body));
        if (timed) {
            // Drop the date for display; keep HH:mm:ss.SSS (simple view: HH:mm:ss).
            display = full.substring(11, TS_LEN) + "  " + prefix + LogLine.stripMarker(body);
            friendly = full.substring(11, 19) + "  " + prefix + message;
        } else {
            display = prefix + LogLine.stripMarker(body);
            friendly = prefix + message;
        }
        return new Entry(ts == null ? "" : ts, prefix.isEmpty() ? full : insertPrefix(full, ts, prefix),
                display, friendly, classify(body));
    }

    private static String firstLine(String text) {
        int end = text.indexOf('\n');
        return end < 0 ? text : text.substring(0, end);
    }

    private static String insertPrefix(String full, String ts, String prefix) {
        if (ts != null && !ts.isEmpty() && full.startsWith(ts) && full.length() > TS_LEN) {
            return ts + " " + prefix + full.substring(TS_LEN + 1);
        }
        return prefix + full;
    }

    /** Level written by ModuleLog; lines from older builds fall back to LogLine's guess. */
    static int classify(String body) {
        switch (LogLine.levelOf(body)) {
            case ERROR: return KIND_ERROR;
            case WARN: return KIND_WARN;
            case OK: return KIND_OK;
            case DETAIL: return KIND_DETAIL;
            default: return KIND_INFO;
        }
    }
}
