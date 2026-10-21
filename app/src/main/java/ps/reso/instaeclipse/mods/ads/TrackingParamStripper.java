package ps.reso.instaeclipse.mods.ads;

import java.util.Locale;
import java.util.Set;

/** Pure (framework-free) tracking-param removal used by {@link TrackingLinkDisable}; unit-tested. */
public final class TrackingParamStripper {

    private TrackingParamStripper() {}

    // Instagram links inside arbitrary share text ("Check this out https://www.instagram.com/p/…").
    // Not inside another domain (notinstagram.com), and closing punctuation after it stays text.
    private static final java.util.regex.Pattern IG_URL = java.util.regex.Pattern.compile(
            "(?i)(?<![a-z0-9.@-])(?:https?://)?(?:[a-z0-9-]+\\.)*instagram\\.com/[^\\s]*?"
                    + "(?=[).,;:!?'\"\\]]*(?:\\s|$))");

    /** Hosts whose links are the ones Instagram shares (posts, reels, stories, profiles). */
    private static final Set<String> SHARE_HOSTS = Set.of("instagram.com", "www.instagram.com", "m.instagram.com");

    /**
     * First path segments of app areas whose query can carry real input (login redirects, OAuth,
     * the API). Links there only lose the known tracking keys; every other path is a share link.
     */
    private static final Set<String> APP_PATHS = Set.of("accounts", "oauth", "challenge", "api", "graphql",
            "ajax", "web", "direct", "emails", "session", "privacy", "legal", "about", "developer", "static",
            "_n", "_u"); // in-app deep links (e.g. _n/reels_audio_page?audio_id=)

    /** Query keys that change what a share link opens; every other key on one is share tracking. */
    private static final Set<String> FUNCTIONAL = Set.of(
            "img_index",                                        // carousel slide (#252)
            "comment_id", "open_comments", "open_like_count",   // Instagram's own comment/likes links
            "q",                                                // explore/search/keyword term
            "hl");                                              // interface language

    /** True for query keys that only track the sharer/session (never change what the link opens). */
    private static boolean isTrackingParam(String key) {
        String k = key.toLowerCase(Locale.ROOT);
        return k.equals("igshid") || k.equals("igsh") || k.equals("igsi") || k.equals("ig_rid") || k.equals("ig_mid")
                || k.equals("stkn") || k.startsWith("utm_") || k.equals("story_media_id")
                || k.equals("saved_by") || k.equals("saved-by")
                || k.equals("psln"); // per-share id on "Copy link" (IG 452)
    }

    /**
     * Returns the text with tracking params removed from every Instagram link in it, or null when
     * nothing changed. Instagram's server keeps renaming the per-share id (igshid, igsh, igsi, psln,
     * mdkz, …), so share links keep only {@link #FUNCTIONAL} keys; other links drop
     * the known tracking keys. Kept params stay in order, and any #fragment is preserved.
     */
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

    /** A link on Instagram's main host outside {@link #APP_PATHS} (never the l.instagram.com link shim). */
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
