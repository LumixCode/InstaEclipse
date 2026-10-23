package ps.reso.instaeclipse.utils.log;

import java.nio.charset.StandardCharsets;

final class ByteNeedle {
    static final String GC_BAD_REF = "GC tried to mark invalid reference 0x";
    static final int MAX_HEX = 16;

    private final byte[] needle;
    private final int[] fallback;
    private final StringBuilder hex = new StringBuilder(MAX_HEX);
    private int matched;
    private boolean collecting;
    private boolean done;

    ByteNeedle(String ascii) {
        needle = ascii.getBytes(StandardCharsets.US_ASCII);
        if (needle.length == 0) throw new IllegalArgumentException("empty needle");
        fallback = new int[needle.length];
        for (int i = 1, k = 0; i < needle.length; i++) {
            while (k > 0 && needle[i] != needle[k]) k = fallback[k - 1];
            if (needle[i] == needle[k]) k++;
            fallback[i] = k;
        }
    }

    void feed(byte[] buf, int off, int len) {
        for (int i = off, end = off + len; i < end && !done; i++) accept(buf[i]);
    }

    private void accept(byte b) {
        if (collecting) {
            if ((b >= '0' && b <= '9') || (b >= 'a' && b <= 'f')) {
                hex.append((char) b);
                if (hex.length() == MAX_HEX) done = true;
                return;
            }
            collecting = false;
            if (hex.length() > 0) {
                done = true;
                return;
            }
        }
        while (matched > 0 && needle[matched] != b) matched = fallback[matched - 1];
        if (needle[matched] == b) matched++;
        if (matched == needle.length) {
            collecting = true;
            matched = fallback[matched - 1];
        }
    }

    String hexOrNull() {
        return hex.length() > 0 ? hex.toString() : null;
    }
}
