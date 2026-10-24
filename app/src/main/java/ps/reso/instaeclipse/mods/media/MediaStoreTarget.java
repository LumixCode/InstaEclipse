package ps.reso.instaeclipse.mods.media;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

public final class MediaStoreTarget {
    enum Collection { DOWNLOADS, IMAGES, VIDEO, AUDIO }

    public static final String DEFAULT_FOLDER = "Download/InstaEclipse";

    private static final Set<String> IMAGE_ROOTS = new HashSet<>(Arrays.asList("Pictures", "DCIM"));
    private static final Set<String> VIDEO_ROOTS = new HashSet<>(Arrays.asList("Movies", "DCIM", "Pictures"));
    private static final Set<String> AUDIO_ROOTS = new HashSet<>(Arrays.asList("Music", "Podcasts", "Ringtones",
            "Alarms", "Notifications"));

    final Collection collection;
    final String relativePath;
    final String displayPath;

    private MediaStoreTarget(Collection collection, String relativePath) {
        this.collection = collection;
        this.relativePath = relativePath;
        this.displayPath = relativePath;
    }

    static MediaStoreTarget of(String customPath, String externalRoot, String owner, boolean usernameFolder,
            String mime) {
        String relative = relative(customPath, externalRoot);
        Collection collection = relative == null ? Collection.DOWNLOADS : collection(top(relative), mime);
        String base = relative == null ? DEFAULT_FOLDER
                : collection == null ? "Download/" + relative : relative;
        if (usernameFolder && owner != null && !owner.isEmpty()) base += "/" + owner;
        return new MediaStoreTarget(collection == null ? Collection.DOWNLOADS : collection, base);
    }

    static boolean reachesChosenFolder(String customPath, String externalRoot, String mime) {
        String relative = relative(customPath, externalRoot);
        return relative != null && collection(top(relative), mime) != null;
    }

    private static Collection collection(String top, String mime) {
        if (top.equals("Download")) return Collection.DOWNLOADS;
        if (mime == null) return null;
        if (mime.startsWith("image/") && IMAGE_ROOTS.contains(top)) return Collection.IMAGES;
        if (mime.startsWith("video/") && VIDEO_ROOTS.contains(top)) return Collection.VIDEO;
        if (mime.startsWith("audio/") && AUDIO_ROOTS.contains(top)) return Collection.AUDIO;
        return null;
    }

    private static String top(String relative) {
        int slash = relative.indexOf('/');
        return slash < 0 ? relative : relative.substring(0, slash);
    }

    private static String relative(String customPath, String externalRoot) {
        if (customPath == null || customPath.startsWith("content://") || !customPath.startsWith(externalRoot + "/"))
            return null;
        String relative = customPath.substring(externalRoot.length() + 1);
        while (relative.endsWith("/")) relative = relative.substring(0, relative.length() - 1);
        return relative.isEmpty() ? null : relative;
    }
}
