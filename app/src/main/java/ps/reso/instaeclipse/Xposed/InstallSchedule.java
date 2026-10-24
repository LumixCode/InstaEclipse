package ps.reso.instaeclipse.Xposed;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Predicate;

import ps.reso.instaeclipse.utils.core.DexKitStats;

public final class InstallSchedule {

    public static final String HEADER = "step\tstate\tkeys";
    public static final String NO_ROW = "no saved key list for its switches";
    public static final String NO_MARKER = "not set up before";
    public static final String OTHER_STATE = "set up with other switches";
    public static final String MISSING = "missing ";
    public static final String LEGACY = "legacy order";

    private static final String MARKER_PREFIX = "q_step_";
    private static final String LEARNED_PREFIX = "q_keys_";

    private InstallSchedule() {}

    public interface Rows {
        List<String> keys(String step, String state);
    }

    public interface Store {
        String get(String key);

        boolean has(String storedKey);

        void put(String key, String value);
    }

    public static final class StepKeys implements Rows {

        public static final StepKeys EMPTY = new StepKeys(Collections.emptyMap());

        private final Map<String, Map<String, List<String>>> rows;

        public StepKeys(Map<String, Map<String, List<String>>> rows) {
            Map<String, Map<String, List<String>>> copy = new TreeMap<>();
            for (Map.Entry<String, Map<String, List<String>>> step : rows.entrySet()) {
                Map<String, List<String>> states = new TreeMap<>();
                for (Map.Entry<String, List<String>> state : step.getValue().entrySet()) {
                    states.put(state.getKey(), Collections.unmodifiableList(new ArrayList<>(new TreeSet<>(state.getValue()))));
                }
                copy.put(step.getKey(), Collections.unmodifiableMap(states));
            }
            this.rows = Collections.unmodifiableMap(copy);
        }

        public static StepKeys parse(String text) {
            Map<String, Map<String, List<String>>> rows = new HashMap<>();
            if (text == null) return EMPTY;
            for (String raw : text.split("\n")) {
                String line = raw.endsWith("\r") ? raw.substring(0, raw.length() - 1) : raw;
                if (line.isEmpty() || HEADER.equals(line)) continue;
                String[] p = line.split("\t", -1);
                if (p.length != 3 || p[0].isEmpty()) continue;
                rows.computeIfAbsent(p[0], k -> new HashMap<>()).put(p[1], split(p[2]));
            }
            return new StepKeys(rows);
        }

        @Override
        public List<String> keys(String step, String state) {
            Map<String, List<String>> states = rows.get(step);
            return states == null ? null : states.get(state);
        }

        public Map<String, Map<String, List<String>>> rows() {
            return rows;
        }

        public String text() {
            StringBuilder sb = new StringBuilder(HEADER).append('\n');
            for (Map.Entry<String, Map<String, List<String>>> step : rows.entrySet()) {
                for (Map.Entry<String, List<String>> state : step.getValue().entrySet()) {
                    sb.append(step.getKey()).append('\t').append(state.getKey()).append('\t')
                            .append(String.join(",", state.getValue())).append('\n');
                }
            }
            return sb.toString();
        }
    }

    public static final class Split {
        public final List<InstallStep> inline;
        public final List<InstallStep> background;
        public final List<String> backgroundOpen;
        public final Set<String> unknown;
        public final String firstBackground;
        public final String reason;
        public final boolean legacy;
        final Rows rows;

        Split(List<InstallStep> inline, List<InstallStep> background, List<String> backgroundOpen,
              Set<String> unknown, String firstBackground, String reason, boolean legacy, Rows rows) {
            this.inline = Collections.unmodifiableList(inline);
            this.background = Collections.unmodifiableList(background);
            this.backgroundOpen = Collections.unmodifiableList(backgroundOpen);
            this.unknown = Collections.unmodifiableSet(unknown);
            this.firstBackground = firstBackground;
            this.reason = reason;
            this.legacy = legacy;
            this.rows = rows;
        }

        boolean rowMayBeStale() {
            return reason != null && reason.startsWith(MISSING);
        }
    }

    public static Split split(List<InstallStep> steps, Predicate<InstallStep> gateOpen,
                              Function<InstallStep, String> lookupState, Function<String, String> markers,
                              Rows stepKeys, Predicate<String> present) {
        List<InstallStep> inline = new ArrayList<>();
        List<InstallStep> background = new ArrayList<>();
        List<String> open = new ArrayList<>();
        Set<String> unknown = new LinkedHashSet<>();
        String first = null;
        String reason = null;
        for (InstallStep step : steps) {
            if (step.phaseA) {
                inline.add(step);
                continue;
            }
            boolean isOpen = gateOpen.test(step);
            List<String> row = null;
            String state = null;
            if (isOpen) {
                state = lookupState.apply(step);
                row = stepKeys.keys(step.name, state);
                if (row == null) unknown.add(step.name);
            }
            if (first == null) {
                String why = isOpen ? invalid(state, row, markers.apply(step.name), present) : null;
                if (why == null) {
                    inline.add(step);
                    continue;
                }
                first = step.name;
                reason = why;
            }
            background.add(step);
            if (isOpen) open.add(step.name);
        }
        return new Split(inline, background, open, unknown, first, reason, false, stepKeys);
    }

