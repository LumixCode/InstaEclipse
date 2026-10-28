package ps.reso.instaeclipse.utils.ghost;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.function.Predicate;

public final class ReadReceiptListModel {

    public static final class Row {
        public final String id;
        public final String title;
        public final String saved;
        private boolean on = true;

        Row(String id, String title, String saved) {
            this.id = id;
            this.title = title;
            this.saved = saved;
        }

        public boolean on() {
            return on;
        }

        public String label(String unknown) {
            if (!title.isEmpty()) return title;
            String suffix = id.length() > 6 ? id.substring(id.length() - 6) : id;
            return unknown + "  ·  …" + suffix;
        }
    }

    private final List<Row> rows = new ArrayList<>();

    public ReadReceiptListModel(Map<String, String> listed, Function<String, String> inboxTitle,
                                Function<String, String> savedName, Predicate<String> hidden) {
        List<Row> titled = new ArrayList<>();
        List<Row> untitled = new ArrayList<>();
        if (listed != null) {
            for (Map.Entry<String, String> entry : listed.entrySet()) {
                String id = entry.getKey();
                if (id == null || id.isEmpty()) continue;
                String stored = clean(entry.getValue());
                String title = hidden.test(id) ? ""
                        : firstOf(clean(inboxTitle.apply(id)), stored, clean(savedName.apply(id)));
                Row row = new Row(id, title, stored.isEmpty() ? title : stored);
                (title.isEmpty() ? untitled : titled).add(row);
            }
        }
        titled.sort((a, b) -> String.CASE_INSENSITIVE_ORDER.compare(a.title, b.title));
        rows.addAll(titled);
        rows.addAll(untitled);
    }

    public List<Row> rows() {
        return Collections.unmodifiableList(rows);
    }

    public Row set(String id, boolean on) {
        for (Row row : rows) {
            if (row.id.equals(id)) {
                row.on = on;
                return row;
            }
        }
        return null;
    }

    private static String clean(String s) {
        return s == null ? "" : s.trim();
    }

    private static String firstOf(String... titles) {
        for (String t : titles) if (!t.isEmpty()) return t;
        return "";
    }
}
