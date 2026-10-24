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
    private static final String[] CONFIGS = {FONT_FALLBACK_XML, FONTS_XML};
    private static final String[] FALLBACKS = {"Roboto-Regular.ttf", "NotoSans-Regular.ttf", "DroidSans.ttf"};
    private static final long MAX_CONFIG_BYTES = 1 << 20;
    private static volatile String path;

    private SystemSansFont() {}

    static String path() {
        String p = path;
        if (p == null) path = p = find(SystemSansFont::read, f -> new File(f).exists());
        return p;
    }

    static String find(Function<String, String> read, Predicate<String> exists) {
        for (String config : CONFIGS) {
            String file = regularFile(read.apply(config));
            if (file != null && exists.test(FONT_DIR + file)) return FONT_DIR + file;
        }
        for (String f : FALLBACKS) if (exists.test(FONT_DIR + f)) return FONT_DIR + f;
        return FONT_DIR + FALLBACKS[0];
    }

    private static String regularFile(String xml) {
        if (xml == null) return null;
        String body = withoutComments(xml);
        for (int at = tagStart(body, "<family", 0); at >= 0; at = tagStart(body, "<family", at + 1)) {
            int open = body.indexOf('>', at);
            if (open < 0) return null;
            String tag = body.substring(at, open);
            if (tag.endsWith("/") || !"sans-serif".equals(attribute(tag, "name"))) continue;
            int close = body.indexOf("</family>", open);
            return close < 0 ? null : regular(body.substring(open + 1, close));
        }
        return null;
    }

    private static String regular(String family) {
        String normal = null;
        for (int at = tagStart(family, "<font", 0); at >= 0; at = tagStart(family, "<font", at + 1)) {
            int open = family.indexOf('>', at);
            if (open < 0) break;
            String tag = family.substring(at, open);
            if (tag.endsWith("/")) continue;
            int end = family.indexOf('<', open + 1);
            String file = (end < 0 ? family.substring(open + 1) : family.substring(open + 1, end)).trim();
            String style = attribute(tag, "style");
            if (file.isEmpty() || (style != null && !style.equals("normal"))) continue;
            String weight = attribute(tag, "weight");
            if (weight == null || weight.trim().equals("400")) return file;
            if (normal == null) normal = file;
        }
        return normal;
    }

    private static int tagStart(String s, String open, int from) {
        for (int at = s.indexOf(open, from); at >= 0; at = s.indexOf(open, at + 1)) {
            int next = at + open.length();
            if (next >= s.length()) return -1;
            char c = s.charAt(next);
            if (Character.isWhitespace(c) || c == '>' || c == '/') return at;
        }
        return -1;
    }

    private static String attribute(String tag, String name) {
        for (int at = tag.indexOf(name, 1); at >= 0; at = tag.indexOf(name, at + 1)) {
            if (!Character.isWhitespace(tag.charAt(at - 1))) continue;
            int i = at + name.length();
            while (i < tag.length() && Character.isWhitespace(tag.charAt(i))) i++;
            if (i >= tag.length() || tag.charAt(i) != '=') continue;
            i++;
            while (i < tag.length() && Character.isWhitespace(tag.charAt(i))) i++;
            if (i >= tag.length()) return null;
            char quote = tag.charAt(i);
            if (quote != '"' && quote != '\'') return null;
            int end = tag.indexOf(quote, i + 1);
            return end < 0 ? null : tag.substring(i + 1, end);
        }
        return null;
    }

    private static String withoutComments(String xml) {
        StringBuilder out = new StringBuilder(xml.length());
        int at = 0;
        while (true) {
            int start = xml.indexOf("<!--", at);
            if (start < 0) return out.append(xml, at, xml.length()).toString();
            out.append(xml, at, start);
            int end = xml.indexOf("-->", start + 4);
            if (end < 0) return out.toString();
            at = end + 3;
        }
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
