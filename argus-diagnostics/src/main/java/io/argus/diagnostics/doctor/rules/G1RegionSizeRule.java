package io.argus.diagnostics.doctor.rules;

import io.argus.diagnostics.doctor.*;

import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Detects mis-sized G1 regions for the current heap.
 *
 * <p>G1's default region size scales as a power of two between 1MB and 32MB based
 * on max heap, capped at 2048 regions. For very large heaps (&gt; 64GB) the JVM may
 * cap at 32MB regions and run with &gt; 2048 regions — at that point an explicit
 * override is worth considering. For small heaps (&lt; 2GB) with no explicit override,
 * tiny regions (1–2MB) make the humongous threshold very low (≥ regionSize/2) and
 * mark many normal allocations as humongous.
 *
 * <p>Fires WARNING when:
 * <ul>
 *   <li>heap &gt; 32 GB AND no explicit {@code -XX:G1HeapRegionSize}, OR</li>
 *   <li>explicit {@code -XX:G1HeapRegionSize} smaller than the JVM default for
 *       this heap (humongous-allocation risk).</li>
 * </ul>
 * Skipped entirely for non-G1 collectors.
 */
public final class G1RegionSizeRule implements HealthRule {

    private static final Pattern REGION_SIZE_FLAG = Pattern.compile(
            "-XX:G1HeapRegionSize=(\\d+)([KMG]?)", Pattern.CASE_INSENSITIVE);

    static final long LARGE_HEAP_BYTES = 32L * 1024 * 1024 * 1024; // 32 GB

    @Override
    public List<Finding> evaluate(JvmSnapshot s) {
        if (!s.gcAlgorithm().contains("G1")) return List.of();
        if (s.heapMax() <= 0) return List.of();

        long explicitRegionBytes = explicitRegionBytes(s.vmFlags());

        // Large heap, no explicit region size — JVM caps at 32MB regions which may
        // mean very high region count; suggest explicit tuning.
        if (s.heapMax() > LARGE_HEAP_BYTES && explicitRegionBytes <= 0) {
            var b = Finding.builder(Severity.WARNING, "GC",
                    "Large heap on G1 without explicit -XX:G1HeapRegionSize");
            b.detail(String.format(
                    "heap max %.1f GB with default region sizing; G1 will cap regions at 32MB "
                            + "and the resulting region count may exceed 2048",
                    s.heapMax() / 1_073_741_824.0));
            b.recommend("Set -XX:G1HeapRegionSize=32m (or 64m for >128GB heaps) "
                    + "to bound region count and predictable evacuation cost.");
            b.flag("-XX:G1HeapRegionSize=32m");
            return List.of(b.build());
        }

        // Tiny explicit region on a non-tiny heap → humongous spike risk.
        if (explicitRegionBytes > 0 && explicitRegionBytes <= 2L * 1024 * 1024
                && s.heapMax() >= 4L * 1024 * 1024 * 1024) {
            var b = Finding.builder(Severity.WARNING, "GC",
                    "Tiny G1 region size on a ≥ 4 GB heap (humongous spike risk)");
            b.detail(String.format(
                    "-XX:G1HeapRegionSize=%dM with -Xmx %.1f GB — "
                            + "humongous threshold is %dKB, common allocations may be marked humongous",
                    explicitRegionBytes / (1024 * 1024),
                    s.heapMax() / 1_073_741_824.0,
                    (explicitRegionBytes / 1024) / 2));
            b.recommend("Raise -XX:G1HeapRegionSize to 8m or 16m and re-profile humongous incidence.");
            b.flag("-XX:G1HeapRegionSize=8m");
            return List.of(b.build());
        }

        return List.of();
    }

    static long explicitRegionBytes(List<String> vmFlags) {
        for (String flag : vmFlags) {
            Matcher m = REGION_SIZE_FLAG.matcher(flag);
            if (m.find()) {
                long v;
                try { v = Long.parseLong(m.group(1)); }
                catch (NumberFormatException e) { continue; }
                String unit = m.group(2);
                if (unit == null || unit.isEmpty()) return v; // bytes
                switch (unit.toUpperCase()) {
                    case "K": return v * 1024L;
                    case "M": return v * 1024L * 1024L;
                    case "G": return v * 1024L * 1024L * 1024L;
                    default:  return v;
                }
            }
        }
        return 0;
    }
}
