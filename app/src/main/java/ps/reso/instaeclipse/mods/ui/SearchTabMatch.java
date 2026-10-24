package ps.reso.instaeclipse.mods.ui;

final class SearchTabMatch {

    private SearchTabMatch() {}

    static boolean isSearchChild(String entryName, CharSequence description) {
        return description != null && description.toString().toLowerCase().contains("search");
    }

    static boolean found(boolean fallback, boolean present, int wired) {
        return present;
    }
}
