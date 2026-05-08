package io.argus.cli.harness;

import io.argus.cli.doctor.Finding;

import java.util.List;

/**
 * A rule that evaluates a time-series window of JVM samples and produces
 * zero or more {@link Finding}s for trend-based anomalies (leaks, regressions,
 * spikes) that single-snapshot {@link io.argus.cli.doctor.HealthRule}s cannot
 * detect.
 *
 * <p>Rules are stateless and given an immutable {@link HarnessSession}. They
 * should return an empty list when the window is too short to evaluate
 * confidently — let other rules speak.
 */
@FunctionalInterface
public interface TrendRule {

    List<Finding> evaluate(HarnessSession window);
}
