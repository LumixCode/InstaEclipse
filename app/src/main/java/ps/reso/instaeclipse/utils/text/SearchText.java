package ps.reso.instaeclipse.utils.text;

import java.util.Locale;

public final class SearchText {

    private SearchText() {}

    public static String fold(String text) {
        return text == null ? "" : text.toLowerCase(Locale.getDefault());
    }
}
