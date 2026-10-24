package ps.reso.instaeclipse.mods.ui;

final class SpoofMetricArgs {

    static final int NONE = -1;
    private static final int NO_ID = -1;

    private SpoofMetricArgs() {}

    static int countArg(Class<?>[] params) {
        int found = NONE;
        for (int i = 0; i < params.length; i++) {
            if (params[i] != Integer.class) continue;
            if (found != NONE) return NONE;
            found = i;
        }
        return found;
    }

    static int metricArg(int[] viewIds, int followersId, int followingId) {
        for (int i = 0; i < viewIds.length; i++) {
            int id = viewIds[i];
            if (id == 0 || id == NO_ID) continue;
            if (id == followersId || id == followingId) return i;
        }
        return NONE;
    }

    static boolean replace(Object[] args, int count, boolean followersView, boolean own, int followers, int following) {
        if (!own || count < 0 || count >= args.length) return false;
        int value = followersView ? followers : following;
        if (value < 0) return false;
        args[count] = value;
        return true;
    }
}
