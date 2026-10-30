package ps.reso.instaeclipse.utils.core;

import java.util.Collections;
import java.util.List;

public final class InstagramCompat {

    private static final List<String> TESTED_VERSIONS = Collections.singletonList(ExportBrand.TESTED_INSTAGRAM);

    private InstagramCompat() {}

    public static boolean isTested(String versionName) {
        return TESTED_VERSIONS.contains(versionName);
    }
}
