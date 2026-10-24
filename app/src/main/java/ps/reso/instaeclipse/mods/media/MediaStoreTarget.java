package ps.reso.instaeclipse.mods.media;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

final class MediaStoreTarget {
    enum Collection { DOWNLOADS, IMAGES, VIDEO, AUDIO }

    static final String DEFAULT_FOLDER = "Download/InstaEclipse";

    private static final Set<String> MEDIA_ROOTS = new HashSet<>(Arrays.asList("Download", "Downloads",
            "Pictures", "DCIM", "Movies", "Music", "Ringtones", "Alarms", "Notifications", "Podcasts", "Audiobooks"));

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
        String base = DEFAULT_FOLDER;
        String relative = relative(customPath, externalRoot);
        if (relative != null) base = MEDIA_ROOTS.contains(relative.split("/")[0]) ? relative : "Download/" + relative;
        if (usernameFolder && owner != null && !owner.isEmpty()) base += "/" + owner;
        return new MediaStoreTarget(Collection.DOWNLOADS, base);
    }

    static boolean reachesChosenFolder(String customPath, String externalRoot, String mime) {
        String relative = relative(customPath, externalRoot);
        return relative != null && relative.split("/")[0].equals("Download");
    }

    private static String relative(String customPath, String externalRoot) {
        if (customPath == null || customPath.startsWith("content://") || !customPath.startsWith(externalRoot + "/"))
            return null;
        return customPath.substring(externalRoot.length() + 1);
    }
}
