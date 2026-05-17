package io.argus.spring.diagnostics;

import io.argus.diagnostics.gclog.GcLogAnalysis;
import io.argus.diagnostics.gcscore.GcScoreCalculator;
import io.argus.diagnostics.gcscore.GcScoreResult;

/**
 * Compute a multi-axis GC health score from a {@link GcLogAnalysis}.
 *
 * <p>Thin Spring-injectable wrapper around {@link GcScoreCalculator}.
 */
public class GcScoreService {

    /**
     * Score a GC analysis with auto-detected algorithm.
     */
    public GcScoreResult score(GcLogAnalysis analysis) {
        return GcScoreCalculator.compute(analysis);
    }

    /**
     * Score a GC analysis assuming the given GC algorithm
     * (e.g. {@code "G1"}, {@code "ZGC"}, {@code "Parallel"}).
     * Use when the algorithm is known a priori — bypasses inference and
     * may apply algorithm-specific weight branches (notably ZGC).
     */
    public GcScoreResult score(GcLogAnalysis analysis, String gcAlgorithm) {
        return GcScoreCalculator.compute(analysis, gcAlgorithm);
    }

    /** Infer the GC algorithm from analysis signals (collector cause / pattern). */
    public String inferAlgorithm(GcLogAnalysis analysis) {
        return GcScoreCalculator.inferAlgorithm(analysis);
    }
}
