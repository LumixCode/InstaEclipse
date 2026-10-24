package ps.reso.instaeclipse.utils.core;

import android.os.Looper;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class DexKitStats {

    public static final String INLINE = "inline";
    public static final String BACKGROUND = "background";
    public static final String NONE = "(none)";

    public static final class Totals {
        public final long queryHits;
        public final long queryMisses;
        public final long scanNanos;
        public final long nativeCalls;
        public final long mainThreadScans;

        public Totals(long queryHits, long queryMisses, long scanNanos, long nativeCalls, long mainThreadScans) {
            this.queryHits = queryHits;
            this.queryMisses = queryMisses;
            this.scanNanos = scanNanos;
            this.nativeCalls = nativeCalls;
            this.mainThreadScans = mainThreadScans;
        }

        public Totals minus(Totals o) {
            return new Totals(queryHits - o.queryHits, queryMisses - o.queryMisses, scanNanos - o.scanNanos,
                    nativeCalls - o.nativeCalls, mainThreadScans - o.mainThreadScans);
        }

        public Totals plus(Totals o) {
            return new Totals(queryHits + o.queryHits, queryMisses + o.queryMisses, scanNanos + o.scanNanos,
                    nativeCalls + o.nativeCalls, mainThreadScans + o.mainThreadScans);
        }
    }

    public static final class Row {
        public final String step;
        public final String segment;
        public final boolean mainThread;
        public final Totals totals;

        Row(String step, String segment, boolean mainThread, Totals totals) {
            this.step = step;
            this.segment = segment;
            this.mainThread = mainThread;
            this.totals = totals;
        }
    }

    private static final class Counter {
        long queryHits;
        long queryMisses;
        long scanNanos;
        long nativeCalls;
        long mainThreadScans;

        Totals totals() {
            return new Totals(queryHits, queryMisses, scanNanos, nativeCalls, mainThreadScans);
        }
    }

    private static final ThreadLocal<String> STEP = new ThreadLocal<>();
    private static final ThreadLocal<String> SEGMENT = new ThreadLocal<>();
    private static final ThreadLocal<Collection<String>> SINK = new ThreadLocal<>();
    private static final Map<String, Counter> COUNTERS = new LinkedHashMap<>();
    private static final Counter TOTAL = new Counter();
    private static final Map<String, List<String>> KEYS = new LinkedHashMap<>();
    private static long bridgeCreates;
    private static long mainThreadNative;
    private static volatile boolean recordKeys;
    private static volatile Thread mainThread;
    private static volatile boolean mainThreadResolved;

    private DexKitStats() {}

    public static void setSegment(String segment) {
        if (segment == null) SEGMENT.remove();
        else SEGMENT.set(segment);
    }

    public static String segment() {
        String s = SEGMENT.get();
        return s == null ? NONE : s;
    }

    public static void enterStep(String step) {
        if (step == null) STEP.remove();
        else STEP.set(step);
    }

    public static void exitStep() {
        STEP.remove();
    }

    public static String currentStep() {
        String s = STEP.get();
        return s == null ? NONE : s;
    }

    public static void hit() {
        add(1, 0, 0, 0, false);
    }

    public static void miss(long scanNanos) {
        add(0, 1, scanNanos, 1, true);
    }

    public static void nativeCalls(int calls) {
        if (calls > 0) add(0, 0, 0, calls, false);
    }

    public static void libraryLoaded() {
        boolean main = onMainThread();
        synchronized (COUNTERS) {
            if (main) mainThreadNative++;
        }
    }

    public static void bridgeCreated() {
        boolean main = onMainThread();
        synchronized (COUNTERS) {
            bridgeCreates++;
            if (main) mainThreadNative++;
        }
    }

    public static long bridgeCreates() {
        synchronized (COUNTERS) {
            return bridgeCreates;
        }
    }

    public static long mainThreadNative() {
        synchronized (COUNTERS) {
            return mainThreadNative;
        }
    }

    public static void recordKeys(boolean on) {
        recordKeys = on;
    }

    public static void collectKeys(Collection<String> sink) {
        if (sink == null) SINK.remove();
        else SINK.set(sink);
    }

    public static void keyRead(String key) {
        if (key == null) return;
        Collection<String> sink = SINK.get();
        if (sink != null) sink.add(key);
        if (!recordKeys) return;
        String step = currentStep();
        synchronized (KEYS) {
            List<String> keys = KEYS.get(step);
            if (keys == null) {
                keys = new ArrayList<>();
                KEYS.put(step, keys);
            }
            keys.add(key);
        }
    }

    public static Map<String, List<String>> keysRead() {
        Map<String, List<String>> out = new LinkedHashMap<>();
        synchronized (KEYS) {
            for (Map.Entry<String, List<String>> e : KEYS.entrySet()) out.put(e.getKey(), new ArrayList<>(e.getValue()));
        }
        return out;
    }

    public static boolean onMainThread() {
        Thread main = mainThread;
        if (main == null && !mainThreadResolved) {
            try {
                main = Looper.getMainLooper().getThread();
            } catch (Throwable ignored) {
                main = null;
            }
            mainThread = main;
            mainThreadResolved = true;
        }
        return main != null && main == Thread.currentThread();
    }

    public static void setMainThreadForTest(Thread thread) {
        mainThread = thread;
        mainThreadResolved = true;
    }

    private static void add(long hits, long misses, long scanNanos, long natives, boolean scan) {
        boolean main = onMainThread();
        String key = currentStep() + '\u0000' + segment() + '\u0000' + (main ? "main" : "other");
        synchronized (COUNTERS) {
            Counter c = COUNTERS.get(key);
            if (c == null) {
                c = new Counter();
                COUNTERS.put(key, c);
            }
            apply(c, hits, misses, scanNanos, natives, scan && main);
            apply(TOTAL, hits, misses, scanNanos, natives, scan && main);
            if (scan && main) mainThreadNative++;
        }
    }

    private static void apply(Counter c, long hits, long misses, long scanNanos, long natives, boolean mainScan) {
        c.queryHits += hits;
        c.queryMisses += misses;
        c.scanNanos += scanNanos;
        c.nativeCalls += natives;
        if (mainScan) c.mainThreadScans++;
    }

    public static Totals totals() {
        synchronized (COUNTERS) {
            return TOTAL.totals();
        }
    }

    public static List<Row> rows() {
        List<Row> out = new ArrayList<>();
        synchronized (COUNTERS) {
            for (Map.Entry<String, Counter> e : COUNTERS.entrySet()) {
                String[] k = e.getKey().split("\u0000", -1);
                out.add(new Row(k[0], k[1], "main".equals(k[2]), e.getValue().totals()));
            }
        }
        return out;
    }

    public static Map<String, Totals> bySegmentAndThread() {
        Map<String, Totals> out = new LinkedHashMap<>();
        for (Row r : rows()) {
            String key = r.segment + (r.mainThread ? " main" : " other");
            Totals prev = out.get(key);
            out.put(key, prev == null ? r.totals : prev.plus(r.totals));
        }
        return out;
    }

    public static void reset() {
        synchronized (COUNTERS) {
            COUNTERS.clear();
            TOTAL.queryHits = 0;
            TOTAL.queryMisses = 0;
            TOTAL.scanNanos = 0;
            TOTAL.nativeCalls = 0;
            TOTAL.mainThreadScans = 0;
            bridgeCreates = 0;
            mainThreadNative = 0;
        }
        synchronized (KEYS) {
            KEYS.clear();
        }
    }
}
