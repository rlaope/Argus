package io.argus.cli.provider.jdk;

import io.argus.cli.model.InfoResult;
import io.argus.cli.provider.InfoProvider;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * InfoProvider that uses multiple jcmd sub-commands to collect JVM metadata.
 */
public final class JdkInfoProvider implements InfoProvider {

    private static final Set<String> INCLUDED_PROPERTIES = Set.of(
            "java.version", "java.vendor", "os.name", "os.arch", "user.dir"
    );

    @Override
    public boolean isAvailable(long pid) {
        return JcmdExecutor.isJcmdAvailable();
    }

    @Override
    public int priority() {
        return 10;
    }

    @Override
    public String source() {
        return "jdk";
    }

    @Override
    public InfoResult getVmInfo(long pid) {
        String vmName = "";
        String vmVersion = "";
        String vmVendor = "";
        long uptimeMs = 0L;
        List<String> vmFlags = new ArrayList<>();
        Map<String, String> systemProperties = new HashMap<>();

        // VM.version: extract vmName and vmVersion
        try {
            String versionOutput = JcmdExecutor.execute(pid, "VM.version");
            for (String line : versionOutput.split("\n")) {
                String trimmed = line.trim();
                if (trimmed.isEmpty()) continue;
                if (vmName.isEmpty()) {
                    vmName = trimmed;
                } else if (vmVersion.isEmpty() && (trimmed.contains("build") || trimmed.matches(".*\\d+\\..*"))) {
                    vmVersion = trimmed;
                } else if (vmVendor.isEmpty() && trimmed.toLowerCase().contains("vendor")) {
                    vmVendor = trimmed;
                }
            }
        } catch (RuntimeException ignored) {}

        // VM.flags: collect all flags as strings
        try {
            String flagsOutput = JcmdExecutor.execute(pid, "VM.flags");
            for (String line : flagsOutput.split("\n")) {
                String trimmed = line.trim();
                if (trimmed.isEmpty()) continue;
                // Each flag token is like "-XX:+UseG1GC" etc. The output may be a single long line
                String[] tokens = trimmed.split("\\s+");
                for (String token : tokens) {
                    if (token.startsWith("-")) {
                        vmFlags.add(token);
                    }
                }
            }
        } catch (RuntimeException ignored) {}

        // VM.uptime: format is "1234.567 s"
        try {
            String uptimeOutput = JcmdExecutor.execute(pid, "VM.uptime");
            for (String line : uptimeOutput.split("\n")) {
                String trimmed = line.trim();
                if (trimmed.isEmpty()) continue;
                String[] parts = trimmed.split("\\s+");
                if (parts.length >= 1) {
                    try {
                        double seconds = Double.parseDouble(parts[0]);
                        uptimeMs = (long) (seconds * 1000.0);
                        break;
                    } catch (NumberFormatException ignored2) {}
                }
            }
        } catch (RuntimeException ignored) {}

        // VM.system_properties: "key=value" lines, capture selected keys only
        try {
            String propsOutput = JcmdExecutor.execute(pid, "VM.system_properties");
            String currentKey = null;
            StringBuilder currentValue = new StringBuilder();

            for (String line : propsOutput.split("\n")) {
                // Continuation lines start with whitespace (backslash-continued values)
                if (currentKey != null && (line.startsWith(" ") || line.startsWith("\t"))) {
                    currentValue.append(line.trim());
                    continue;
                }

                // Flush previous entry
                if (currentKey != null) {
                    if (INCLUDED_PROPERTIES.contains(currentKey)) {
                        systemProperties.put(currentKey, currentValue.toString());
                    }
                    currentKey = null;
                    currentValue.setLength(0);
                }

                int eqIdx = line.indexOf('=');
                if (eqIdx > 0) {
                    String key = line.substring(0, eqIdx).trim();
                    String value = line.substring(eqIdx + 1).trim();
                    if (INCLUDED_PROPERTIES.contains(key)) {
                        currentKey = key;
                        currentValue.append(value);
                    }
                }
            }

            // Flush last entry
            if (currentKey != null && INCLUDED_PROPERTIES.contains(currentKey)) {
                systemProperties.put(currentKey, currentValue.toString());
            }
        } catch (RuntimeException ignored) {}

        // Derive vmVendor from system properties if not already found
        if (vmVendor.isEmpty() && systemProperties.containsKey("java.vendor")) {
            vmVendor = systemProperties.get("java.vendor");
        }

        // CPU info via OperatingSystemMXBean (from VM.info or jcmd)
        double processCpuLoad = -1;
        double systemCpuLoad = -1;
        int availableProcessors = 0;
        double systemLoadAverage = -1;
        try {
            String vmInfo = JcmdExecutor.execute(pid, "VM.info");
            for (String line : vmInfo.split("\n")) {
                String trimmed = line.trim();
                if (trimmed.startsWith("available_processors")) {
                    String[] parts = trimmed.split("=");
                    if (parts.length >= 2) {
                        availableProcessors = Integer.parseInt(parts[1].trim());
                    }
                }
            }
        } catch (RuntimeException ignored) {}

        // Try to get CPU load from PerfCounter data
        try {
            java.lang.management.OperatingSystemMXBean os =
                    java.lang.management.ManagementFactory.getOperatingSystemMXBean();
            availableProcessors = availableProcessors > 0 ? availableProcessors : os.getAvailableProcessors();
            systemLoadAverage = os.getSystemLoadAverage();
            if (os instanceof com.sun.management.OperatingSystemMXBean sunOs) {
                processCpuLoad = sunOs.getProcessCpuLoad();
                systemCpuLoad = sunOs.getCpuLoad();
            }
        } catch (Exception ignored) {}

        return new InfoResult(
                vmName,
                vmVersion,
                vmVendor,
                uptimeMs,
                pid,
                List.copyOf(vmFlags),
                Map.copyOf(systemProperties),
                processCpuLoad,
                systemCpuLoad,
                availableProcessors,
                systemLoadAverage
        );
    }
}
