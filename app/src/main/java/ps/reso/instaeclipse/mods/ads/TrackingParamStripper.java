package ps.reso.instaeclipse.mods.ads;

import java.util.Locale;
import java.util.Set;

public final class TrackingParamStripper {

    private TrackingParamStripper() {}

    private static final String URL_CHARS = "A-Za-z0-9\\-._~:/?#\\[\\]@!$&'()*+,;=%";
    private static final java.util.regex.Pattern IG_URL = java.util.regex.Pattern.compile(
            "(?i)(?<![a-z0-9.@-])(?:https?://)?(?:[a-z0-9-]+\\.)*instagram\\.com/[" + URL_CHARS + "]*?"
                    + "(?=[).,;:!?'\"\\]]*(?:[^" + URL_CHARS + "]|$))");

    private static final Set<String> SHARE_HOSTS = Set.of("instagram.com", "www.instagram.com", "m.instagram.com");

    private static final Set<String> APP_PATHS = Set.of("accounts", "oauth", "challenge", "api", "graphql",
            "ajax", "web", "direct", "emails", "session", "privacy", "legal", "about", "developer", "static",
            "_n", "_u");

    private static final Set<String> FUNCTIONAL = Set.of(
            "img_index",
            "comment_id", "open_comments", "open_like_count",
            "q",
            "hl");

    private static boolean isTrackingParam(String key) {
        String k = key.toLowerCase(Locale.ROOT);
        return k.equals("igshid") || k.equals("igsh") || k.equals("igsi") || k.equals("ig_rid") || k.equals("ig_mid")
                || k.equals("stkn") || k.startsWith("utm_") || k.equals("story_media_id")
                || k.equals("saved_by") || k.equals("saved-by")
                || k.equals("psln");
    }

    public static String strip(String text) {
        if (text == null || !text.toLowerCase(Locale.ROOT).contains("instagram.com/")) return null;
        java.util.regex.Matcher m = IG_URL.matcher(text);
        StringBuilder out = new StringBuilder(text.length());
        int last = 0;
        boolean changed = false;
        while (m.find()) {
            String url = m.group();
            String cleaned = stripUrl(url);
            out.append(text, last, m.start()).append(cleaned);
            last = m.end();
            if (!cleaned.equals(url)) changed = true;
        }
        if (!changed) return null;
        out.append(text, last, text.length());
        return out.toString();
    }

    private static String stripUrl(String url) {
        int q = url.indexOf('?');
        if (q < 0) return url;
        int h = url.indexOf('#', q);
        String base = url.substring(0, q);
        String query = h >= 0 ? url.substring(q + 1, h) : url.substring(q + 1);
        String fragment = h >= 0 ? url.substring(h) : "";
        boolean shareLink = isShareLink(base);
        StringBuilder kept = new StringBuilder();
        boolean removed = false;
        for (String pair : query.split("&")) {
            if (pair.isEmpty()) continue;
            int eq = pair.indexOf('=');
            String key = eq >= 0 ? pair.substring(0, eq) : pair;
            if (isTrackingParam(key)
                    || (shareLink && !FUNCTIONAL.contains(key.toLowerCase(Locale.ROOT)))) {
                removed = true;
                continue;
            }
            if (kept.length() > 0) kept.append('&');
            kept.append(pair);
        }
        if (!removed) return url;
        return kept.length() > 0 ? base + "?" + kept + fragment : base + fragment;
    }

    private static boolean isShareLink(String base) {
        String lower = base.toLowerCase(Locale.ROOT);
        int scheme = lower.indexOf("://");
        String rest = scheme >= 0 ? lower.substring(scheme + 3) : lower;
        int slash = rest.indexOf('/');
        if (slash < 0 || !SHARE_HOSTS.contains(rest.substring(0, slash))) return false;
        String path = rest.substring(slash + 1);
        int end = path.indexOf('/');
        String first = end >= 0 ? path.substring(0, end) : path;
        return !first.isEmpty() && !APP_PATHS.contains(first);
    }
}
