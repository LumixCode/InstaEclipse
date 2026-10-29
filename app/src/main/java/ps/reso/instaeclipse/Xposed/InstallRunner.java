package ps.reso.instaeclipse.Xposed;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.LongSupplier;

import ps.reso.instaeclipse.utils.core.DexKitCache;
import ps.reso.instaeclipse.utils.core.DexKitSeed;
import ps.reso.instaeclipse.utils.core.DexKitStats;
import ps.reso.instaeclipse.utils.core.ModuleBridge;
import ps.reso.instaeclipse.utils.feature.FeatureCatalog;
import ps.reso.instaeclipse.utils.feature.RestartPending;
import ps.reso.instaeclipse.utils.log.ModuleLog;
import ps.reso.instaeclipse.utils.log.StartupTrace;

public final class InstallRunner {

    public interface Reporter {
        void error(String message, Throwable t);

        default void withoutBridge(int steps) {
        }
    }

    public interface Ledger {
        boolean wantsKeys(InstallStep step);

        void ran(InstallStep step, String segment, long searches, Collection<String> keys);
    }

    public static final Reporter LOG = new Reporter() {
        @Override
        public void error(String message, Throwable t) {
            ModuleLog.error(message, t);
        }

        @Override
        public void withoutBridge(int steps) {
            ModuleLog.error(ModuleBridge.unavailableLine(steps));
            String detail = ModuleBridge.unavailableDetail();
            if (detail != null) ModuleLog.detail(detail);
        }
    };

    private static final Set<String> RAN = ConcurrentHashMap.newKeySet();

    private final Set<String> skipped;
    private final LongSupplier clock;
    private final String segment;
    private final List<Object[]> marks = Collections.synchronizedList(new ArrayList<>());
    private int withoutBridge;
    private volatile Ledger ledger;

    public InstallRunner(Set<String> skipped, LongSupplier clock, String segment) {
        this.skipped = skipped == null ? Collections.emptySet() : skipped;
        this.clock = clock;
        this.segment = segment;
    }

    public void setLedger(Ledger ledger) {
        this.ledger = ledger;
    }

    public void runAll(InstallPlan plan, InstallPlan.InstallCtx ctx, Reporter reporter) {
        run(plan, plan.steps(), segment, ctx, reporter);
        report(reporter);
    }

    public void run(InstallPlan plan, List<InstallStep> steps, String segment, InstallPlan.InstallCtx ctx, Reporter reporter) {
        run(plan, steps, segment, ctx, reporter, -1, null);
    }

    public void run(InstallPlan plan, List<InstallStep> steps, String segment, InstallPlan.InstallCtx ctx, Reporter reporter,
                    int pauseAt, Runnable pause) {
        run(plan, steps, segment, ctx, reporter, pauseAt, pause, null);
    }

    public void run(InstallPlan plan, List<InstallStep> steps, String segment, InstallPlan.InstallCtx ctx, Reporter reporter,
                    int pauseAt, Runnable pause, Runnable last) {
        String previous = DexKitStats.segment();
        DexKitStats.setSegment(segment);
        DexKitCache.beginBatch();
        try {
            if (DexKitStats.BACKGROUND.equals(segment)) seed(ctx);
            for (int i = 0; i <= steps.size(); i++) {
                if (i == pauseAt && pause != null) pause.run();
                if (i < steps.size()) runStep(plan, steps.get(i), segment, ctx, reporter);
            }
            if (last != null) {
                try {
                    last.run();
                } catch (Throwable t) {
                    ModuleLog.detail("(IE|Startup) Could not note whether the next start needs the code search: " + t);
                }
            }
        } finally {
            DexKitCache.flushBatch();
            DexKitStats.setSegment(DexKitStats.NONE.equals(previous) ? null : previous);
        }
    }

    private static void seed(InstallPlan.InstallCtx ctx) {
        if (ctx == null || ctx.lpparam == null || ctx.lpparam.appInfo == null) return;
        try {
            DexKitSeed.importIfEmpty(Module.moduleSourceDir, ctx.lpparam.appInfo.sourceDir);
        } catch (Throwable t) {
            ModuleLog.detail("(IE|Startup) Could not read the bundled lookup table, so Instagram's code is searched: " + t);
        }
    }

    public void report(Reporter reporter) {
        int missed = withoutBridge;
        withoutBridge = 0;
        if (missed > 0) reporter.withoutBridge(missed);
    }

    private void runStep(InstallPlan plan, InstallStep step, String segment, InstallPlan.InstallCtx ctx, Reporter reporter) {
        StartupTrace.Span span = null;
        Ledger book = ledger;
        List<String> keys = null;
        long searches = -1;
        try {
            for (String key : step.restartKeys()) {
                RestartPending.capture(key, plan.flag(key));
                for (String parent : FeatureCatalog.restartParents(key)) RestartPending.capture(parent, plan.flag(parent));
            }
            if (step.gateKey != null && !plan.gateOpen(step.gateKey)) return;
            mark(step.name);
            if (step.skippedBy(skipped)) return;
            if (step.dexKit && ModuleBridge.failed()) {
                withoutBridge++;
                return;
            }
            span = StartupTrace.step(step.name);
            DexKitStats.enterStep(step.name);
            if (book != null) {
                if (book.wantsKeys(step)) {
                    keys = new ArrayList<>();
                    DexKitStats.collectKeys(keys);
                }
                searches = DexKitStats.totals().queryMisses;
            }
            plan.run(step, ctx);
            RAN.add(step.name);
        } catch (Throwable t) {
            if (step.dexKit && ModuleBridge.failed()) withoutBridge++;
            else reporter.error(step.failTag + step.failText, t);
        } finally {
            if (span != null) {
                if (keys != null) DexKitStats.collectKeys(null);
                DexKitStats.exitStep();
                StartupTrace.end(span);
            }
            if (book != null && searches >= 0) {
                try {
                    book.ran(step, segment, DexKitStats.totals().queryMisses - searches, keys);
                } catch (Throwable t) {
                    ModuleLog.detail("(IE|Startup) Could not note that " + step.name + " is set up: " + t);
                }
            }
        }
    }

    public static boolean ran(String stepName) {
        return RAN.contains(stepName);
    }

    public void mark(String name) {
        marks.add(new Object[]{name, clock.getAsLong()});
    }

    public List<Map<String, Object>> timings() {
        Map<String, Long> nanos = new LinkedHashMap<>();
        synchronized (marks) {
            for (int i = 0; i + 1 < marks.size(); i++) {
                Object name = marks.get(i)[0];
                if (name == null) continue;
                long d = (Long) marks.get(i + 1)[1] - (Long) marks.get(i)[1];
                nanos.merge((String) name, d, Long::sum);
            }
        }
        List<Map<String, Object>> out = new ArrayList<>();
        for (Map.Entry<String, Long> e : nanos.entrySet()) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("name", e.getKey());
            m.put("ms", e.getValue() / 1_000_000L);
            m.put("us", e.getValue() / 1_000L);
            out.add(m);
        }
        return out;
    }
}
