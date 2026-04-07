package io.argus.cli.doctor.rules;

import io.argus.cli.doctor.*;

import java.util.List;

/**
 * Detects NIO direct buffer pressure — common in Netty/NIO applications.
 * Direct buffers are allocated outside the heap and not tracked by GC.
 */
public final class DirectBufferRule implements HealthRule {

    @Override
    public List<Finding> evaluate(JvmSnapshot s) {
        for (var buf : s.bufferPools()) {
            if (!buf.name().toLowerCase().contains("direct")) continue;
            if (buf.capacity() <= 0) continue;

            // Check absolute size (> 500MB is concerning)
            long mb = buf.used() / (1024 * 1024);
            if (mb < 200) return List.of();

            Severity sev = mb >= 800 ? Severity.CRITICAL : Severity.WARNING;
            return List.of(Finding.builder(sev, "Memory",
                            String.format("Direct buffer pool: %dMB used (%d buffers)",
                                    mb, buf.count()))
                    .detail("Large direct buffer usage indicates potential buffer leaks in NIO/Netty. "
                            + "Direct buffers are allocated outside the Java heap and not collected by GC.")
                    .recommend("Run: argus buffers <pid> for detailed buffer pool breakdown")
                    .recommend("Check for Netty ByteBuf leaks — enable -Dio.netty.leakDetection.level=paranoid")
                    .flag("-XX:MaxDirectMemorySize=" + Math.max(mb * 2, 1024) + "m")
                    .build());
        }
        return List.of();
    }
}
