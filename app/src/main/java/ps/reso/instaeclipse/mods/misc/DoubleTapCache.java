package ps.reso.instaeclipse.mods.misc;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Predicate;

final class DoubleTapCache {

    private DoubleTapCache() {}

    static boolean shouldStoreMissing(boolean returnedNormally, int found) {
        return found == 0;
    }

    static <T> List<T> senders(List<T> callers, Predicate<T> returnsVoid, Predicate<T> mergedLambda) {
        List<T> out = new ArrayList<>();
        for (T caller : callers) if (returnsVoid.test(caller)) out.add(caller);
        return out;
    }
}
