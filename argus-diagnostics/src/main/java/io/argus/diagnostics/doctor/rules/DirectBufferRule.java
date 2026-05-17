package io.argus.diagnostics.doctor.rules;

import io.argus.diagnostics.doctor.*;

import java.util.List;
import java.util.Map;

/**
 * Detects NIO direct buffer pressure — common in Netty/NIO applications.
 * Direct buffers are allocated outside the heap and not tracked by GC.
 *
 * <p>Prefers JVM-reported buffer pools when present. On JDK 16+ the jcmd
 * remote path cannot read buffer pools, so we fall back to NMT category
 * totals (DirectByteBuffer allocations land under {@code Other} on JDK 17
 * and {@code Other} or {@code Internal} on JDK 21+). The NMT fallback is
 * coarser — it includes any unattributed native memory, not strictly
 * NIO direct buffers — so the finding text says "via NMT" for honesty.
 */
public final class DirectBufferRule implements HealthRule {

    private static final long ABS_THRESHOLD_MB = 200;
    private static final double HEAP_RATIO_PCT = 50.0;

    @Override
    public List<Finding> evaluate(JvmSnapshot s) {
        for (var buf : s.bufferPools()) {
            if (!buf.name().toLowerCase().contains("direct")) continue;
            if (buf.capacity() <= 0) continue;

            long mb = buf.used() / (1024 * 1024);
            Finding f = build(mb, buf.count(), s.heapMax(), false);
            if (f != null) return List.of(f);
            return List.of();
        }

        // Fallback: bufferPools empty (JDK 16+ jcmd path). Read NMT instead.
        long maxCategoryMb = 0;
        for (Map.Entry<String, Long> e : s.nmtCommittedKbByCategory().entrySet()) {
            String name = e.getKey().toLowerCase();
            if (name.contains("other") || name.contains("internal")) {
                maxCategoryMb = Math.max(maxCategoryMb, e.getValue() / 1024);
            }
        }
        if (maxCategoryMb == 0) return List.of();
        Finding f = build(maxCategoryMb, -1, s.heapMax(), true);
        return f == null ? List.of() : List.of(f);
    }

    private static Finding build(long mb, long bufferCount, long heapMax, boolean viaNmt) {
        long heapMB = heapMax > 0 ? heapMax / (1024 * 1024) : 1;
        double ratioToHeap = (double) mb / heapMB * 100;
        if (mb < ABS_THRESHOLD_MB && ratioToHeap < HEAP_RATIO_PCT) return null;

        Severity sev = (mb >= 800 || ratioToHeap >= 100) ? Severity.CRITICAL : Severity.WARNING;
        String title = bufferCount >= 0
                ? String.format("Direct buffer pool: %dMB used (%d buffers)", mb, bufferCount)
                : String.format("Native off-heap memory: %dMB committed (via NMT)", mb);
        String detail = viaNmt
                ? "JDK 16+ jcmd cannot read BufferPoolMXBean, so this is derived from NMT "
                  + "categories (Other / Internal) where DirectByteBuffer allocations land. "
                  + "It is an upper bound — non-NIO native allocations are included."
                : "Large direct buffer usage indicates potential buffer leaks in NIO/Netty. "
                  + "Direct buffers are allocated outside the Java heap and not collected by GC.";
        return Finding.builder(sev, "Memory", title)
                .detail(detail)
                .recommend("Run: argus nmt <pid> --save / --diff to track growth over time")
                .recommend("Check for Netty ByteBuf leaks — enable -Dio.netty.leakDetection.level=paranoid")
                .flag("-XX:MaxDirectMemorySize=" + Math.max(mb * 2, 1024) + "m")
                .build();
    }
}
