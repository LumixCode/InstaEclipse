package ps.reso.instaeclipse.utils.text;

import java.math.BigInteger;

public final class MediaIds {

    private static final String ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_";

    private MediaIds() {}

    public static String pk(String mediaId) {
        if (mediaId == null) return null;
        return mediaId.contains("_") ? mediaId.substring(0, mediaId.indexOf('_')) : mediaId;
    }

    public static long pkLong(String mediaId) {
        try {
            return Long.parseLong(pk(mediaId));
        } catch (Throwable t) {
            return 0;
        }
    }

    public static String shortcode(String mediaId) {
        try {
            BigInteger n = new BigInteger(pk(mediaId));
            if (n.signum() <= 0) return "";
            StringBuilder b = new StringBuilder();
            BigInteger sixtyFour = BigInteger.valueOf(64);
            while (n.signum() > 0) {
                BigInteger[] qr = n.divideAndRemainder(sixtyFour);
                b.append(ALPHABET.charAt(qr[1].intValue()));
                n = qr[0];
            }
            return b.reverse().toString();
        } catch (Throwable t) {
            return "";
        }
    }

    public static String pkFromShortcode(String code) {
        if (code == null) return null;
        if (code.length() > 11) code = code.substring(0, 11);
        BigInteger pk = BigInteger.ZERO;
        for (char c : code.toCharArray()) {
            int v = ALPHABET.indexOf(c);
            if (v < 0) return null;
            pk = pk.shiftLeft(6).add(BigInteger.valueOf(v));
        }
        return pk.toString();
    }
}
