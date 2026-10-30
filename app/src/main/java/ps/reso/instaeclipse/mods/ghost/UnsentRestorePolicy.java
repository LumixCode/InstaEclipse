package ps.reso.instaeclipse.mods.ghost;

final class UnsentRestorePolicy {
    private UnsentRestorePolicy() {}

    static int restoreIndex(long[] loaded, long time, Boolean learned) {
        if (loaded.length == 0) return 0;
        Boolean own = direction(loaded);
        Boolean newestFirst = own != null ? own : learned;
        if (newestFirst == null) return -1;
        long oldest = newestFirst ? loaded[loaded.length - 1] : loaded[0];
        if (time < oldest) return -1;
        for (int i = 0; i < loaded.length; i++) {
            if (newestFirst ? loaded[i] < time : loaded[i] > time) return i;
        }
        return loaded.length;
    }

    static int keptIndex(long[] loaded, long time, Boolean learned) {
        Boolean own = direction(loaded);
        boolean newestFirst = own != null ? own : Boolean.TRUE.equals(learned);
        for (int i = 0; i < loaded.length; i++) {
            if (newestFirst ? loaded[i] < time : loaded[i] > time) return i;
        }
        return loaded.length;
    }

    static Boolean direction(long[] loaded) {
        if (loaded.length < 2 || loaded[0] == loaded[loaded.length - 1]) return null;
        return loaded[0] > loaded[loaded.length - 1];
    }

    static Boolean learn(long[] loaded) {
        boolean newer = false, older = false;
        for (int i = 1; i < loaded.length; i++) {
            if (loaded[i] > loaded[i - 1]) newer = true;
            else if (loaded[i] < loaded[i - 1]) older = true;
        }
        return newer == older ? null : older;
    }
}
