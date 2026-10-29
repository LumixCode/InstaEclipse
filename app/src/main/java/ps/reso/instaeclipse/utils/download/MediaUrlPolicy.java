/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 *
 * Modified for HushGram (Instagram), 2026.
 */
package ps.reso.instaeclipse.utils.download;

import java.net.Inet4Address;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.Proxy;
import java.net.ProxySelector;
import java.net.URL;
import java.net.UnknownHostException;
import java.util.Locale;

class MediaUrlPolicy {

    interface Resolver {
        InetAddress[] resolve(String host) throws UnknownHostException;
    }

    interface Route {
        boolean direct(URL url);
    }

    static final Resolver DNS = InetAddress::getAllByName;

    static final Route DIRECT = url -> true;

    private static final String[] META_MEDIA_DOMAINS = { "fbcdn.net", "fbsbx.com", "cdninstagram.com", "giphy.com" };

    static final class Refusal {
        final String reason;
        final boolean network;

        Refusal(String reason, boolean network) {
            this.reason = reason;
            this.network = network;
        }
    }

    private final Resolver resolver;
    final Route route;

    MediaUrlPolicy(Resolver resolver) {
        this(resolver, DIRECT);
    }

    MediaUrlPolicy(Resolver resolver, Route route) {
        this.resolver = resolver;
        this.route = route;
    }

    Refusal refusal(URL url) {
        String shape = shapeRefusal(url);
        if (shape != null) return new Refusal(shape, false);
        if (!route.direct(url)) return null;

        String host = url.getHost().toLowerCase(Locale.US);
        InetAddress[] addresses;
        try {
            addresses = resolver.resolve(host);
        } catch (UnknownHostException e) {
            return new Refusal("its host does not resolve", true);
        } catch (Throwable t) {
            return new Refusal("its host could not be looked up", true);
        }
        if (addresses == null || addresses.length == 0) return new Refusal("its host does not resolve", true);

        for (InetAddress address : addresses) {
            String reason = nonPublic(address);
            if (reason != null) return new Refusal("its host resolves to " + reason, true);
        }
        return null;
    }

    static boolean proxied(URL url) {
        try {
            ProxySelector selector = ProxySelector.getDefault();
            if (selector == null) return false;
            for (Proxy proxy : selector.select(url.toURI())) {
                if (proxy != null && proxy.type() != Proxy.Type.DIRECT) return true;
            }
        } catch (Throwable ignored) {}
        return false;
    }

    static String shapeRefusal(String url) {
        if (url == null || url.isEmpty()) return "there is no address";
        if (url.length() > 4096) return "the address exceeds the size limit";
        for (int i = 0; i < url.length(); i++) {
            if (Character.isWhitespace(url.charAt(i)) || Character.isISOControl(url.charAt(i)))
                return "it is not a well-formed address";
        }
        try {
            return shapeRefusal(new URL(url));
        } catch (Throwable t) {
            return "it is not a well-formed address";
        }
    }

    static String shapeRefusal(URL url) {
        if (url.toExternalForm().length() > 4096) return "the address exceeds the size limit";
        if (!"https".equalsIgnoreCase(url.getProtocol())) return "it is not HTTPS";
        String authority = url.getAuthority();
        if (authority == null || authority.isEmpty()) return "it names no host";
        if (url.getUserInfo() != null || authority.indexOf('@') >= 0 || authority.indexOf('\\') >= 0) {
            return "it carries a user name";
        }
        if (url.getPort() != -1 && url.getPort() != 443) return "it names a port other than 443";

        String host = url.getHost();
        if (host == null || host.isEmpty()) return "it names no host";
        host = normalise(host);
        if (isLiteralAddress(host)) return "its host is a bare IP address";
        if (!isMetaMediaHost(host)) return "its host is not one of Meta's media servers";
        return null;
    }

    static boolean isMetaMediaHost(String host) {
        String name = normalise(host);
        for (String domain : META_MEDIA_DOMAINS) {
            if (name.equals(domain) || name.endsWith("." + domain)) return true;
        }
        return false;
    }

    private static String normalise(String host) {
        String name = host.toLowerCase(Locale.US);
        while (name.endsWith(".")) name = name.substring(0, name.length() - 1);
        return name;
    }

