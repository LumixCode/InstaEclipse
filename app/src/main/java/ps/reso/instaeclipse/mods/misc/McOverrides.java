package ps.reso.instaeclipse.mods.misc;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
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
                for (String entry : old) {
                    if (!indexes.contains(index(entry))) merged.add(entry);
                    else replaced.computeIfAbsent(config, k -> new ArrayList<>()).add(entry);
                }
            merged.addAll(e.getValue());
            out.put(config, merged);
        }
        return new Merged(out, replaced);
    }

    static Map<String, List<String>> remove(Map<String, List<String>> json, Map<String, List<String>> add,
                                            Map<String, List<String>> replaced) {
        Map<String, List<String>> out = copy(json);
        Set<String> configs = new LinkedHashSet<>(add.keySet());
        if (replaced != null) configs.addAll(replaced.keySet());
        for (String config : configs) {
            Set<String> drop = new HashSet<>(add.getOrDefault(config, Collections.emptyList()));
            List<String> kept = new ArrayList<>();
            List<String> old = out.get(config);
            if (old != null) for (String entry : old) if (!drop.contains(entry)) kept.add(entry);
            List<String> back = replaced != null ? replaced.get(config) : null;
            if (back != null) {
                Set<String> taken = new HashSet<>();
                for (String entry : kept) taken.add(index(entry));
                for (String entry : back) if (taken.add(index(entry))) kept.add(entry);
            }
            if (kept.isEmpty()) out.remove(config);
            else out.put(config, kept);
        }
        return out;
    }

    static Map<String, List<String>> replacedIn(String marker, Parser parser) {
        if (marker == null) return null;
        String text = marker.trim();
        if (!text.startsWith("{")) return new LinkedHashMap<>();
        try {
            Map<String, List<String>> replaced = parser.parse(text);
            return replaced != null ? replaced : new LinkedHashMap<>();
        } catch (Exception e) {
            return new LinkedHashMap<>();
        }
    }

    static Sync sync(boolean on, Map<String, List<String>> json, Map<String, List<String>> add,
                     Map<String, List<String>> replaced) {
        if (!on) return replaced == null ? null : new Sync(remove(json, add, replaced), null);
        if (replaced == null) {
            Merged m = merge(json, add);
            return new Sync(m.json, m.replaced);
        }
        Map<String, List<String>> missing = missing(json, add);
        if (missing.isEmpty()) return null;
        Merged m = merge(json, missing);
        return new Sync(m.json, union(replaced, m.replaced));
    }

    private static Map<String, List<String>> missing(Map<String, List<String>> json, Map<String, List<String>> add) {
        Map<String, List<String>> out = new LinkedHashMap<>();
        for (Map.Entry<String, List<String>> e : add.entrySet()) {
            List<String> have = json.getOrDefault(e.getKey(), Collections.emptyList());
            for (String entry : e.getValue())
                if (!have.contains(entry)) out.computeIfAbsent(e.getKey(), k -> new ArrayList<>()).add(entry);
        }
        return out;
    }

    private static Map<String, List<String>> union(Map<String, List<String>> a, Map<String, List<String>> b) {
        Map<String, List<String>> out = copy(a);
        for (Map.Entry<String, List<String>> e : b.entrySet()) {
            List<String> list = out.computeIfAbsent(e.getKey(), k -> new ArrayList<>());
            for (String entry : e.getValue()) if (!list.contains(entry)) list.add(entry);
        }
        return out;
    }

    private static Map<String, List<String>> copy(Map<String, List<String>> json) {
        Map<String, List<String>> out = new LinkedHashMap<>();
        for (Map.Entry<String, List<String>> e : json.entrySet()) out.put(e.getKey(), new ArrayList<>(e.getValue()));
        return out;
    }
}
