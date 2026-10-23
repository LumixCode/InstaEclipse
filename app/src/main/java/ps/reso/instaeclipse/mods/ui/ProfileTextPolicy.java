package ps.reso.instaeclipse.mods.ui;

import java.util.List;

/** What holding a profile's username or bio copies (ProfileTextCopyHook). No Android types, so it is unit tested. */
final class ProfileTextPolicy {
    private static final int HEAD = 12;
    private static final int MIN_TRUNCATED_HEAD = 3;

    private ProfileTextPolicy() {}

    /**
     * The username a profile title shows, or null. For a private account Instagram's title builder
     * (0AgX.A00 on 451) adds a space holding the padlock image before the name (after it, right to
     * left), and the text can carry bidi marks.
     */
    static String username(CharSequence title) {
        if (title == null) return null;
        String name = squash(title.toString());
        return name.isEmpty() ? null : name;
    }

    /**
     * True when a node's text shows this bio: the whole bio, or its start collapsed into "… more".
     * The header's other texts (business category, links, address) can begin like the bio or
     * contain a short one, so only these count: the whole bio (a short one exactly, or as the
     * start of the text); everything before an ellipsis being the bio's start; or, for a collapse
     * without an ellipsis, a long run of the bio followed by no more than a "more" label.
     */
    static boolean shows(String visible, String bio) {
        if (visible == null || bio == null) return false;
        String shown = squash(visible), full = squash(bio);
        if (full.isEmpty() || shown.isEmpty()) return false;
        if (full.length() < HEAD) {
            if (shown.equals(full) || shown.startsWith(full + " ")) return true;
        } else if (shown.contains(full)) {
            return true;
        }
        int cut = shown.indexOf('…');
        if (cut < 0) cut = shown.indexOf("...");
        if (cut >= 0) {
            String before = shown.substring(0, cut).trim();
            return before.length() >= MIN_TRUNCATED_HEAD && before.length() < full.length() && full.startsWith(before);
        }
        int common = 0;
        while (common < shown.length() && common < full.length() && shown.charAt(common) == full.charAt(common)) common++;
        return common >= 2 * HEAD && shown.length() - common <= 2 * HEAD;
    }

    /**
     * The bio to copy for a hold, or null when the held text isn't the bio. {@code held} are the
     * texts under the finger, innermost first (a mention inside the bio, then the bio itself). The
     * User model's bio comes first; {@code headerBios} (the bios profile headers were built with,
     * the open profile's first) are the fallback when the model has none or it isn't what is shown.
     */
    static String bioFor(List<String> held, String modelBio, List<String> headerBios) {
        for (String text : held) {
            if (text == null || text.trim().isEmpty()) continue;
            if (modelBio != null && shows(text, modelBio)) return modelBio.trim();
            for (String bio : headerBios) if (bio != null && shows(text, bio)) return bio.trim();
        }
        return null;
    }

    /** Text without invisible format marks (bidi isolates, zero-width joiners), object replacement
     *  characters (image spans) or edge whitespace, and with every inner run of whitespace, no-break
     *  spaces and line breaks included, as one space. */
    private static String squash(String text) {
        StringBuilder out = new StringBuilder(text.length());
        boolean space = false;
        for (int i = 0; i < text.length(); ) {
            int cp = text.codePointAt(i);
            i += Character.charCount(cp);
            if (Character.getType(cp) == Character.FORMAT || cp == 0xFFFC) continue;
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
