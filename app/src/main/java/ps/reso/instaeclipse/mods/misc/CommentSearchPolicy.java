package ps.reso.instaeclipse.mods.misc;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

final class CommentSearchPolicy {

    enum Load { GOING, DONE, MAYBE_MORE }

    static final Pattern VIEW_REPLIES =
            Pattern.compile("(?i)^\\s*view\\s+(all\\s+)?\\d[\\d,.]*\\s+(more\\s+)?repl(y|ies)\\s*$");

    private static final String GAP = "[\\s\\u00A0\\u202F]+";
    private static final String ANY_NUMBER = "\\p{Nd}+(?:[,.'\\u00A0\\u202F\\u2009\\u066B\\u066C ]\\p{Nd}+)*";
    private static final Pattern NUMBER = Pattern.compile("%(?:\\d+\\$)?[-#+0,(]*\\d*[ds]|" + ANY_NUMBER);
    private static final Pattern SPACE = Pattern.compile(GAP);
    private static final Pattern LETTER = Pattern.compile("\\p{L}");

    private CommentSearchPolicy() {}

    static List<Pattern> replyMatchers(Collection<String> templates) {
        Map<String, Pattern> out = new LinkedHashMap<>();
        for (String t : templates) {
            if (t == null) continue;
            String template = t.trim();
            StringBuilder regex = new StringBuilder("^\\s*");
            StringBuilder words = new StringBuilder();
            Matcher m = NUMBER.matcher(template);
            int at = 0;
            while (m.find()) {
                String part = template.substring(at, m.start());
                words.append(part);
                regex.append(literal(part)).append(ANY_NUMBER);
                at = m.end();
            }
            String tail = template.substring(at);
            words.append(tail);
            regex.append(literal(tail)).append("\\s*$");
            if (!LETTER.matcher(words).find()) continue;
            String key = regex.toString();
            if (!out.containsKey(key)) out.put(key, Pattern.compile(key, Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE));
        }
        out.put(VIEW_REPLIES.pattern(), VIEW_REPLIES);
        return new ArrayList<>(out.values());
    }

    private static String literal(String s) {
        StringBuilder b = new StringBuilder();
        Matcher m = SPACE.matcher(s);
        int at = 0;
        while (m.find()) {
            if (m.start() > at) b.append(Pattern.quote(s.substring(at, m.start())));
            b.append(GAP);
            at = m.end();
        }
        if (at < s.length()) b.append(Pattern.quote(s.substring(at)));
        return b.toString();
    }

    static boolean isReplyLink(List<Pattern> matchers, CharSequence text) {
        if (text == null) return false;
        for (Pattern p : matchers) if (p.matcher(text).matches()) return true;
        return false;
    }

    static String[] header(List<String[]> rows, String[] post) {
        if (post != null) return new String[]{post[0], post[1]};
        for (String[] r : rows) if (r[2].isEmpty() && !r[0].isEmpty()) return new String[]{r[0], r[1]};
        return new String[]{"", ""};
    }

    static List<String[]> comments(List<String[]> rows, String[] header) {
        List<String[]> out = new ArrayList<>();
        for (String[] r : rows) if (!r[2].isEmpty()) out.add(r);
        if (!out.isEmpty()) return out;
        for (String[] r : rows) if (!(r[0].equals(header[0]) && r[1].equals(header[1]))) out.add(r);
        return out;
    }

    static int count(List<String[]> rows, String[] header) {
        return comments(rows, header).size();
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
        return idleMs > (grew ? 6000 : 4000) ? Load.MAYBE_MORE : Load.GOING;
    }
}
