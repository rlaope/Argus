package io.argus.cli.harness.rules;

import io.argus.cli.doctor.Finding;
import io.argus.cli.doctor.Severity;
import io.argus.cli.harness.HarnessSession;
import io.argus.cli.harness.TimedSnapshot;
import io.argus.cli.harness.TrendRule;

import java.util.List;

/**
 * Linear-regression heap leak detector.
 *
 * <p>Fits a line through (timeMs, heapUsed) over the entire retained window.
 * If R² is high (samples are well-explained by a straight rise) AND the slope
 * is more than 1 MB/min, raise CRITICAL — this is the classic leak signature
 * that single-snapshot rules cannot see.
 *
 * <p>Skipped when the window has fewer than 5 samples.
 */
public final class HeapGrowthLeakRule implements TrendRule {

    private static final int MIN_SAMPLES = 5;
    private static final double MIN_R_SQUARED = 0.85;
    private static final double MIN_SLOPE_BYTES_PER_MIN = 1024.0 * 1024.0; // 1 MB/min

    @Override
    public List<Finding> evaluate(HarnessSession window) {
        List<TimedSnapshot> samples = window.snapshots();
        if (samples.size() < MIN_SAMPLES) return List.of();

        double[] x = new double[samples.size()];
        double[] y = new double[samples.size()];
        long t0 = samples.get(0).timestampMs();
        for (int i = 0; i < samples.size(); i++) {
            x[i] = (samples.get(i).timestampMs() - t0) / 60_000.0; // minutes since first
            y[i] = samples.get(i).snapshot().heapUsed();
        }

        double slope = slope(x, y);            // bytes per minute
        double r2 = rSquared(x, y, slope);

        if (r2 < MIN_R_SQUARED || slope < MIN_SLOPE_BYTES_PER_MIN) return List.of();

        double slopeMbPerMin = slope / (1024.0 * 1024.0);
        double windowMin = x[x.length - 1];

        return List.of(Finding.builder(Severity.CRITICAL, "Heap",
                        "Heap growth pattern looks like a leak")
                .detail(String.format(
                        "Heap used grew at %.2f MB/min (R^2=%.2f) over %.1f min — sustained linear growth strongly suggests a leak",
                        slopeMbPerMin, r2, windowMin))
                .recommend("Capture a heap dump now: argus heapdump <pid>")
                .recommend("Compare two histograms 5 min apart: argus diff <pid>")
                .recommend("If the leak is reproducible, run argus heapanalyze <heapdump>")
                .build());
    }

    static double slope(double[] x, double[] y) {
        double mx = mean(x), my = mean(y);
        double num = 0, den = 0;
        for (int i = 0; i < x.length; i++) {
            num += (x[i] - mx) * (y[i] - my);
            den += (x[i] - mx) * (x[i] - mx);
        }
        return den == 0 ? 0 : num / den;
    }

    static double rSquared(double[] x, double[] y, double slope) {
        double mx = mean(x), my = mean(y);
        double intercept = my - slope * mx;
        double ssRes = 0, ssTot = 0;
        for (int i = 0; i < x.length; i++) {
            double pred = intercept + slope * x[i];
            ssRes += (y[i] - pred) * (y[i] - pred);
            ssTot += (y[i] - my) * (y[i] - my);
        }
        return ssTot == 0 ? 0 : 1 - (ssRes / ssTot);
    }

    private static double mean(double[] a) {
        double s = 0;
        for (double v : a) s += v;
        return s / a.length;
    }
}
