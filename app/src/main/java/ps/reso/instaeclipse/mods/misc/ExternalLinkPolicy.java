package ps.reso.instaeclipse.mods.misc;

import java.net.URI;
import java.net.URLDecoder;
import java.util.Locale;

final class ExternalLinkPolicy {
    // Meta's own pages stay in Instagram's in-app browser, which keeps Meta sign-in and app links.
    private static final String[] INTERNAL_HOSTS = {
            "instagram.com", "instagr.am", "ig.me", "facebook.com", "fb.com", "threads.net", "threads.com"};
    // Ad and tracking links carry these unescaped; java.net.URI would reject the whole link.
    private static final String UNESCAPED = " \"<>^`{|}";

    private ExternalLinkPolicy() {}

    /** Only unwrap Instagram's and Facebook's link shims, never an unrelated site's query parameter named u. */
    static String browserUrl(String value) {
        if (value == null) return null;
        try {
            URI uri = parse(value);
            if (!isWeb(uri)) return null;
            if ("l.instagram.com".equalsIgnoreCase(uri.getHost()) || "l.facebook.com".equalsIgnoreCase(uri.getHost())) {
                String destination = null;
                String query = uri.getRawQuery();
                if (query == null) return null;
                for (String part : query.split("&")) {
                    if (!part.startsWith("u=")) continue;
                    if (destination != null) return null;
                    destination = URLDecoder.decode(part.substring(2), "UTF-8");
                }
                if (destination == null) return null;
                uri = parse(destination);
                if (!isWeb(uri)) return null;
            }
            String host = uri.getHost().toLowerCase(Locale.ROOT);
            if (host.endsWith(".")) host = host.substring(0, host.length() - 1);
            for (String internal : INTERNAL_HOSTS) {
                if (host.equals(internal) || host.endsWith("." + internal)) return null;
            }
            return uri.toASCIIString();
        } catch (IllegalArgumentException | java.net.URISyntaxException | java.io.UnsupportedEncodingException ignored) {
            return null;
        }
    }

    private static URI parse(String value) throws java.net.URISyntaxException {
        StringBuilder escaped = new StringBuilder(value.length());
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (UNESCAPED.indexOf(c) >= 0) escaped.append('%').append(Integer.toHexString(c).toUpperCase(Locale.ROOT));
            else escaped.append(c);
        }
        return new URI(escaped.toString());
    }

    private static boolean isWeb(URI uri) {
        return ("https".equalsIgnoreCase(uri.getScheme()) || "http".equalsIgnoreCase(uri.getScheme()))
                && uri.getHost() != null && !uri.getHost().isEmpty() && uri.getRawUserInfo() == null;
    }
}
