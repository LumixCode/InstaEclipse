package ps.reso.instaeclipse.utils.log;

import android.os.Debug;
import android.os.Handler;
import android.os.Looper;
import android.os.Process;
import android.os.Trace;
import android.view.Choreographer;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

import ps.reso.instaeclipse.utils.core.DexKitStats;
import ps.reso.instaeclipse.utils.core.LegacyPaths;
import ps.reso.instaeclipse.utils.core.StartupIdle;
import ps.reso.instaeclipse.xposedcompat.XposedBridge;

public final class StartupTrace {

    public static final String DEXKIT = "dexkit";
    public static final String ATTACH_BEFORE = "attach.before";
    public static final String ATTACH_AFTER = "attach.after";
    public static final String INSTALL = "install";

    public static final class Span {
        public final String name;
        public final boolean step;
        final long startNanos;
        final long cpuStart;
        final int hooksStart;
        final DexKitStats.Totals statsStart;
        final Thread thread = Thread.currentThread();
        volatile boolean traced = true;
        volatile long endNanos = -1;
        long cpuNanos;
        int hooks;
        DexKitStats.Totals stats = new DexKitStats.Totals(0, 0, 0, 0, 0);

        Span(String name, boolean step, long startNanos, long cpuStart, int hooksStart, DexKitStats.Totals statsStart) {
            this.name = name;
            this.step = step;
            this.startNanos = startNanos;
            this.cpuStart = cpuStart;
            this.hooksStart = hooksStart;
            this.statsStart = statsStart;
        }

        public boolean ended() {
            return endNanos >= 0;
        }

        public long nanos() {
            return ended() ? endNanos - startNanos : 0L;
        }

        public long ms() {
            return nanos() / 1_000_000L;
        }

        public long cpuNanos() {
            return cpuNanos;
        }

        public int hooks() {
            return hooks;
        }

        public DexKitStats.Totals stats() {
            return stats;
        }
    }

    private static final List<Span> SPANS = Collections.synchronizedList(new ArrayList<>());
    private static final AtomicBoolean FRAME_WATCHED = new AtomicBoolean();
    private static final AtomicBoolean FRAME_DRAWN = new AtomicBoolean();
    private static volatile Span lastInstall;
    private static volatile String lastSummary;
    private static volatile String lastDetail;

    private StartupTrace() {}

    public static Span begin(String name) {
        return start(name, false);
    }

    public static Span step(String name) {
        return start(name, true);
    }

    private static Span start(String name, boolean step) {
        beginSection("IE:" + name);
        Span span = new Span(name, step, System.nanoTime(), threadCpuNanos(), XposedBridge.hookCount(), DexKitStats.totals());
        SPANS.add(span);
        return span;
    }

    public static void end(Span span) {
        if (span == null || span.ended()) return;
        boolean sameThread = span.thread == Thread.currentThread();
        long cpu = threadCpuNanos();
        span.cpuNanos = sameThread && cpu >= 0 && span.cpuStart >= 0 ? cpu - span.cpuStart : -1L;
        span.hooks = XposedBridge.hookCount() - span.hooksStart;
        span.stats = DexKitStats.totals().minus(span.statsStart);
        span.endNanos = System.nanoTime();
        if (INSTALL.equals(span.name)) lastInstall = span;
        if (sameThread && span.traced) endSection();
    }

    public static void handOff(Span span) {
        if (span == null || span.ended() || !span.traced || span.thread != Thread.currentThread()) return;
        span.traced = false;
        endSection();
    }

    public static List<Span> spans() {
        synchronized (SPANS) {
            return new ArrayList<>(SPANS);
        }
    }

    public static Span lastInstall() {
        return lastInstall;
    }

    public static String lastSummary() {
        return lastSummary;
    }

    public static String lastDetail() {
        return lastDetail;
    }

    public static String newSearches(Span install) {
        return count(install == null ? 0L : install.stats.queryMisses, "new search", "new searches");
    }

    public static void logInstallSummary(Span install, boolean warm) {
        if (install == null || !install.ended()) return;
        List<Span> all = spans();
        String line = summary(warm, processStartNanos(), all, install) + LegacyPaths.summarySuffix();
        String detail = detail(all, install, DexKitStats.bySegmentAndThread());
        lastSummary = line;
        lastDetail = detail;
        ModuleLog.line(line);
        ModuleLog.detail(detail);
    }

