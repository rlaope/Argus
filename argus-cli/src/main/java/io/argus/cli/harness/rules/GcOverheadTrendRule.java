package io.argus.cli.harness.rules;

import io.argus.diagnostics.doctor.Finding;
import io.argus.diagnostics.doctor.Severity;
import io.argus.cli.harness.HarnessSession;
import io.argus.cli.harness.TimedSnapshot;
import io.argus.cli.harness.TrendRule;

import java.util.List;

/**
 * GC overhead regression detector.
 *
 * <p>Splits the retained window into two halves and compares the average
 * GC overhead percent. If the second half is at least 2x the first half
 * AND the second half is above 2% absolute, raise WARNING — overhead is
 * trending the wrong way even if no single snapshot crosses the doctor
 * threshold.
 *
 * <p>Skipped when the window is too short (&lt; 6 samples).
 */
public final class GcOverheadTrendRule implements TrendRule {

    private static final int MIN_SAMPLES = 6;
    private static final double REGRESSION_RATIO = 2.0;
    private static final double MIN_RECENT_OVERHEAD_PERCENT = 2.0;

    @Override
    public List<Finding> evaluate(HarnessSession window) {
        List<TimedSnapshot> samples = window.snapshots();
        if (samples.size() < MIN_SAMPLES) return List.of();

        int mid = samples.size() / 2;
        double oldAvg = avgOverhead(samples.subList(0, mid));
        double newAvg = avgOverhead(samples.subList(mid, samples.size()));

        if (newAvg < MIN_RECENT_OVERHEAD_PERCENT) return List.of();
        if (oldAvg <= 0) return List.of();
        if (newAvg < oldAvg * REGRESSION_RATIO) return List.of();

        return List.of(Finding.builder(Severity.WARNING, "GC",
                        "GC overhead is trending up")
                .detail(String.format(
                        "Average GC overhead rose from %.2f%% to %.2f%% (%.1fx) over the harness window",
                        oldAvg, newAvg, newAvg / Math.max(oldAvg, 0.01)))
                .recommend("Capture allocation profile: argus profile <pid> --event=alloc --duration=30")
                .recommend("Inspect recent GC log if available: argus gclog <gc.log>")
                .recommend("Run argus gcwhy <pid> for a per-event explanation of the worst recent pause")
                .build());
    }

    private static double avgOverhead(List<TimedSnapshot> samples) {
        if (samples.isEmpty()) return 0;
        double sum = 0;
        for (TimedSnapshot t : samples) sum += t.snapshot().gcOverheadPercent();
        return sum / samples.size();
    }
}
