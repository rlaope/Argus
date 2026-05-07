package io.argus.cli.doctor;

import io.argus.cli.model.MethodSample;
import io.argus.cli.model.ProfileSnapshot;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Profile-based diagnostic rules that analyse a {@link ProfileSnapshot} and produce
 * {@link Finding}s about hot-method patterns (wait stalls, JIT overhead, GC barriers,
 * allocation hot-leaves, dominant methods).
 *
 * <p>Each rule is a static nested class so they can be instantiated independently
 * for testing without any external state.
 */
public final class ProfileRules {

    private ProfileRules() {}

    // -------------------------------------------------------------------------
    // Rule 1 – HotWaitRule
    // -------------------------------------------------------------------------

    /**
     * Flags if blocking/wait methods (Object.wait, Thread.sleep, park variants)
     * collectively exceed 50% of CPU samples.
     */
    public static final class HotWaitRule {

        private static final double THRESHOLD_PCT = 50.0;

        private static final List<String> WAIT_PATTERNS = List.of(
                "Object.wait", "Thread.sleep", "Park.park", "Unsafe.park",
                "LockSupport.park", "sun.misc.Unsafe.park"
        );

        public List<Finding> evaluate(ProfileSnapshot snap) {
            if (snap.totalSamples() == 0) return List.of();

            double totalWaitPct = 0.0;
            List<String> matched = new ArrayList<>();

            for (MethodSample m : snap.methods()) {
                for (String pat : WAIT_PATTERNS) {
                    if (m.method().contains(pat)) {
                        totalWaitPct += m.percentage();
                        matched.add(String.format("%s (%.1f%%)", m.method(), m.percentage()));
                        break;
                    }
                }
            }

            if (totalWaitPct <= THRESHOLD_PCT) return List.of();

            String detail = String.format(
                    "%.1f%% of CPU samples are in blocking/wait methods: %s. " +
                    "If this is a worker pool that should be busy, check for a synchronization bottleneck.",
                    totalWaitPct, String.join(", ", matched));

            return List.of(Finding.builder(Severity.WARNING, "Profile",
                            String.format("High wait time: %.1f%% of samples in blocking calls", totalWaitPct))
                    .detail(detail)
                    .recommend("Inspect thread dumps: argus threads <pid>")
                    .recommend("Check for lock contention or under-provisioned thread pools")
                    .build());
        }
    }

    // -------------------------------------------------------------------------
    // Rule 2 – HotJitBarrierRule
    // -------------------------------------------------------------------------

    /**
     * Flags if JIT compilation overhead (c2_runtime, OptoRuntime, compile_method, etc.)
     * exceeds 10% of samples.
     */
    public static final class HotJitBarrierRule {

        private static final double THRESHOLD_PCT = 10.0;

        private static final Pattern JIT_PATTERN = Pattern.compile(
                "c2_runtime|OptoRuntime|GraphBuilder|compile_method|CompilationPolicy",
                Pattern.CASE_INSENSITIVE);

        public List<Finding> evaluate(ProfileSnapshot snap) {
            if (snap.totalSamples() == 0) return List.of();

            double jitPct = 0.0;
            for (MethodSample m : snap.methods()) {
                if (JIT_PATTERN.matcher(m.method()).find()) {
                    jitPct += m.percentage();
                }
            }

            if (jitPct <= THRESHOLD_PCT) return List.of();

            return List.of(Finding.builder(Severity.WARNING, "Profile",
                            String.format("JIT compilation overhead: %.1f%% of samples in JIT internals", jitPct))
                    .detail("JIT compilation overhead is unusually high. Consider -XX:+TieredCompilation tuning " +
                            "or a warmup phase before load testing.")
                    .recommend("Add -XX:+TieredCompilation if not already set")
                    .recommend("Increase warmup iterations or use class data sharing (-XX:+UseAppCDS)")
                    .build());
        }
    }

    // -------------------------------------------------------------------------
    // Rule 3 – HotGcBarrierRule
    // -------------------------------------------------------------------------

    /**
     * Flags if GC barrier/runtime code exceeds 15% of samples.
     */
    public static final class HotGcBarrierRule {

        private static final double THRESHOLD_PCT = 15.0;

        private static final Pattern GC_PATTERN = Pattern.compile(
                "G1|Shenandoah|ZHeap|ZGC|GenCollect|ParallelGC|OldGC",
                Pattern.CASE_INSENSITIVE);

