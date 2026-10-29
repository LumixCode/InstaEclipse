package ps.reso.instaeclipse.utils.version;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class ReleaseNotes {
    private static final Pattern HEADING = Pattern.compile("^#{1,6}\\s+(.*?)(\\s+#+)?$");
    private static final Pattern BULLET = Pattern.compile("^(\\s*)[-*+]\\s+(.*)$");
    private static final Pattern TABLE_RULE = Pattern.compile("^\\|?\\s*:?-{3,}:?\\s*(\\|\\s*:?-{3,}:?\\s*)*\\|?$");
    private static final Pattern RULE = Pattern.compile("^(\\*\\s*){3,}$|^(_\\s*){3,}$");
    private static final Pattern IMAGE = Pattern.compile("!\\[([^\\]]*)]\\([^)]*\\)");
    private static final Pattern LINK = Pattern.compile("\\[([^\\]]+)]\\([^)]*\\)");
    private static final String ESCAPABLE = "\\`*_{}[]()#+-.!|<>";

    private enum Kind { NONE, PARAGRAPH, LIST, HEADING, TABLE }

    public final String text;
    public final List<int[]> bold;

    private ReleaseNotes(String text, List<int[]> bold) {
        this.text = text;
        this.bold = Collections.unmodifiableList(bold);
    }

    public static ReleaseNotes render(String markdown, int maxChars) {
        StringBuilder out = new StringBuilder();
        List<int[]> bold = new ArrayList<>();
        Kind last = Kind.NONE;
        boolean gap = false;
        String source = markdown == null ? "" : markdown.replace("\r\n", "\n").replace('\r', '\n');
        for (String raw : source.split("\n", -1)) {
            String line = raw.trim();
            if (line.isEmpty()) {
                gap = true;
                continue;
            }
            if (TABLE_RULE.matcher(line).matches() || RULE.matcher(line).matches()) continue;
            if (line.startsWith(">")) line = line.substring(1).trim();
            Matcher heading = HEADING.matcher(line);
            Matcher bullet = BULLET.matcher(raw);
            boolean paragraph = gap && last != Kind.HEADING;
            if (heading.matches()) {
                start(out, true);
                int from = out.length();
                inline(heading.group(1), out, bold);
                addBold(bold, from, out.length());
                last = Kind.HEADING;
            } else if (line.startsWith("|")) {
                List<String> cells = new ArrayList<>();
                for (String cell : line.replaceAll("^\\||\\|$", "").split("\\|", -1)) {
                    if (!cell.trim().isEmpty()) cells.add(cell.trim());
                }
                if (cells.isEmpty()) continue;
                start(out, paragraph);
                for (int i = 0; i < cells.size(); i++) {
                    if (i > 0) out.append(cells.size() == 2 ? ": " : " · ");
                    inline(cells.get(i), out, bold);
                }
                last = Kind.TABLE;
            } else if (bullet.matches()) {
                start(out, paragraph);
                int level = bullet.group(1).replace("\t", "    ").length() / 2;
                for (int i = 0; i < level; i++) out.append("  ");
                out.append("• ");
                inline(bullet.group(2), out, bold);
                last = Kind.LIST;
            } else if (last == Kind.PARAGRAPH && !gap) {
                out.append(' ');
                inline(line, out, bold);
            } else {
                start(out, paragraph);
                inline(line, out, bold);
                last = Kind.PARAGRAPH;
            }
            gap = false;
        }
        return cut(out.toString(), bold, maxChars);
    }

    private static void start(StringBuilder out, boolean paragraph) {
        if (out.length() == 0) return;
        out.append(paragraph ? "\n\n" : "\n");
    }

    private static void inline(String s, StringBuilder out, List<int[]> bold) {
        s = IMAGE.matcher(s).replaceAll("$1");
        s = LINK.matcher(s).replaceAll("$1");
        int open = -1;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '\\' && i + 1 < s.length() && ESCAPABLE.indexOf(s.charAt(i + 1)) >= 0) {
                out.append(s.charAt(++i));
            } else if (c == '`' && s.indexOf('`', i + 1) > i) {
                int end = s.indexOf('`', i + 1);
                out.append(s, i + 1, end);
                i = end;
            } else if (c == '*' && i + 1 < s.length() && s.charAt(i + 1) == '*') {
                if (open < 0) {
                    open = out.length();
                } else {
                    addBold(bold, open, out.length());
                    open = -1;
                }
                i++;
            } else {
                out.append(c);
            }
        }
    }

    private static void addBold(List<int[]> bold, int from, int to) {
        if (to > from) bold.add(new int[]{from, to});
    }

    private static ReleaseNotes cut(String text, List<int[]> bold, int maxChars) {
        if (maxChars <= 0 || text.length() <= maxChars) return new ReleaseNotes(text, bold);
        int end = maxChars;
        int floor = maxChars * 3 / 5;
        int line = text.lastIndexOf('\n', maxChars);
        int space = text.lastIndexOf(' ', maxChars);
        boolean atLine = line >= floor;
        if (atLine) end = line;
        else if (space >= floor) end = space;
        if (end > 0 && Character.isHighSurrogate(text.charAt(end - 1))) end--;
        String head = text.substring(0, end).replaceAll("\\s+$", "");
        List<int[]> kept = new ArrayList<>();
        for (int[] b : bold) {
            if (b[0] < head.length()) addBold(kept, b[0], Math.min(b[1], head.length()));
        }
        return new ReleaseNotes(head + (atLine ? "\n…" : "…"), kept);
    }
}
