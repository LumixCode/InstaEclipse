package ps.reso.instaeclipse.utils.core;

import java.io.File;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

public final class CacheSweep {

    private CacheSweep() {}

    public static List<File> select(File cacheDir, File externalCacheDir, File filesDir) {
        Set<File> out = new LinkedHashSet<>();
        out.addAll(children(cacheDir));
        out.addAll(children(externalCacheDir));
        for (File k : children(filesDir)) {
            if (k.isDirectory() && k.getName().toLowerCase().contains("cache")) out.addAll(children(k));
        }
        return new ArrayList<>(out);
    }

    static List<File> children(File dir) {
        List<File> out = new ArrayList<>();
        File[] kids = dir != null ? dir.listFiles() : null;
        if (kids != null) Collections.addAll(out, kids);
        return out;
    }

    public static long size(Collection<File> entries) {
        long total = 0;
        for (File f : entries) total += sizeOf(f);
        return total;
    }

    private static long sizeOf(File f) {
        if (!f.isDirectory()) return f.length();
        long total = 0;
        File[] kids = f.listFiles();
        if (kids != null) for (File k : kids) total += sizeOf(k);
        return total;
    }

    public static void delete(Collection<File> entries) {
        for (File f : entries) deleteRecursive(f);
    }

    private static void deleteRecursive(File f) {
        if (f.isDirectory()) {
            File[] c = f.listFiles();
            if (c != null) for (File x : c) deleteRecursive(x);
        }
        //noinspection ResultOfMethodCallIgnored
        f.delete();
    }

    public static final class ForegroundCount {
        private int started;

        public void started() {
            started++;
        }

        public boolean stopped(boolean changingConfigurations) {
            started--;
            if (started > 0) return false;
            started = 0;
            return true;
        }
    }
}