        public List<Finding> evaluate(ProfileSnapshot snap) {
            if (snap.totalSamples() == 0) return List.of();

            double gcPct = 0.0;
            for (MethodSample m : snap.methods()) {
                if (GC_PATTERN.matcher(m.method()).find()) {
                    gcPct += m.percentage();
                }
            }

            if (gcPct <= THRESHOLD_PCT) return List.of();

            return List.of(Finding.builder(Severity.WARNING, "Profile",
                            String.format("GC barrier overhead: %.1f%% of samples in GC code paths", gcPct))
                    .detail("GC barriers/code paths dominate CPU. " +
                            "Suggest correlating with `argus gc <pid>`.")
                    .recommend("Run: argus gc <pid> to correlate with GC metrics")
                    .recommend("Consider tuning heap size or switching GC algorithm")
                    .build());
        }
    }

    // -------------------------------------------------------------------------
    // Rule 4 – HotAllocationLeafRule  (alloc snapshots only)
    // -------------------------------------------------------------------------

    /**
     * For alloc-type snapshots: surfaces the top-3 allocation hot leaves as INFO findings.
     */
    public static final class HotAllocationLeafRule {

        private static final int TOP_N = 3;

        public List<Finding> evaluate(ProfileSnapshot snap) {
            if (snap.totalSamples() == 0) return List.of();
            if (!"alloc".equalsIgnoreCase(snap.type())) return List.of();

            List<MethodSample> top = snap.methods().stream()
                    .sorted((a, b) -> Double.compare(b.percentage(), a.percentage()))
                    .limit(TOP_N)
                    .toList();

            List<Finding> findings = new ArrayList<>();
            for (MethodSample m : top) {
                findings.add(Finding.builder(Severity.INFO, "Profile",
                                String.format("Allocation hot-leaf: %s (%.1f%%)", m.method(), m.percentage()))
                        .detail(String.format("Method %s accounts for %.1f%% of allocation samples. " +
                                "If unexpected, consider `--type=alloc --live` to see surviving allocations.",
                                m.method(), m.percentage()))
                        .recommend("Run: argus profile <pid> --type=alloc --live for live object analysis")
                        .build());
            }
            return findings;
        }
    }

    // -------------------------------------------------------------------------
    // Rule 5 – DominantMethodRule
    // -------------------------------------------------------------------------

    /**
     * Flags if a single method exceeds 60% of samples — likely a tight loop or runaway computation.
     */
    public static final class DominantMethodRule {

        private static final double THRESHOLD_PCT = 60.0;

        public List<Finding> evaluate(ProfileSnapshot snap) {
            if (snap.totalSamples() == 0) return List.of();

            for (MethodSample m : snap.methods()) {
                if (m.percentage() >= THRESHOLD_PCT) {
                    return List.of(Finding.builder(Severity.CRITICAL, "Profile",
                                    String.format("Dominant method: %s at %.1f%% of samples", m.method(), m.percentage()))
                            .detail(String.format(
                                    "One method (%s) dominates the profile at %.1f%% of samples. " +
                                    "Likely a tight loop or runaway computation.",
                                    m.method(), m.percentage()))
                            .recommend("Inspect the method for infinite loops or missing backpressure")
                            .recommend("Run: argus flame <pid> to visualise the full call tree")
                            .build());
                }
            }
            return List.of();
        }
    }

    // -------------------------------------------------------------------------
    // Convenience: run all rules against a snapshot
    // -------------------------------------------------------------------------

    /**
     * Runs all 5 profile rules against {@code snap} and returns the merged findings,
     * sorted CRITICAL → WARNING → INFO.
     */
    public static List<Finding> diagnose(ProfileSnapshot snap) {
        List<Finding> all = new ArrayList<>();
        all.addAll(new HotWaitRule().evaluate(snap));
        all.addAll(new HotJitBarrierRule().evaluate(snap));
        all.addAll(new HotGcBarrierRule().evaluate(snap));
        all.addAll(new HotAllocationLeafRule().evaluate(snap));
        all.addAll(new DominantMethodRule().evaluate(snap));

        all.sort(java.util.Comparator
                .comparing(Finding::severity)
                .thenComparing(Finding::category));
        return List.copyOf(all);
    }
}
