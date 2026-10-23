package ps.reso.instaeclipse.Xposed;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.LongSupplier;

import ps.reso.instaeclipse.utils.core.DexKitStats;
import ps.reso.instaeclipse.utils.log.ModuleLog;
import ps.reso.instaeclipse.utils.log.StartupTrace;

public final class InstallRunner {

    public interface Reporter {
        void error(String message, Throwable t);
    }

    public static final Reporter LOG = new Reporter() {
        @Override
        public void error(String message, Throwable t) {
            ModuleLog.error(message, t);
        }
    };

    private final Set<String> skipped;
    private final LongSupplier clock;
    private final String segment;
    private final List<Object[]> marks = Collections.synchronizedList(new ArrayList<>());

    public InstallRunner(Set<String> skipped, LongSupplier clock, String segment) {
        this.skipped = skipped == null ? Collections.emptySet() : skipped;
        this.clock = clock;
        this.segment = segment;
    }

    public void runAll(InstallPlan plan, InstallPlan.InstallCtx ctx, Reporter reporter) {
        String previous = DexKitStats.segment();
        DexKitStats.setSegment(segment);
        try {
            for (InstallStep step : plan.steps()) runStep(plan, step, ctx, reporter);
        } finally {
            DexKitStats.setSegment(DexKitStats.NONE.equals(previous) ? null : previous);
        }
    }

    private void runStep(InstallPlan plan, InstallStep step, InstallPlan.InstallCtx ctx, Reporter reporter) {
        StartupTrace.Span span = null;
        try {
            if (step.gateKey != null && !plan.gateOpen(step.gateKey)) return;
            mark(step.name);
            if (step.skippedBy(skipped)) return;
            span = StartupTrace.step(step.name);
            DexKitStats.enterStep(step.name);
            plan.run(step, ctx);
        } catch (Throwable t) {
            reporter.error(step.failTag + step.failText, t);
        } finally {
            if (span != null) {
                DexKitStats.exitStep();
                StartupTrace.end(span);
            }
        }
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
