package ps.reso.instaeclipse.utils.core;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

public final class Credits {

    public enum Site { GITHUB, TELEGRAM, LINKEDIN, NONE }

    public static final List<Contributor> CONTRIBUTORS = Collections.unmodifiableList(Arrays.asList(
            new Contributor("ReSo7200", "https://github.com/ReSo7200", "https://linkedin.com/in/abdalhaleem-altamimi", null),
            new Contributor("swakwork", "https://github.com/swakwork", null, null),
            new Contributor("isma3iloiso", "https://github.com/isma3iloiso", null, null),
            new Contributor("Placeholder6", "https://github.com/Placeholder6", null, null),
            new Contributor("frknkrc44", "https://github.com/frknkrc44", null, null),
            new Contributor("BrianML", "https://github.com/brianml31", null, "https://t.me/instamoon_channel"),
            new Contributor("silvzr", "https://github.com/silvzr", null, null),
            new Contributor("oct", "https://github.com/oct888", null, null),
            new Contributor("HalfManBear", "https://github.com/halfmanbear", null, null),
            new Contributor("ar5to", "https://github.com/ar5to", null, "https://t.me/ar5to"),
            new Contributor("particle-box", "https://github.com/particle-box", null, null),
            new Contributor("rsr", null, null, "https://t.me/rsr1337"),
            new Contributor("akifakif32", "https://github.com/akifakif32", null, null),
            new Contributor("HackZy01", "https://github.com/HackZy01", null, null),
            new Contributor("Xiddoc", "https://github.com/Xiddoc", null, null),
            new Contributor("GlitchDaBest", "https://github.com/GlitchDaBest", null, null),
            new Contributor("NicKz101", "https://github.com/NicKz101", null, null),
            new Contributor("HcNguyen111", "https://github.com/HcNguyen111", null, null),
            new Contributor("Lxchoooo", "https://github.com/Lxchoooo", null, null),
            new Contributor("EscapeA", "https://github.com/EscapeA", null, null),
            new Contributor("Figim", "https://github.com/Figim", null, null),
            new Contributor("oka1da", "https://github.com/oka1da", null, null),
            new Contributor("uurcan7", "https://github.com/uurcan7", null, null),
            new Contributor("zarzet", "https://github.com/zarzet", null, null),
            new Contributor("xxOrdulu52xx", "https://github.com/xxOrdulu52xx", null, null),
            new Contributor("dpwbusr", "https://github.com/dpwbusr", null, null),
            new Contributor("d9k6s", "https://github.com/d9k6s", null, null),
            new Contributor("izadiegizabal", "https://github.com/izadiegizabal", null, null),
            new Contributor("b4lol", "https://github.com/b4lol", null, null)
    ));

    public static final List<Contributor> SPECIAL_THANKS = Collections.unmodifiableList(Arrays.asList(
            new Contributor("xHookman", "https://github.com/xHookman", null, null),
            new Contributor("Bluepapilte", null, null, "https://t.me/instasmashrepo"),
            new Contributor("BdrcnAYYDIN", null, null, "https://t.me/BdrcnAYYDIN"),
            new Contributor("Amàzing World", null, null, null)
    ));

    private Credits() {}

    public static Site site(Contributor c) {
        if (present(c.githubUrl())) return Site.GITHUB;
        if (present(c.telegramUrl())) return Site.TELEGRAM;
        if (present(c.linkedinUrl())) return Site.LINKEDIN;
        return Site.NONE;
    }

    public static String mainLink(Contributor c) {
        switch (site(c)) {
            case GITHUB: return c.githubUrl().trim();
            case TELEGRAM: return c.telegramUrl().trim();
            case LINKEDIN: return c.linkedinUrl().trim();
            default: return null;
        }
    }

    public static String initials(String name) {
        StringBuilder out = new StringBuilder(2);
        if (name != null) {
            for (String word : name.trim().split("\\s+")) {
                for (int i = 0; i < word.length(); i++) {
                    int cp = word.codePointAt(i);
                    if (Character.isLetterOrDigit(cp)) {
                        out.appendCodePoint(cp);
                        break;
                    }
                    i += Character.charCount(cp) - 1;
                }
                if (out.codePointCount(0, out.length()) == 2) break;
            }
        }
        return out.length() == 0 ? "?" : out.toString().toUpperCase(Locale.ROOT);
    }

    private static boolean present(String url) {
        return url != null && !url.trim().isEmpty();
    }
}
