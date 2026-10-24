package ps.reso.instaeclipse.mods.ui;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.function.Function;
import java.util.function.Predicate;

final class SystemSansFont {
    static final String FONT_DIR = "/system/fonts/";
    static final String FONT_FALLBACK_XML = "/system/etc/font_fallback.xml";
    static final String FONTS_XML = "/system/etc/fonts.xml";
    private static final String[] FALLBACKS = {"Roboto-Regular.ttf", "NotoSans-Regular.ttf", "DroidSans.ttf"};
    private static final long MAX_CONFIG_BYTES = 1 << 20;

    private SystemSansFont() {}

    static String path() {
        return find(SystemSansFont::read, p -> new File(p).exists());
    }

    static String find(Function<String, String> read, Predicate<String> exists) {
        for (String f : FALLBACKS) if (exists.test(FONT_DIR + f)) return FONT_DIR + f;
        return FONT_DIR + FALLBACKS[0];
    }

    private static String read(String path) {
        try {
            File f = new File(path);
            if (!f.isFile() || f.length() > MAX_CONFIG_BYTES) return null;
            return new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8);
        } catch (Throwable t) {
            return null;
        }
    }
}
