package io.argus.cli.harness.rules;

import io.argus.diagnostics.doctor.Finding;
import io.argus.diagnostics.doctor.Severity;
import io.argus.cli.harness.HarnessSession;
import io.argus.cli.harness.TimedSnapshot;
import io.argus.cli.harness.TrendRule;

import java.util.List;

/**
 * Thread-count growth detector.
 *
 * <p>Looks for sustained thread growth across the window. If the latest
 * sample's thread count exceeds the earliest by more than 50 AND the
 * regression slope is positive (growth, not noise around a steady state),
 * raise WARNING — that pattern is almost always a thread-pool / executor
 * leak.
 *
 * <p>Skipped when the window is too short (&lt; 5 samples).
 */
public final class ThreadGrowthRule implements TrendRule {

    private static final int MIN_SAMPLES = 5;
    private static final int GROWTH_THRESHOLD = 50;

    @Override
    public List<Finding> evaluate(HarnessSession window) {
        List<TimedSnapshot> samples = window.snapshots();
        if (samples.size() < MIN_SAMPLES) return List.of();

        int first = samples.get(0).snapshot().threadCount();
        int last = samples.get(samples.size() - 1).snapshot().threadCount();
        int delta = last - first;
        if (delta < GROWTH_THRESHOLD) return List.of();

        // Confirm growth is not just a one-off spike — slope of threadCount over time.
        double[] x = new double[samples.size()];
        double[] y = new double[samples.size()];
        long t0 = samples.get(0).timestampMs();
        for (int i = 0; i < samples.size(); i++) {
            x[i] = (samples.get(i).timestampMs() - t0) / 60_000.0;
            y[i] = samples.get(i).snapshot().threadCount();
        }
        double slope = HeapGrowthLeakRule.slope(x, y);
        if (slope <= 0) return List.of();

        double minutes = x[x.length - 1];
        return List.of(Finding.builder(Severity.WARNING, "Threads",
                        "Thread count is climbing")
                .detail(String.format(
                        "Threads grew from %d to %d (+%d, %.2f/min) over %.1f min — likely an unbounded pool or executor",
                        first, last, delta, slope, minutes))
                .recommend("Capture a thread dump: argus threaddump <pid>")
                .recommend("Check executor / connection pools: argus pool <pid>")
                .recommend("If WAITING-state threads dominate, inspect: argus threads <pid> --top=20")
                .build());
    }
}
