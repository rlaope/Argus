package io.argus.cli.doctor.rules;

import io.argus.cli.doctor.*;

import java.util.List;

/**
 * Detects heap memory pressure — approaching OOM.
 *
 * <p>Old gen saturation is more dangerous than overall heap usage,
 * so we check both overall and per-pool when available.
 */
public final class HeapPressureRule implements HealthRule {

    @Override
    public List<Finding> evaluate(JvmSnapshot s) {
        double heapPct = s.heapUsagePercent();

        if (heapPct < 75) return List.of();

        boolean critical = heapPct >= 92;
        Severity sev = critical ? Severity.CRITICAL : Severity.WARNING;

        var b = Finding.builder(sev, "Memory",
                String.format("Heap usage %.0f%% (%s / %s)",
                        heapPct, formatBytes(s.heapUsed()), formatBytes(s.heapMax())));

        // Check for old gen specifically
        for (var pool : s.memoryPools().values()) {
            String name = pool.name().toLowerCase();
            if ((name.contains("old") || name.contains("tenured")) && pool.usagePercent() > 85) {
                b.detail(String.format("Old generation at %.0f%% — Full GC risk is imminent. "
                        + "Objects are being promoted faster than collected.", pool.usagePercent()));
                b.recommend("Take a heap dump before OOM: argus heapdump <pid>");
                b.recommend("Check for memory leaks: argus diff <pid> 30");
                break;
            }
        }

        if (critical) {
            b.recommend("Immediate action: increase heap or restart with larger -Xmx");
            b.flag("-Xmx" + suggestHeapSize(s.heapMax()));
        } else {
            b.recommend("Monitor trend with: argus watch <pid>");
        }

        return List.of(b.build());
    }

    private static String suggestHeapSize(long currentMax) {
        long mb = (long) (currentMax * 1.5) / (1024 * 1024);
        return mb >= 1024 ? (mb / 1024) + "g" : mb + "m";
    }

    private static String formatBytes(long bytes) {
        if (bytes < 1024 * 1024) return (bytes / 1024) + "KB";
        if (bytes < 1024L * 1024 * 1024) return (bytes / (1024 * 1024)) + "MB";
        return String.format("%.1fGB", bytes / (1024.0 * 1024 * 1024));
    }
}
