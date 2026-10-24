package ps.reso.instaeclipse.mods.ui;

import java.util.Locale;

final class SearchTabMatch {

    private SearchTabMatch() {}

    static boolean isSearchChild(String entryName, CharSequence description) {
        return entryName != null && entryName.toLowerCase(Locale.ROOT).contains("search");
    }

    static boolean found(boolean fallback, boolean present, int wired) {
        return present && (!fallback || wired > 0);
    }
}
