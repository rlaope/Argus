package io.argus.cli.provider.jdk;

import io.argus.cli.model.ProcessInfo;
import io.argus.cli.provider.ProcessProvider;

import java.util.ArrayList;
import java.util.List;

/**
 * ProcessProvider that uses {@code jcmd -l} to list running JVM processes.
 */
public final class JdkProcessProvider implements ProcessProvider {

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
    public List<ProcessInfo> listProcesses() {
        String output;
        try {
            output = JcmdExecutor.execute("-l");
        } catch (RuntimeException e) {
            return List.of();
        }

        List<ProcessInfo> result = new ArrayList<>();
        for (String line : output.split("\n")) {
            String trimmed = line.trim();
            if (trimmed.isEmpty()) continue;
            // Skip jcmd's own process entries
            if (trimmed.contains("jdk.jcmd") || trimmed.contains("sun.tools")) continue;

            String[] tokens = trimmed.split("\\s+", 3);
            if (tokens.length < 1) continue;

            long pid;
            try {
                pid = Long.parseLong(tokens[0]);
            } catch (NumberFormatException e) {
                continue;
            }

            String mainClass = tokens.length >= 2 ? tokens[1] : "";
            String arguments = tokens.length >= 3 ? tokens[2] : "";

            // Try to get Java version and uptime for each process
            String javaVersion = "";
            long uptimeMs = 0;
            try {
                String versionOutput = JcmdExecutor.execute(pid, "VM.version");
                for (String vLine : versionOutput.split("\n")) {
                    String v = vLine.trim();
                    if (v.contains("JDK") || v.contains("jdk") || v.matches(".*\\d+\\.\\d+\\.\\d+.*")) {
                        javaVersion = v;
                        break;
                    }
                }
            } catch (RuntimeException ignored) {}
            try {
                String uptimeOutput = JcmdExecutor.execute(pid, "VM.uptime");
                for (String uLine : uptimeOutput.split("\n")) {
                    String u = uLine.trim();
                    if (u.isEmpty()) continue;
                    String[] uParts = u.split("\\s+");
                    try {
                        uptimeMs = (long) (Double.parseDouble(uParts[0]) * 1000.0);
                        break;
                    } catch (NumberFormatException ignored2) {}
                }
            } catch (RuntimeException ignored) {}

            result.add(new ProcessInfo(pid, mainClass, arguments, javaVersion, uptimeMs));
        }
        return List.copyOf(result);
    }
}
