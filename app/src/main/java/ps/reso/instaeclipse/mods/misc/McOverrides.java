package ps.reso.instaeclipse.mods.misc;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

final class McOverrides {

    interface Parser {
        Map<String, List<String>> parse(String text) throws Exception;
    }

    static final class Merged {
        final Map<String, List<String>> json;
        final Map<String, List<String>> replaced;

        Merged(Map<String, List<String>> json, Map<String, List<String>> replaced) {
            this.json = json;
            this.replaced = replaced;
        }
    }

    static final class Sync {
        final Map<String, List<String>> json;
        final Map<String, List<String>> replaced;

        Sync(Map<String, List<String>> json, Map<String, List<String>> replaced) {
            this.json = json;
            this.replaced = replaced;
        }
    }

    private McOverrides() {}

    static String index(String entry) {
        int i = entry.indexOf(':');
        return i < 0 ? entry : entry.substring(0, i);
    }

    static Merged merge(Map<String, List<String>> json, Map<String, List<String>> add) {
        Map<String, List<String>> out = copy(json);
        Map<String, List<String>> replaced = new LinkedHashMap<>();
        for (Map.Entry<String, List<String>> e : add.entrySet()) {
            String config = e.getKey();
            Set<String> indexes = new HashSet<>();
            for (String entry : e.getValue()) indexes.add(index(entry));
            List<String> merged = new ArrayList<>();
            List<String> old = out.get(config);
            if (old != null)
                for (String entry : old) if (!indexes.contains(index(entry))) merged.add(entry);
            merged.addAll(e.getValue());
            out.put(config, merged);
        }
        return new Merged(out, replaced);
    }

    static Map<String, List<String>> remove(Map<String, List<String>> json, Map<String, List<String>> add,
                                            Map<String, List<String>> replaced) {
        Map<String, List<String>> out = copy(json);
        for (Map.Entry<String, List<String>> e : add.entrySet()) {
            String config = e.getKey();
            List<String> old = out.get(config);
            if (old == null) continue;
            Set<String> drop = new HashSet<>(e.getValue());
            List<String> kept = new ArrayList<>();
            for (String entry : old) if (!drop.contains(entry)) kept.add(entry);
            if (kept.isEmpty()) out.remove(config);
            else out.put(config, kept);
        }
        return out;
    }

    static Map<String, List<String>> replacedIn(String marker, Parser parser) {
        return marker == null ? null : new LinkedHashMap<>();
    }

    static Sync sync(boolean on, Map<String, List<String>> json, Map<String, List<String>> add,
                     Map<String, List<String>> replaced) {
        if (on == (replaced != null)) return null;
        if (on) {
            Merged m = merge(json, add);
            return new Sync(m.json, m.replaced);
        }
        return new Sync(remove(json, add, replaced), null);
    }

    private static Map<String, List<String>> copy(Map<String, List<String>> json) {
        Map<String, List<String>> out = new LinkedHashMap<>();
        for (Map.Entry<String, List<String>> e : json.entrySet()) out.put(e.getKey(), new ArrayList<>(e.getValue()));
        return out;
    }
}