    private static boolean isLiteralAddress(String host) {
        if (host.startsWith("[") || host.indexOf(':') >= 0) return true;
        return host.matches("[0-9.]+") || host.matches("0x[0-9a-f.x]+");
    }

    static String nonPublic(InetAddress address) {
        if (address.isAnyLocalAddress()) return "an unspecified address";
        if (address.isLoopbackAddress()) return "a loopback address";
        if (address.isLinkLocalAddress()) return "a link-local address";
        if (address.isSiteLocalAddress()) return "a private address";
        if (address.isMulticastAddress()) return "a multicast address";

        byte[] b = address.getAddress();
        if (address instanceof Inet4Address) return nonPublicV4(b);
        if (address instanceof Inet6Address) return nonPublicV6(b);
        return "an address of an unknown kind";
    }

    private static String nonPublicV4(byte[] b) {
        int a0 = b[0] & 0xFF, a1 = b[1] & 0xFF, a2 = b[2] & 0xFF;
        if (a0 == 0) return "an unspecified address";
        if (a0 == 10 || (a0 == 172 && (a1 & 0xF0) == 16) || (a0 == 192 && a1 == 168)) return "a private address";
        if (a0 == 100 && (a1 & 0xC0) == 64) return "a carrier-grade NAT address";
        if (a0 == 127) return "a loopback address";
        if (a0 == 169 && a1 == 254) return "a link-local address";
        if (a0 >= 224 && a0 <= 239) return "a multicast address";
        if (a0 >= 240) return "a reserved address";
        if (a0 == 192 && a1 == 0 && (a2 == 0 || a2 == 2)) return "a reserved address";
        if (a0 == 192 && a1 == 88 && a2 == 99) return "a reserved address";
        if (a0 == 198 && a1 == 51 && a2 == 100) return "a reserved address";
        if (a0 == 203 && a1 == 0 && a2 == 113) return "a reserved address";
        return null;
    }

    private static String nonPublicV6(byte[] b) {
        int first = b[0] & 0xFF;
        boolean fakeIp = (first == 0xFC && b[1] == 0 && (b[2] & 0xC0) == 0)
                || (first == 0xFD && (b[1] & 0xFF) == 0xFE && (b[2] & 0xFF) == 0xDC && (b[3] & 0xFF) == 0xBA
                        && (b[4] & 0xFF) == 0x98 && (b[5] & 0xFF) == 0x76 && b[6] == 0 && b[7] == 0);
        if ((first & 0xFE) == 0xFC && !fakeIp) return "a private address";
        if (first == 0xFE && (b[1] & 0xC0) == 0x80) return "a link-local address";
        if (first == 0xFE && (b[1] & 0xC0) == 0xC0) return "a private address";
        if (first == 0xFF) return "a multicast address";
        if (first == 0x20 && (b[1] & 0xFF) == 0x01 && (b[2] & 0xFF) == 0x0D && (b[3] & 0xFF) == 0xB8) {
            return "a reserved address";
        }

        boolean zeros80 = true;
        for (int i = 0; i < 10; i++) if (b[i] != 0) zeros80 = false;
        if (zeros80 && (((b[10] & 0xFF) == 0xFF && (b[11] & 0xFF) == 0xFF) || (b[10] == 0 && b[11] == 0))) {
            return embedded(b, 12);
        }
        if ((b[0] & 0xFF) == 0x00 && (b[1] & 0xFF) == 0x64 && (b[2] & 0xFF) == 0xFF && (b[3] & 0xFF) == 0x9B) {
            return embedded(b, 12);
        }
        if (first == 0x20 && (b[1] & 0xFF) == 0x02) return embedded(b, 2);
        return null;
    }

    private static String embedded(byte[] b, int at) {
        byte[] v4 = { b[at], b[at + 1], b[at + 2], b[at + 3] };
        if (v4[0] == 0 && v4[1] == 0 && v4[2] == 0 && (v4[3] == 0 || v4[3] == 1)) {
            return v4[3] == 0 ? "an unspecified address" : "a loopback address";
        }
        return nonPublicV4(v4);
    }
}
