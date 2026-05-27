package io.argus.diagnostics.doctor.rules;

import io.argus.diagnostics.doctor.*;

import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Detects sustained humongous-allocation pressure under G1.
 *
 * <p>G1 marks any allocation ≥ regionSize/2 as humongous, allocating it
 * directly into old gen. Frequent humongous allocations trigger concurrent
 * cycles regardless of IHOP and can cause fragmentation; a humongous region
 * count above ~10% of total regions indicates a tuning problem (region size
 * too small for the workload).
 *
 * <p>Fires WARNING when either:
 * <ul>
 *   <li>{@link io.argus.diagnostics.gclog.G1Stats#humongousAllocationCycles()} ≥ 3, OR</li>
 *   <li>peak humongous regions ≥ 10 with default region size (no explicit
 *       {@code -XX:G1HeapRegionSize}).</li>
 * </ul>
 * Skipped entirely for non-G1 collectors.
 */
public final class G1HumongousPressureRule implements HealthRule {

    static final int MIN_HUMONGOUS_CYCLES = 3;
    static final int PEAK_REGION_THRESHOLD = 10;

    private static final Pattern REGION_SIZE_FLAG = Pattern.compile(
            "-XX:G1HeapRegionSize=(\\d+)([KMG]?)", Pattern.CASE_INSENSITIVE);

    @Override
    public List<Finding> evaluate(JvmSnapshot s) {
        if (!s.gcAlgorithm().contains("G1")) return List.of();

        var stats = s.g1Stats();
        boolean cyclesHigh = stats.humongousAllocationCycles() >= MIN_HUMONGOUS_CYCLES;
        boolean peakHighOnDefault = stats.humongousRegionsPeak() >= PEAK_REGION_THRESHOLD
                && !hasExplicitRegionSize(s.vmFlags());

        if (!cyclesHigh && !peakHighOnDefault) return List.of();

        var b = Finding.builder(Severity.WARNING, "GC",
                "Sustained G1 humongous-allocation pressure (" + stats.humongousAllocationCycles()
                        + " cycles, peak " + stats.humongousRegionsPeak() + " regions)");

        b.detail("Allocations ≥ regionSize/2 are forced into old gen and trigger concurrent "
                + "cycles regardless of IHOP. Frequent humongous activity points to a "
                + "region size too small for the workload's largest allocations.");

        b.recommend("Profile humongous allocation sites with argus g1 <PID> "
                + "and raise -XX:G1HeapRegionSize to 2× the largest observed humongous "
                + "allocation (so it's no longer marked humongous).");

        b.flag("-XX:G1HeapRegionSize=16m");

        return List.of(b.build());
    }

    static boolean hasExplicitRegionSize(List<String> vmFlags) {
        for (String flag : vmFlags) {
            Matcher m = REGION_SIZE_FLAG.matcher(flag);
            if (m.find()) return true;
        }
        return false;
    }
}
