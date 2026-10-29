package ps.reso.instaeclipse.mods.misc;

import java.net.URI;
import java.net.URLDecoder;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

final class ExternalLinkPolicy {
    private static final String[] INTERNAL_HOSTS = {
            "instagram.com", "instagr.am", "ig.me", "facebook.com", "fb.com", "threads.net", "threads.com"};
    private static final String UNESCAPED = " \"<>^`{|}";

    private ExternalLinkPolicy() {}

    static String browserUrl(String value) {
        if (value == null) return null;
        try {
            URI uri = parse(value);
            if (!isWeb(uri)) return null;
            if (isShim(uri)) {
                String destination = destination(uri);
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

    static String shimTarget(String url) {
        if (url == null) return null;
        try {
            URI uri = parse(url);
            return isWeb(uri) && isShim(uri) ? destination(uri) : null;
        } catch (IllegalArgumentException | java.net.URISyntaxException | java.io.UnsupportedEncodingException ignored) {
            return null;
        }
    }

    static String stripFbclid(String url) {
        if (url == null) return null;
        int hash = url.indexOf('#');
        String head = hash < 0 ? url : url.substring(0, hash);
        int q = head.indexOf('?');
        if (q < 0) return url;
        List<String> kept = new ArrayList<>();
        boolean dropped = false;
        for (String pair : head.substring(q + 1).split("&", -1)) {
            int eq = pair.indexOf('=');
            if ((eq < 0 ? pair : pair.substring(0, eq)).equals("fbclid")) dropped = true;
            else kept.add(pair);
        }
        if (!dropped) return url;
        String query = String.join("&", kept);
        return head.substring(0, q) + (query.isEmpty() ? "" : "?" + query) + (hash < 0 ? "" : url.substring(hash));
    }

    private static boolean isShim(URI uri) {
        return "l.instagram.com".equalsIgnoreCase(uri.getHost()) || "l.facebook.com".equalsIgnoreCase(uri.getHost());
    }

    private static String destination(URI uri) throws java.io.UnsupportedEncodingException {
        String query = uri.getRawQuery();
        if (query == null) return null;
        String destination = null;
        for (String part : query.split("&")) {
            if (!part.startsWith("u=")) continue;
            if (destination != null) return null;
            destination = URLDecoder.decode(part.substring(2), "UTF-8");
        }
        return destination;
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
