package ps.reso.instaeclipse.share;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class ShareLinks {
    public enum Kind { POST, REEL, TV, OPAQUE }

    public static final class Link {
        public final String canonicalUrl;
        public final Kind kind;
        public final String shortcode;

        Link(String canonicalUrl, Kind kind, String shortcode) {
            this.canonicalUrl = canonicalUrl;
            this.kind = kind;
            this.shortcode = shortcode;
        }
    }

    private static final Pattern LINK = Pattern.compile(
            "https?://(?:www\\.|m\\.)?instagram\\.com/(?:[A-Za-z0-9._]+/)?(?:p|reel|reels|tv)/[A-Za-z0-9_-]+[^\\s]*");
    private static final Pattern SHORTCODE = Pattern.compile("/(p|reel|reels|tv)/([A-Za-z0-9_-]+)");

    private ShareLinks() {}

    public static Link parse(String text) {
        if (text == null) return null;
        Matcher m = LINK.matcher(text);
        if (!m.find()) return null;
        String link = m.group();
        Matcher code = SHORTCODE.matcher(link);
        if (!code.find()) return null;
        return new Link(link, kind(code.group(1)), code.group(2));
    }

    public static String shortcode(String link) {
        if (link == null) return null;
        Matcher m = SHORTCODE.matcher(link);
        return m.find() ? m.group(2) : null;
    }

    private static Kind kind(String segment) {
        switch (segment) {
            case "p": return Kind.POST;
            case "tv": return Kind.TV;
            default: return Kind.REEL;
        }
    }
}
