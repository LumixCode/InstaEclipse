package ps.reso.instaeclipse.share;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
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

    private static final Pattern URL = Pattern.compile(
            "https?://(?:www\\.|m\\.|applink\\.)?instagram\\.com(?=[/?#]|\\s|$)\\S*", Pattern.CASE_INSENSITIVE);
    private static final Pattern ID = Pattern.compile("[A-Za-z0-9_-]{6,}");
    private static final Pattern USER = Pattern.compile("[A-Za-z0-9._]+");
    private static final Set<String> RESERVED = Set.of("audio", "series", "effect_page", "videos", "app");
    private static final String TRAILING = ")]>\"'.,";

    private ShareLinks() {}

    public static Link parse(String text) {
        if (text == null) return null;
        Matcher m = URL.matcher(text);
        while (m.find()) {
            Link link = link(trim(m.group()));
            if (link != null) return link;
        }
        return null;
    }

    public static String shortcode(String link) {
        Link parsed = parse(link);
        return parsed == null || parsed.kind == Kind.OPAQUE ? null : parsed.shortcode;
    }

    private static String trim(String url) {
        int end = url.length();
        while (end > 0 && TRAILING.indexOf(url.charAt(end - 1)) >= 0) end--;
        return url.substring(0, end);
    }

    private static Link link(String url) {
        int query = url.length();
        for (char c : new char[]{'?', '#'}) {
            int at = url.indexOf(c);
            if (at >= 0 && at < query) query = at;
        }
        String base = url.substring(0, query);
        int path = base.indexOf('/', base.indexOf("://") + 3);
        if (path < 0) return null;
        List<String> parts = new ArrayList<>();
        for (String part : base.substring(path + 1).split("/")) {
            if (!part.isEmpty()) parts.add(part);
        }
        if (parts.size() < 2) return null;
        if (parts.get(0).equals("share")) return new Link(base, Kind.OPAQUE, null);
        int at = parts.size() > 2 && kind(parts.get(1)) != null && USER.matcher(parts.get(0)).matches() ? 1 : 0;
        Kind kind = kind(parts.get(at));
        if (kind == null || parts.size() < at + 2) return null;
        String id = parts.get(at + 1);
        if (RESERVED.contains(id) || !ID.matcher(id).matches()) return null;
        String route = kind == Kind.POST ? "p" : kind == Kind.TV ? "tv" : "reel";
        return new Link("https://www.instagram.com/" + route + "/" + id + "/", kind, id);
    }

    private static Kind kind(String segment) {
        switch (segment) {
            case "p": return Kind.POST;
            case "reel":
            case "reels": return Kind.REEL;
            case "tv": return Kind.TV;
            default: return null;
        }
    }
}
