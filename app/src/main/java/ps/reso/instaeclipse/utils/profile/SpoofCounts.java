package ps.reso.instaeclipse.utils.profile;

public final class SpoofCounts {

    private SpoofCounts() {}

    public static int parse(String s) {
        try {
            long v = Long.parseLong(s.trim());
            return v < 0 ? -1 : (int) Math.min(v, Integer.MAX_VALUE);
        } catch (Throwable t) {
            return -1;
        }
    }
}
