package ps.reso.instaeclipse.mods.ghost;

/** Where a saved unsent message goes back into a thread's sorted, partially loaded message list. */
final class UnsentRestorePolicy {
    private UnsentRestorePolicy() {}

    /**
     * Index that keeps {@code loaded} sorted, or -1 when the message is older than everything
     * loaded. Older history is fetched page by page; restoring past the oldest loaded message would
     * show it next to newer messages before the ones in between have loaded.
     */
    static int restoreIndex(long[] loaded, long time) {
        if (loaded.length < 2 || loaded[0] == loaded[loaded.length - 1]) return -1;
        boolean newestFirst = loaded[0] > loaded[loaded.length - 1];
        long oldest = newestFirst ? loaded[loaded.length - 1] : loaded[0];
        if (time < oldest) return -1;
        for (int i = 0; i < loaded.length; i++) {
            if (newestFirst ? loaded[i] < time : loaded[i] > time) return i;
        }
        return loaded.length;
    }
}
