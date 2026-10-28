package ps.reso.instaeclipse.utils.core;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Collections;
import java.util.Enumeration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.CRC32;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import ps.reso.instaeclipse.utils.log.ModuleLog;

public final class DexKitSeed {

    public static final String FILE_NAME = "ig451-queries.tsv";
    public static final String ASSET = "assets/dexkit/" + FILE_NAME;
    static final int MAX_ASSET_BYTES = 1 << 20;
    private static final Pattern DEX = Pattern.compile("classes([2-9]|[1-9]\\d{1,3})?\\.dex");
    private static final String[] SEARCH_PREFIXES = {"q_fm_", "q_fc_", "q_cl_"};
    private static final String TAG = "(IE|Startup) ";

    private static volatile boolean enabled = true;
    private static volatile int lastImported = -1;

    private DexKitSeed() {}

    public static void enableForTest(boolean on) {
        enabled = on;
        lastImported = -1;
    }

    public static int lastImported() {
        return lastImported;
    }

    public static boolean isSearchKey(String key) {
        if (key == null || key.indexOf('\t') >= 0 || key.indexOf('\n') >= 0 || key.indexOf('\r') >= 0) return false;
        for (String prefix : SEARCH_PREFIXES) {
            if (key.startsWith(prefix) && key.length() > prefix.length()) return true;
        }
        return false;
    }

    public static long dexCrc(ZipFile zip) {
        TreeMap<Integer, Long> crcs = new TreeMap<>();
        Enumeration<? extends ZipEntry> entries = zip.entries();
        while (entries.hasMoreElements()) {
            ZipEntry entry = entries.nextElement();
            Matcher m = DEX.matcher(entry.getName());
            if (!m.matches() || entry.getCrc() < 0) continue;
            crcs.put(m.group(1) == null ? 1 : Integer.parseInt(m.group(1)), entry.getCrc());
        }
        if (crcs.isEmpty()) return -1;
        CRC32 crc = new CRC32();
        for (long value : crcs.values()) {
            crc.update(new byte[]{(byte) (value >>> 24), (byte) (value >>> 16), (byte) (value >>> 8), (byte) value});
        }
        return crc.getValue();
    }

    public static String header(long versionCode, long crc) {
        return versionCode + "\t" + String.format(Locale.ROOT, "%08x", crc);
    }

    public static String format(long versionCode, long crc, Map<String, ?> searches) {
        StringBuilder out = new StringBuilder(header(versionCode, crc)).append('\n');
        for (Map.Entry<String, ?> e : new TreeMap<>(searches).entrySet()) {
            if (!isSearchKey(e.getKey()) || !(e.getValue() instanceof String)) continue;
            List<String> descriptors = DexKitQueryCache.decodeResult((String) e.getValue());
            if (!valid(e.getKey(), descriptors)) continue;
            out.append(e.getKey()).append('\t').append(String.join("\t", descriptors)).append('\n');
        }
        return out.toString();
    }

    public static Map<String, String> parse(String text, long versionCode, long crc) {
        if (text == null) return null;
        String[] lines = text.split("\n", -1);
        if (!strip(lines[0]).equals(header(versionCode, crc))) return null;
        Map<String, String> out = new LinkedHashMap<>();
        for (int i = 1; i < lines.length; i++) {
            String line = strip(lines[i]);
            int tab = line.indexOf('\t');
            if (tab <= 0) continue;
            String key = line.substring(0, tab);
            if (!isSearchKey(key) || out.containsKey(key)) continue;
            String value = line.substring(tab + 1);
            List<String> descriptors = value.isEmpty() ? Collections.emptyList() : Arrays.asList(value.split("\t", -1));
            if (!valid(key, descriptors)) continue;
            out.put(key, DexKitQueryCache.encodeResult(descriptors));
        }
        return out;
    }

    public static int importIfEmpty(String moduleApk, String instagramApk) {
        if (!enabled || moduleApk == null || instagramApk == null || DexKitStats.onMainThread()) return 0;
        long versionCode = DexKitCache.seedVersionCode();
        if (versionCode < 0 || !DexKitCache.acceptsSeed()) return 0;
        long start = System.nanoTime();
        String text;
        long crc;
        try {
            text = readAsset(moduleApk);
            if (text == null) return 0;
            try (ZipFile zip = new ZipFile(instagramApk)) {
                crc = dexCrc(zip);
            }
        } catch (IOException e) {
            ModuleLog.detail(TAG + "The bundled lookup table could not be read, so Instagram's code is searched: " + e);
            return 0;
        }
        Map<String, String> entries = parse(text, versionCode, crc);
        if (entries == null) {
            ModuleLog.detail(TAG + "The bundled lookup table is for another Instagram build, so Instagram's code is searched");
            return 0;
        }
        int imported = DexKitCache.importSearches(entries);
        lastImported = imported;
        ModuleLog.detail(TAG + "Imported " + imported + " saved searches for this Instagram build from the bundled table in "
                + (System.nanoTime() - start) / 1_000_000L + " ms");
        return imported;
    }

    static String readAsset(String moduleApk) throws IOException {
        try (ZipFile zip = new ZipFile(moduleApk)) {
            ZipEntry entry = zip.getEntry(ASSET);
            if (entry == null) return null;
            try (InputStream in = zip.getInputStream(entry)) {
                ByteArrayOutputStream out = new ByteArrayOutputStream();
                byte[] buffer = new byte[8192];
                for (int n; (n = in.read(buffer)) > 0; ) {
                    out.write(buffer, 0, n);
                    if (out.size() > MAX_ASSET_BYTES) return null;
                }
                return new String(out.toByteArray(), StandardCharsets.UTF_8);
            }
        }
    }

    private static boolean valid(String key, List<String> descriptors) {
        try {
            for (String d : descriptors) {
                if (d.isEmpty()) return false;
                if (key.startsWith("q_fc_")) new DexKitQueryCache.FoundClass(d);
                else new DexKitQueryCache.Found(d);
            }
            return true;
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    private static String strip(String line) {
        return line.endsWith("\r") ? line.substring(0, line.length() - 1) : line;
    }
}
