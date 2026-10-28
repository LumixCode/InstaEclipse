package ps.reso.instaeclipse.utils.ghost;

import java.util.List;
import java.util.Locale;

public final class QuickToggleSummary {

    private QuickToggleSummary() {}

    public static boolean opensPicker(boolean[] chosen) {
        for (boolean c : chosen) if (c) return false;
        return true;
    }

    public static boolean turnsOn(boolean[] chosen, boolean[] on) {
        for (int i = 0; i < chosen.length; i++) if (chosen[i] && on[i]) return false;
        return true;
    }

    public static String names(List<String> names) {
        String joined = String.join(", ", names);
        if (joined.isEmpty()) return joined;
        int first = joined.codePointAt(0);
        return new StringBuilder().appendCodePoint(Character.toUpperCase(first))
                .append(joined, Character.charCount(first), joined.length()).toString();
    }

    public static String message(boolean on, List<String> names, String onFormat, String offFormat) {
        return String.format(Locale.ROOT, on ? onFormat : offFormat, names(names));
    }
}
