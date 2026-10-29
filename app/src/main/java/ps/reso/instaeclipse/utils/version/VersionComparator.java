package ps.reso.instaeclipse.utils.version;

public final class VersionComparator {

    private static final String FORK = "-lumix.";

    private VersionComparator() {}

    public static int compare(String a, String b) {
        int[] x = parse(a);
        int[] y = parse(b);
        int n = Math.max(x.length, y.length) - 1;
        for (int i = 0; i < n; i++) {
            int xi = i < x.length - 1 ? x[i] : 0;
            int yi = i < y.length - 1 ? y[i] : 0;
            if (xi != yi) return Integer.compare(xi, yi);
        }
        return Integer.compare(x[x.length - 1], y[y.length - 1]);
    }

    private static int[] parse(String v) {
        if (v == null) return new int[]{0};
        String s = v.trim();
        if (s.startsWith("v") || s.startsWith("V")) s = s.substring(1);
        int cut = s.length();
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c != '.' && !Character.isDigit(c)) { cut = i; break; }
        }
        int fork = s.startsWith(FORK, cut) ? number(s, cut + FORK.length()) : 0;
        s = s.substring(0, cut);
        String[] parts = s.isEmpty() ? new String[0] : s.split("\\.");
        int[] out = new int[parts.length + 1];
        for (int i = 0; i < parts.length; i++) {
            try {
                out[i] = parts[i].isEmpty() ? 0 : Integer.parseInt(parts[i]);
            } catch (NumberFormatException e) {
                out[i] = 0;
            }
        }
        out[parts.length] = fork;
        return out;
    }

    private static int number(String s, int from) {
        int end = from;
        while (end < s.length() && Character.isDigit(s.charAt(end))) end++;
        if (end == from) return 0;
        try {
            return Integer.parseInt(s.substring(from, end));
        } catch (NumberFormatException e) {
            return 0;
        }
    }
}