    static String invalid(String state, List<String> row, String marker, Predicate<String> present) {
        if (row == null) return NO_ROW;
        if (marker == null) return NO_MARKER;
        if (!marker.equals(state)) return OTHER_STATE;
        for (String key : row) if (!present.test(key)) return MISSING + key;
        return null;
    }

    public static Split legacy(List<InstallStep> steps, boolean installedBefore) {
        List<InstallStep> all = new ArrayList<>(steps);
        if (installedBefore || all.isEmpty()) {
            return new Split(all, new ArrayList<>(), new ArrayList<>(), new LinkedHashSet<>(), null, null, true, null);
        }
        List<String> names = new ArrayList<>();
        for (InstallStep step : all) names.add(step.name);
        return new Split(new ArrayList<>(), all, names, new LinkedHashSet<>(), all.get(0).name, LEGACY, true, null);
    }

    public static String lookupState(InstallStep step, Function<String, Object> flags) {
        StringBuilder sb = new StringBuilder();
        for (String name : step.lookupFlags()) {
            if (sb.length() > 0) sb.append(',');
            sb.append(name).append('=').append(flags.apply(name));
        }
        return sb.toString();
    }

    public static String markerKey(String step) {
        return MARKER_PREFIX + step;
    }

    public static String learnedKey(String step, String state) {
        return LEARNED_PREFIX + step + "|" + state;
    }

    public static boolean isScheduleKey(String storedKey) {
        return storedKey.startsWith("s_" + MARKER_PREFIX) || storedKey.startsWith("s_" + LEARNED_PREFIX);
    }

    public static String storedKey(String readKey) {
        return readKey.startsWith("q_") ? "s_" + readKey : readKey;
    }

    public static Rows withLearned(Rows asset, Function<String, String> stored, String build) {
        return (step, state) -> {
            List<String> keys = learned(stored.apply(learnedKey(step, state)), build);
            return keys != null ? keys : asset.keys(step, state);
        };
    }

    static List<String> learned(String value, String build) {
        if (value == null) return null;
        int newline = value.indexOf('\n');
        if (newline < 0 || !value.substring(0, newline).equals(build)) return null;
        return split(value.substring(newline + 1).trim());
    }

    static String learnedValue(String build, Collection<String> keys) {
        return build + "\n" + String.join(",", keys);
    }

    public static List<String> presentKeys(Collection<String> readKeys, Predicate<String> present) {
        Set<String> out = new TreeSet<>();
        for (String key : readKeys) {
            String stored = storedKey(key);
            if (present.test(stored)) out.add(stored);
        }
        return new ArrayList<>(out);
    }

    private static List<String> split(String joined) {
        return joined.isEmpty() ? Collections.emptyList() : Collections.unmodifiableList(Arrays.asList(joined.split(",")));
    }

    public static final class Book implements InstallRunner.Ledger {
        private final Store store;
        private final Function<InstallStep, String> lookupState;
        private final Set<String> unknown;
        private final Rows rows;
        private final boolean checkRows;
        private final String build;
        private final Consumer<String> log;

        public Book(Store store, Split split, Function<InstallStep, String> lookupState, String build, Consumer<String> log) {
            this.store = store;
            this.lookupState = lookupState;
            this.unknown = split.unknown;
            this.rows = split.rows;
            this.checkRows = split.rowMayBeStale() && split.rows != null;
            this.build = build;
            this.log = log;
        }

        @Override
        public boolean wantsKeys(InstallStep step) {
            return unknown.contains(step.name) || (checkRows && !step.phaseA);
        }

        @Override
        public void ran(InstallStep step, String segment, long searches, Collection<String> keys) {
            if (step.phaseA) return;
            String marker = markerKey(step.name);
            if (DexKitStats.INLINE.equals(segment) && searches > 0) {
                store.put(marker, null);
                log.accept("(IE|Startup) " + step.name + " searched Instagram's code during the start, so it is set up in the background next time");
                return;
            }
            String state = lookupState.apply(step);
            if (keys != null) {
                List<String> read = presentKeys(keys, store::has);
                if (unknown.contains(step.name)) {
                    store.put(learnedKey(step.name, state), learnedValue(build, read));
                } else {
                    List<String> row = rows.keys(step.name, state);
                    if (row != null && !row.equals(read)) {
                        store.put(learnedKey(step.name, state), learnedValue(build, read));
                        log.accept("(IE|Startup) " + step.name + " read other saved lookups than its list, so this build uses the ones it read from now on");
                    }
                }
            }
            if (!state.equals(store.get(marker))) store.put(marker, state);
        }
    }
}
