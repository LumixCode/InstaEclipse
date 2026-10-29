package ps.reso.instaeclipse.mods.media;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Predicate;

final class VoiceMessageMenuPolicy {
    private VoiceMessageMenuPolicy() {}

    static <T> List<T> withVisibleDownload(List<T> original, T download,
                                         Predicate<T> isPrimarySave, Predicate<T> isHeader) {
        List<T> actions = new ArrayList<>();
        for (T action : original) if (!isPrimarySave.test(action)) actions.add(action);
        int position = 0;
        while (position < actions.size() && isHeader.test(actions.get(position))) position++;
        actions.add(position, download);
        return actions;
    }
}
