package ps.reso.instaeclipse.utils.version;

import java.util.Map;

public final class ReleaseInfo {

    public final String version;
    public final String url;
    public final String body;

    private ReleaseInfo(String version, String url, String body) {
        this.version = version;
        this.url = url;
        this.body = body;
    }

    public static ReleaseInfo of(String tag, String url, String body, String repo) {
        if (tag == null) return null;
        String version = tag.trim();
        if (version.startsWith("v") || version.startsWith("V")) version = version.substring(1);
        if (version.isEmpty()) return null;
        String releases = "https://github.com/" + repo + "/releases/";
        String safe = url != null && url.startsWith(releases) ? url : releases + "latest";
        return new ReleaseInfo(version, safe, body);
    }

    public static ReleaseInfo from(Map<String, ?> release, String repo) {
        if (release == null) return null;
        if (Boolean.TRUE.equals(release.get("draft")) || Boolean.TRUE.equals(release.get("prerelease"))) return null;
        return of(text(release.get("tag_name")), text(release.get("html_url")), text(release.get("body")), repo);
    }

    private static String text(Object value) {
        return value instanceof String ? (String) value : null;
    }
}
