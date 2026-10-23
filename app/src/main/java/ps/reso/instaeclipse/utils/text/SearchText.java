package ps.reso.instaeclipse.utils.text;

import java.text.Normalizer;
import java.util.Locale;
import java.util.regex.Pattern;

public final class SearchText {

    private static final Pattern MARKS = Pattern.compile("\\p{Mn}+");

    private SearchText() {}

    public static String fold(String text) {
        if (text == null) return "";
        String bare = MARKS.matcher(Normalizer.normalize(text, Normalizer.Form.NFD)).replaceAll("");
        return bare.replace('\u0131', 'i').toLowerCase(Locale.ROOT);
    }
}
