package ps.reso.instaeclipse.mods.ghost;

import ps.reso.instaeclipse.R;

final class BulkUnsendText {

    interface Plurals {
        String quantity(int id, int count, Object... args) throws Exception;
    }

    private final Plurals plurals;

    BulkUnsendText(Plurals plurals) {
        this.plurals = plurals;
    }

    String confirm(int count) {
        return text(R.plurals.ig_bulk_unsend_confirm, count, String.valueOf(count), count);
    }

    String progress(int done, int total) {
        return text(R.plurals.ig_bulk_unsend_progress, total, done + "/" + total, done, total);
    }

    String result(int done, int total) {
        return text(R.plurals.ig_bulk_unsend_result, total, done + "/" + total, done, total);
    }

    private String text(int id, int count, String fallback, Object... args) {
        try {
            String s = plurals.quantity(id, count, args);
            return s != null && !s.isEmpty() ? s : fallback;
        } catch (Exception e) {
            return fallback;
        }
    }
}
