package ps.reso.instaeclipse.mods.ads;

import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class ShareDomain {

    private static final Pattern HOST = Pattern.compile("(https?://)(?:www\\.)?instagram\\.com/");
    private static final Pattern VALID = Pattern.compile("[a-z0-9]([a-z0-9-]{0,61}[a-z0-9])?(\\.[a-z0-9]([a-z0-9-]{0,61}[a-z0-9])?)+");

    private ShareDomain() {}

    public static String apply(String text, String domain) {
        if (text == null || domain == null || domain.isEmpty() || !text.contains("instagram.com/")) return text;
        return HOST.matcher(text).replaceAll("$1" + Matcher.quoteReplacement(domain) + "/");
    }

    public static String normalize(String input) {
        if (input == null) return "";
        String d = input.trim().toLowerCase(Locale.ROOT);
        d = d.replaceFirst("^[a-z]+://", "");
        int slash = d.indexOf('/');
        if (slash >= 0) d = d.substring(0, slash);
        if (d.isEmpty()) return "";
        if (d.length() > 253 || !VALID.matcher(d).matches()
                || d.equals("instagram.com") || d.endsWith(".instagram.com")) return null;
        return d;
    }
}
