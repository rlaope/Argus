package io.argus.cli.doctor;

import java.util.List;

/**
 * A single health check rule that evaluates one aspect of JVM health.
 *
 * <p>Rules are stateless and produce zero or more {@link Finding}s based
 * on the provided {@link JvmSnapshot}. A rule that finds nothing healthy
 * returns an empty list (no news is good news).
 *
 * <p>Implementations should be focused — one rule per concern.
 */
@FunctionalInterface
public interface HealthRule {

    /**
     * Evaluate this rule against the JVM snapshot.
     *
     * @param snapshot current JVM metrics
     * @return list of findings (empty if healthy)
     */
    List<Finding> evaluate(JvmSnapshot snapshot);
}
