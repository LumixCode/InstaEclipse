package ps.reso.instaeclipse.mods.ui;

import java.util.List;

final class ProfileTextPolicy {
    private static final int HEAD = 12;
    private static final int MIN_TRUNCATED_HEAD = 3;

    private ProfileTextPolicy() {}

    static String username(CharSequence title) {
        if (title == null) return null;
        String name = squash(title.toString());
        return name.isEmpty() ? null : name;
    }

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

    static String bioFor(List<String> held, String modelBio, List<String> headerBios) {
        for (String text : held) {
            if (text == null || text.trim().isEmpty()) continue;
            if (modelBio != null && shows(text, modelBio)) return modelBio.trim();
            for (String bio : headerBios) if (bio != null && shows(text, bio)) return bio.trim();
        }
        return null;
    }

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
