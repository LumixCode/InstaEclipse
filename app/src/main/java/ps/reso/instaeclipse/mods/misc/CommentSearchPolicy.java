package ps.reso.instaeclipse.mods.misc;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.regex.Pattern;

final class CommentSearchPolicy {

    enum Load { GOING, DONE, MAYBE_MORE }

    static final Pattern VIEW_REPLIES =
            Pattern.compile("(?i)^\\s*view\\s+(all\\s+)?\\d[\\d,.]*\\s+(more\\s+)?repl(y|ies)\\s*$");

    private CommentSearchPolicy() {}

    static List<Pattern> replyMatchers(Collection<String> templates) {
        return Collections.singletonList(VIEW_REPLIES);
    }

    static boolean isReplyLink(List<Pattern> matchers, CharSequence text) {
        if (text == null) return false;
        for (Pattern p : matchers) if (p.matcher(text).matches()) return true;
        return false;
    }

    static String[] header(List<String[]> rows, String[] post) {
        for (String[] r : rows) if (r[2].isEmpty() && !r[0].isEmpty()) return new String[]{r[0], r[1]};
        return new String[]{"", ""};
    }

    static List<String[]> comments(List<String[]> rows, String[] header) {
        List<String[]> out = new ArrayList<>();
        for (String[] r : rows) if (!r[2].isEmpty()) out.add(r);
        return out;
    }

    static int count(List<String[]> rows, String[] header) {
        return rows.size();
    }

    static String mediaId(List<String[]> rows) {
        for (String[] r : rows) if (!r[2].isEmpty() && r.length > 7 && !r[7].isEmpty()) return r[7];
        return "";
    }

    static boolean sameMedia(String a, String b) {
        if (a == null || b == null || a.isEmpty() || b.isEmpty()) return false;
        return a.equals(b) || pk(a).equals(pk(b));
    }

    private static String pk(String id) {
        int i = id.indexOf('_');
        return i < 0 ? id : id.substring(0, i);
    }

    static Load loadState(boolean recycler, long idleMs, boolean atEnd, boolean grew) {
        if (recycler) return idleMs > 3000 && atEnd ? Load.DONE : Load.GOING;
        return idleMs > 4000 ? Load.DONE : Load.GOING;
    }
}
