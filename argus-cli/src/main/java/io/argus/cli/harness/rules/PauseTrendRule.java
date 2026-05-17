package io.argus.cli.harness.rules;

import io.argus.diagnostics.doctor.Finding;
import io.argus.diagnostics.doctor.Severity;
import io.argus.cli.harness.HarnessSession;
import io.argus.cli.harness.TimedSnapshot;
import io.argus.cli.harness.TrendRule;

import java.util.List;

/**
 * GC pause regression detector.
 *
 * <p>Compares the average {@code maxRecentPauseMs} of the first half of the
 * window against the second half. If the second half's average is at least
 * 2x the first half's AND above 50ms absolute, raise WARNING — pause times
 * are deteriorating in a way the single-snapshot {@code MaxPauseRule} only
 * sees instantaneously.
 *
 * <p>Skipped when the window is too short (&lt; 6 samples) or when the
 * remote-jcmd path didn't populate pause data (zeros throughout).
 */
public final class PauseTrendRule implements TrendRule {

    private static final int MIN_SAMPLES = 6;
    private static final double REGRESSION_RATIO = 2.0;
    private static final double MIN_RECENT_PAUSE_MS = 50.0;

    @Override
    public List<Finding> evaluate(HarnessSession window) {
        List<TimedSnapshot> samples = window.snapshots();
        if (samples.size() < MIN_SAMPLES) return List.of();

        int mid = samples.size() / 2;
        double oldAvg = avgPauseMs(samples.subList(0, mid));
        double newAvg = avgPauseMs(samples.subList(mid, samples.size()));

        if (newAvg < MIN_RECENT_PAUSE_MS) return List.of();
        if (oldAvg <= 0) return List.of();
        if (newAvg < oldAvg * REGRESSION_RATIO) return List.of();

        return List.of(Finding.builder(Severity.WARNING, "GC",
                        "Recent GC pause times are growing")
                .detail(String.format(
                        "Average max-pause rose from %.0f ms to %.0f ms (%.1fx) — pause-time SLO at risk",
                        oldAvg, newAvg, newAvg / Math.max(oldAvg, 1)))
                .recommend("Inspect the worst recent pause: argus gcwhy <pid>")
                .recommend("Score the GC log if you have one: argus gcscore <gc.log>")
                .recommend("If on G1, consider tuning -XX:MaxGCPauseMillis or moving to ZGC")
                .flag("-XX:MaxGCPauseMillis=200")
                .build());
    }

    private static double avgPauseMs(List<TimedSnapshot> samples) {
        if (samples.isEmpty()) return 0;
        double sum = 0;
        for (TimedSnapshot t : samples) sum += t.snapshot().maxRecentPauseMs();
        return sum / samples.size();
    }
}
