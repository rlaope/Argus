package io.argus.diagnostics.doctor.rules;

import io.argus.diagnostics.doctor.*;

import java.util.ArrayList;
import java.util.List;

/**
 * Detects virtual-thread carrier-pool saturation.
 *
 * <p>Virtual threads run on a small pool of carrier (platform) threads — a
 * {@code ForkJoinPool} sized to {@code Runtime.availableProcessors()} by default.
 * When every carrier is busy and virtual threads keep arriving, the scheduler
 * cannot mount new work and the JVM emits {@code jdk.VirtualThreadSubmitFailed}.
 * Sustained submit failures combined with the active carriers sitting at the
 * pool's parallelism (and a backlog of unmounted virtual threads) indicate the
 * carrier pool is the bottleneck — typically because carriers are blocked in
 * pinning regions (native frames / foreign calls) rather than yielding.
 *
 * <p>This rule fires only when VT telemetry was collected; with no telemetry the
 * carrier fields default to 0 and the rule stays silent.
 */
public final class CarrierSaturationRule implements HealthRule {

    /** Submit failures below this are treated as transient noise. */
    static final long DEFAULT_SUBMIT_FAILURE_FLOOR = 1;

    /** Fraction of parallelism that counts as "saturated" (active carriers ≈ parallelism). */
    static final double SATURATION_RATIO = 0.9;

    private final long submitFailureFloor;
    private final double saturationRatio;

    public CarrierSaturationRule() {
        this(DEFAULT_SUBMIT_FAILURE_FLOOR, SATURATION_RATIO);
    }

    CarrierSaturationRule(long submitFailureFloor, double saturationRatio) {
        this.submitFailureFloor = submitFailureFloor;
        this.saturationRatio = saturationRatio;
    }

    @Override
    public List<Finding> evaluate(JvmSnapshot s) {
        List<Finding> findings = new ArrayList<>();

        int parallelism = s.carrierParallelism();
        if (parallelism <= 0) {
            return findings; // no VT telemetry — stay silent
        }

        long submitFailures = s.virtualThreadSubmitFailures();
        int activeCarriers = s.activeCarrierThreads();
        long backlog = s.queuedVirtualThreads();

        boolean carriersSaturated = activeCarriers >= parallelism * saturationRatio;
        boolean hasBacklog = backlog > 0;
        boolean failing = submitFailures >= submitFailureFloor;

        if (!(failing && carriersSaturated && hasBacklog)) {
            return findings;
        }

        Severity sev = submitFailures >= parallelism ? Severity.CRITICAL : Severity.WARNING;
        findings.add(Finding.builder(sev, "VirtualThreads",
                        String.format("Carrier pool saturated: %d/%d carriers active, %d queued VT(s), "
                                        + "%d submit failure(s)",
                                activeCarriers, parallelism, backlog, submitFailures))
                .detail("All carrier threads are busy while virtual threads keep arriving, so the "
                        + "scheduler cannot mount new work. Post-JEP-491 this is usually caused by "
                        + "carriers stuck in pinning regions (native frames / foreign (JNI) calls), "
                        + "not by synchronized blocks.")
                .recommend("Run: argus threaddump <pid> --format=json to inspect carrier stacks for native/foreign frames")
                .recommend("Move blocking native or foreign (FFM/JNI) calls off the carrier, or raise "
                        + "jdk.virtualThreadScheduler.parallelism if the work is genuinely CPU-bound")
                .build());

        return findings;
    }
}
