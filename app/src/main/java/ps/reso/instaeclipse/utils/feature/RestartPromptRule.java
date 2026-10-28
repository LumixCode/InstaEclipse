package ps.reso.instaeclipse.utils.feature;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.IntFunction;

public final class RestartPromptRule {

    public static final String LOCATION = "spoofLocation";
    public static final int MAX_LABELS = 3;

    private RestartPromptRule() {}

    public static List<String> toPrompt(Collection<String> applied, Set<String> pending, Set<String> alreadyShown) {
        if (applied == null || pending == null || pending.isEmpty()) return new ArrayList<>();
        Set<String> keys = new TreeSet<>();
        for (String key : applied) if (key != null && pending.contains(key)) keys.add(key);
        if (keys.isEmpty()) return new ArrayList<>();
        List<String> ordered = order(keys);
        if (alreadyShown != null && alreadyShown.contains(signature(ordered))) return new ArrayList<>();
        return ordered;
    }

    public static List<String> order(Collection<String> keys) {
        Set<String> sorted = new TreeSet<>();
        if (keys != null) for (String key : keys) if (key != null) sorted.add(key);
        List<String> out = new ArrayList<>();
        if (sorted.remove(LOCATION)) out.add(LOCATION);
        out.addAll(sorted);
        return out;
    }

    public static String signature(Collection<String> keys) {
        return String.join(",", order(keys));
    }

    public static String format(Collection<String> pending) {
        Set<String> sorted = new TreeSet<>();
        if (pending != null) for (String key : pending) if (key != null && !key.isEmpty()) sorted.add(key);
        return String.join(",", sorted);
    }

    public static Set<String> parse(Object stored) {
        Set<String> out = new TreeSet<>();
        if (!(stored instanceof String)) return out;
        for (String key : ((String) stored).split(",")) {
            String k = key.trim();
            if (!k.isEmpty()) out.add(k);
        }
        return out;
    }

    public static String labelKey(String key) {
        if ("customFontPath".equals(key)) return "customFontEnabled";
        if ("customEmojiPath".equals(key)) return "customEmojiEnabled";
        return key;
    }

    public static Set<String> rowKeys(Collection<String> pending) {
        Set<String> out = new TreeSet<>();
        if (pending != null) for (String key : pending) if (key != null) out.add(labelKey(key));
        return out;
    }

    public static String labels(List<String> labels, IntFunction<String> more) {
        List<String> unique = new ArrayList<>(new LinkedHashSet<>(labels));
        if (unique.size() <= MAX_LABELS) return String.join(", ", unique);
        return String.join(", ", unique.subList(0, MAX_LABELS)) + " " + more.apply(unique.size() - MAX_LABELS);
    }
}
