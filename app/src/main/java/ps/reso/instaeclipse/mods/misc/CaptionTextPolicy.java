package ps.reso.instaeclipse.mods.misc;

final class CaptionTextPolicy {
    private static final int HEAD = 12;
    private static final int MIN_TRUNCATED_HEAD = 3;

    private CaptionTextPolicy() {}

    /**
     * True when the on-screen text displays this caption, collapsed or expanded. Comments also
     * link to their post, so a comment only matches if it repeats the caption's opening words.
     */
    static boolean shows(String visible, String caption) {
        if (visible == null || caption == null) return false;
        String shown = squash(visible), full = squash(caption);
        if (full.isEmpty()) return false;
        if (shown.contains(full.substring(0, Math.min(full.length(), HEAD)))) return true;
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
        return !full.isEmpty() && squash(visible).contains(full);
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
