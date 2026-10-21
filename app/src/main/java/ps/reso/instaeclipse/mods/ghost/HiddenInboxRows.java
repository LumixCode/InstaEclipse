package ps.reso.instaeclipse.mods.ghost;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.function.Function;

/** Owns an unfiltered inbox submission; display changes never overwrite the native source. */
public final class HiddenInboxRows<T> {
    private List<T> source = new ArrayList<>();

    public synchronized void replace(List<? extends T> rows) {
        source = new ArrayList<>(rows);
    }

    public synchronized List<T> visible(Set<String> hiddenIds, boolean enabled,
                                        boolean hiddenOnly, Function<T, String> identity) {
        if (!enabled) return new ArrayList<>(source);
        return select(source, hiddenIds, hiddenOnly, identity);
    }

    public static <T> ArrayList<T> select(List<? extends T> rows, Set<String> hiddenIds,
                                         boolean hiddenOnly, Function<T, String> identity) {
        ArrayList<T> visible = new ArrayList<>(rows.size());
        for (T row : rows) {
            String id = identity.apply(row);
            boolean hidden = id != null && hiddenIds.contains(id);
            if (hidden == hiddenOnly) visible.add(row);
        }
        return visible;
    }
}
