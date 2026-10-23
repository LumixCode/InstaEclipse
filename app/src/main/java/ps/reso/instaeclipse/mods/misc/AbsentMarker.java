package ps.reso.instaeclipse.mods.misc;

final class AbsentMarker {
    static final String ABSENT = "1";

    private AbsentMarker() {}

    static String key(String cacheKey) {
        return cacheKey + "_absent";
    }

    static boolean knownAbsent(boolean cacheValid, String saved) {
        return cacheValid && ABSENT.equals(saved);
    }
}
