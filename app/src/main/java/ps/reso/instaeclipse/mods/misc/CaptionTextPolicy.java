package ps.reso.instaeclipse.mods.misc;

import java.util.Collection;

final class CaptionTextPolicy {
    private static final int HEAD = 12;
    private static final int MIN_TRUNCATED_HEAD = 3;

    private CaptionTextPolicy() {}

    /**
     * True when the on-screen text displays this caption, collapsed or expanded. Comments also
     * link to their post, so a comment only matches if it repeats the caption's opening words.
     */
    static boolean shows(String visible, String caption) {
        return shows(visible, caption, null);
    }

    static boolean shows(String visible, String caption, Collection<String> authors) {
        if (visible == null || caption == null) return false;
        String shown = squash(visible), full = squash(caption);
        if (full.isEmpty()) return false;
        if (full.length() >= HEAD) {
            if (shown.contains(full.substring(0, HEAD))) return true;
        } else if (shown.equals(full) || afterAuthor(shown, full, authors)) {
            return true;
        }
        // Collapsed after a short first line: the caption's start sits right before the ellipsis.
        int cut = shown.indexOf('…');
        if (cut < 0) return false;
        String before = shown.substring(0, cut).trim();
        for (int k = Math.min(full.length(), before.length()); k >= MIN_TRUNCATED_HEAD; k--) {
            String head = full.substring(0, k).trim();
            if (head.length() >= MIN_TRUNCATED_HEAD && before.endsWith(head)) return true;
        }
        return false;
    }

    /** True when the on-screen text holds the whole caption, as an expanded caption does. */
    static boolean showsWhole(String visible, String caption) {
        if (visible == null || caption == null) return false;
        String full = squash(caption);
        if (full.isEmpty()) return false;
        String shown = squash(visible);
        return full.length() >= HEAD ? shown.contains(full) : shown.equals(full);
    }

    static boolean isShort(String caption) {
        return caption == null || squash(caption).length() < HEAD;
    }

    private static boolean afterAuthor(String shown, String full, Collection<String> authors) {
        int at = shown.length() - full.length() - 1;
        if (at <= 0 || shown.charAt(at) != ' ' || !shown.endsWith(full)) return false;
        String token = shown.substring(0, at);
        if (token.indexOf(' ') >= 0) return false;
        return authors == null || authors.isEmpty() || authors.contains(token);
    }

    /** Text without invisible format marks (bidi isolates, zero-width joiners) and with every run
     *  of whitespace, no-break spaces included, as one space: what is shown, not how it's encoded. */
    private static String squash(String text) {
        // No regex: Android's engine rejects flags like (?U) that the JVM accepts.
        StringBuilder out = new StringBuilder(text.length());
        boolean space = false;
        for (int i = 0; i < text.length(); ) {
            int cp = text.codePointAt(i);
            i += Character.charCount(cp);
            if (Character.getType(cp) == Character.FORMAT) continue;
            if (Character.isWhitespace(cp) || Character.isSpaceChar(cp)) {
                space = true;
                continue;
            }
            if (space && out.length() > 0) out.append(' ');
            space = false;
            out.appendCodePoint(cp);
        }
        return out.toString();
    }
}
