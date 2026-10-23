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
        String prefix = "https://github.com/" + repo + "/";
        String safe = url != null && url.startsWith(prefix) ? url : prefix + "releases/latest";
        return new ReleaseInfo(tag, safe, body);
    }

    public static ReleaseInfo from(Map<String, ?> release, String repo) {
        if (release == null) return null;
        return of(text(release.get("tag_name")), text(release.get("html_url")), text(release.get("body")), repo);
    }

    private static String text(Object value) {
        return value instanceof String ? (String) value : null;
    }
}
