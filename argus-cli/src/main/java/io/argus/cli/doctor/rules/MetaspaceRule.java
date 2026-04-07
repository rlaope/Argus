package io.argus.cli.doctor.rules;

import io.argus.cli.doctor.*;

import java.util.List;

/**
 * Detects metaspace pressure — often caused by ClassLoader leaks in
 * application servers or frameworks that do class reloading.
 */
public final class MetaspaceRule implements HealthRule {

    @Override
    public List<Finding> evaluate(JvmSnapshot s) {
        for (var pool : s.memoryPools().values()) {
            String name = pool.name().toLowerCase();
            if (!name.contains("metaspace")) continue;
            if (pool.max() <= 0) continue; // no limit set

            double pct = pool.usagePercent();
            if (pct < 80) return List.of();

            Severity sev = pct >= 95 ? Severity.CRITICAL : Severity.WARNING;
            return List.of(Finding.builder(sev, "Memory",
                            String.format("Metaspace usage %.0f%% (%s / %s)",
                                    pct, formatBytes(pool.used()), formatBytes(pool.max())))
                    .detail("High metaspace usage often indicates a ClassLoader leak — "
                            + "classes are being loaded but never unloaded. Common in app servers "
                            + "after multiple redeployments.")
                    .recommend("Run: argus classstat <pid> to check loaded/unloaded class ratio")
                    .recommend("Check for ClassLoader leaks in web application redeployment")
                    .flag("-XX:MaxMetaspaceSize=512m")
                    .build());
        }
        return List.of();
    }

    private static String formatBytes(long bytes) {
        if (bytes < 1024 * 1024) return (bytes / 1024) + "KB";
        return (bytes / (1024 * 1024)) + "MB";
    }
}
