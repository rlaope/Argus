package io.argus.diagnostics.doctor.rules;

import io.argus.diagnostics.doctor.*;

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

        // Check Old/Tenured gen first — saturation there is the primary OOM indicator
        // and must surface even when total heap percentage looks acceptable.
        JvmSnapshot.PoolInfo oldPool = findOldGenPool(s);
        if (oldPool != null) {
            double oldPct = oldPool.usagePercent();
            if (oldPct >= 85) {
                boolean critical = oldPct >= 92;
                Severity sev = critical ? Severity.CRITICAL : Severity.WARNING;
                var b = Finding.builder(sev, "Memory",
                        String.format("Old generation %.0f%% (%s / %s)",
                                oldPct, formatBytes(oldPool.used()), formatBytes(oldPool.max())));
                b.detail(String.format("Old generation at %.0f%% — Full GC risk is imminent. "
                        + "Objects are being promoted faster than collected.", oldPct));
                b.recommend("Take a heap dump before OOM: argus heapdump <pid>");
                b.recommend("Check for memory leaks: argus diff <pid> 30");
                if (critical) {
                    b.recommend("Immediate action: increase heap or restart with larger -Xmx");
                    b.flag("-Xmx" + suggestHeapSize(s.heapMax()));
                } else {
                    b.recommend("Monitor trend with: argus watch <pid>");
                }
                return List.of(b.build());
            }
        }

        // Fall back to total heap percentage gate
        if (heapPct < 75) return List.of();

        boolean critical = heapPct >= 92;
        Severity sev = critical ? Severity.CRITICAL : Severity.WARNING;

        var b = Finding.builder(sev, "Memory",
                String.format("Heap usage %.0f%% (%s / %s)",
                        heapPct, formatBytes(s.heapUsed()), formatBytes(s.heapMax())));

        if (critical) {
            b.recommend("Immediate action: increase heap or restart with larger -Xmx");
            b.flag("-Xmx" + suggestHeapSize(s.heapMax()));
        } else {
            b.recommend("Monitor trend with: argus watch <pid>");
        }

        return List.of(b.build());
    }

    private static JvmSnapshot.PoolInfo findOldGenPool(JvmSnapshot s) {
        for (var pool : s.memoryPools().values()) {
            String name = pool.name().toLowerCase();
            if (name.contains("old") || name.contains("tenured") || name.contains("g1 old")) {
                return pool;
            }
        }
        return null;
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