    static String summary(boolean warm, long originNanos, List<Span> spans, Span install) {
        StringBuilder sb = new StringBuilder("(IE|Startup) Ready ");
        if (originNanos > 0) {
            sb.append(Math.max(0L, (install.endNanos - originNanos) / 1_000_000L)).append(" ms after Instagram started ");
        }
        sb.append('(').append(warm ? "warm" : "cold").append("): ");
        List<String> parts = new ArrayList<>();
        Span dexKit = latest(spans, DEXKIT);
        if (dexKit != null) parts.add("code search " + dexKit.ms() + " ms");
        Span before = latest(spans, ATTACH_BEFORE);
        Span after = latest(spans, ATTACH_AFTER);
        if (before != null || after != null) {
            long nanos = (before == null ? 0L : before.nanos()) + (after == null ? 0L : after.nanos());
            parts.add("settings " + nanos / 1_000_000L + " ms");
        }
        parts.add("features " + install.ms() + " ms (" + count(install.hooks, "hook", "hooks") + ", "
                + count(install.stats.queryHits, "saved lookup", "saved lookups") + ", "
                + count(install.stats.queryMisses, "new search", "new searches") + ")");
        sb.append(String.join(", ", parts)).append('.');
        List<Span> slowest = slowest(stepsOf(spans, install), 3);
        if (!slowest.isEmpty()) {
            List<String> names = new ArrayList<>();
            for (Span s : slowest) names.add(displayName(s.name) + " " + s.ms() + " ms");
            sb.append(" Slowest: ").append(String.join(", ", names)).append('.');
        }
        return sb.toString();
    }

    static String detail(List<Span> spans, Span install, Map<String, DexKitStats.Totals> bySegment) {
        StringBuilder sb = new StringBuilder("(IE|Startup) Steps (name ms hooks hits misses):");
        boolean first = true;
        for (Span s : stepsOf(spans, install)) {
            sb.append(first ? " " : ", ").append(s.name).append(' ').append(s.ms()).append(' ').append(s.hooks)
                    .append(' ').append(s.stats.queryHits).append(' ').append(s.stats.queryMisses);
            first = false;
        }
        sb.append('.');
        if (!bySegment.isEmpty()) {
            sb.append(" By segment and thread (hits misses native calls):");
            first = true;
            for (Map.Entry<String, DexKitStats.Totals> e : bySegment.entrySet()) {
                DexKitStats.Totals t = e.getValue();
                sb.append(first ? " " : ", ").append(e.getKey()).append(' ').append(t.queryHits).append(' ')
                        .append(t.queryMisses).append(' ').append(t.nativeCalls);
                first = false;
            }
            sb.append('.');
        }
        return sb.toString();
    }

    static List<Span> stepsOf(List<Span> spans, Span install) {
        List<Span> out = new ArrayList<>();
        for (Span s : spans) {
            if (s.step && s.ended() && s.startNanos >= install.startNanos && s.endNanos <= install.endNanos) out.add(s);
        }
        return out;
    }

    static List<Span> slowest(List<Span> steps, int n) {
        List<Span> sorted = new ArrayList<>(steps);
        sorted.sort((a, b) -> Long.compare(b.nanos(), a.nanos()));
        return sorted.size() > n ? new ArrayList<>(sorted.subList(0, n)) : sorted;
    }

    static String displayName(String step) {
        return step.endsWith("Hook") && step.length() > 4 ? step.substring(0, step.length() - 4) : step;
    }

    static String count(long n, String one, String many) {
        return n + " " + (n == 1 ? one : many);
    }

    private static Span latest(List<Span> spans, String name) {
        Span found = null;
        for (Span s : spans) if (name.equals(s.name) && s.ended()) found = s;
        return found;
    }

    public static void watchFirstFrame() {
        if (!FRAME_WATCHED.compareAndSet(false, true)) return;
        try {
            Choreographer.getInstance().postFrameCallback(frameTimeNanos ->
                    new Handler(Looper.getMainLooper()).post(StartupTrace::firstFrame));
        } catch (Throwable t) {
            ModuleLog.detail("(IE|Startup) Could not watch for the first screen: " + t);
        }
    }

    static void firstFrame() {
        if (!FRAME_DRAWN.compareAndSet(false, true)) return;
        long origin = processStartNanos();
        if (origin > 0) {
            ModuleLog.line("(IE|Startup) First screen drawn " + (System.nanoTime() - origin) / 1_000_000L
                    + " ms after Instagram started");
        }
        StartupIdle.firstFrame();
    }

    static long processStartNanos() {
        try {
            return Process.getStartUptimeMillis() * 1_000_000L;
        } catch (Throwable t) {
            return 0L;
        }
    }

    private static long threadCpuNanos() {
        try {
            return Debug.threadCpuTimeNanos();
        } catch (Throwable t) {
            return -1L;
        }
    }

    private static void beginSection(String name) {
        try {
            Trace.beginSection(name.length() > 127 ? name.substring(0, 127) : name);
        } catch (Throwable ignored) {
        }
    }

    private static void endSection() {
        try {
            Trace.endSection();
        } catch (Throwable ignored) {
        }
    }
}
