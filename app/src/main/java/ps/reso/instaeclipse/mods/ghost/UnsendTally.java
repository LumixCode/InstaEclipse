package ps.reso.instaeclipse.mods.ghost;

import java.util.Collection;
import java.util.Set;

final class UnsendTally {

    private final int total;
    private final Set<String> unsentIds;
    private final Set<String> pendingIds;
    private int unsent;

    UnsendTally(int total, Set<String> unsentIds, Set<String> pendingIds) {
        this.total = total;
        this.unsentIds = unsentIds;
        this.pendingIds = pendingIds;
    }

    static boolean sameChat(String openThread, String messageThread) {
        return messageThread != null && (openThread == null || openThread.equals(messageThread));
    }

    static boolean unsent(Object result) {
        return Boolean.TRUE.equals(result);
    }

    void begin(Collection<String> ids) {
        pendingIds.addAll(ids);
    }

    boolean finish(Collection<String> ids, Object result) {
        pendingIds.removeAll(ids);
        if (!unsent(result)) return false;
        unsentIds.addAll(ids);
        unsent++;
        return true;
    }

    int unsent() {
        return unsent;
    }

    int total() {
        return total;
    }
}
