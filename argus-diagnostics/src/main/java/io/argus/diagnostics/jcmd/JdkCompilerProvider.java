package io.argus.diagnostics.jcmd;

import io.argus.diagnostics.model.CompilerResult;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * CompilerProvider that uses {@code jcmd Compiler.codecache} and {@code Compiler.queue}.
 */
public final class JdkCompilerProvider {

    public boolean isAvailable(long pid) {
        return JcmdExecutor.isJcmdAvailable();
    }

    public int priority() { return 10; }

    public String source() { return "jdk"; }

    public CompilerResult getCompilerInfo(long pid) {
        String codecacheOutput;
        String queueOutput;
        String perfCounterOutput;
        try {
            codecacheOutput = JcmdExecutor.execute(pid, "Compiler.codecache");
        } catch (RuntimeException e) {
            codecacheOutput = "";
        }
        try {
            queueOutput = JcmdExecutor.execute(pid, "Compiler.queue");
        } catch (RuntimeException e) {
            queueOutput = "";
        }
        try {
            perfCounterOutput = JcmdExecutor.execute(pid, "PerfCounter.print");
        } catch (RuntimeException e) {
            perfCounterOutput = "";
        }
        return parseOutput(codecacheOutput, queueOutput, perfCounterOutput);
    }

    static CompilerResult parseOutput(String codecacheOutput, String queueOutput) {
        return parseOutput(codecacheOutput, queueOutput, "");
    }

    static CompilerResult parseOutput(String codecacheOutput, String queueOutput, String perfCounterOutput) {
        long size = 0, used = 0, maxUsed = 0, free = 0;
        int blobs = 0, nmethods = 0, adapters = 0;
        boolean enabled = true;

        for (String line : codecacheOutput.split("\n")) {
            String trimmed = line.trim();

            // "CodeHeap 'non-nmethods': size=5696Kb used=1234Kb max_used=..."
            // Or summary: "CodeCache: size=245760Kb used=1234Kb max_used=5678Kb free=240000Kb"
            if (trimmed.startsWith("CodeCache:") || trimmed.startsWith("CodeHeap")) {
                size += extractKb(trimmed, "size=");
                used += extractKb(trimmed, "used=");
                maxUsed += extractKb(trimmed, "max_used=");
                free += extractKb(trimmed, "free=");
            }

            if (trimmed.startsWith("total_blobs=") || trimmed.contains("total_blobs=")) {
                blobs += extractInt(trimmed, "total_blobs=");
                nmethods += extractInt(trimmed, "nmethods=");
                adapters += extractInt(trimmed, "adapters=");
            }

            if (trimmed.contains("compilation:")) {
                enabled = trimmed.contains("enabled");
            }
        }

        // Count queue entries
        int queueSize = 0;
        if (!queueOutput.isEmpty()) {
            for (String line : queueOutput.split("\n")) {
                String trimmed = line.trim();
                // Each compilation task is a numbered line
                if (!trimmed.isEmpty() && Character.isDigit(trimmed.charAt(0))) {
                    queueSize++;
                }
            }
        }

        long deoptCount = parseDeoptCount(perfCounterOutput);

        return new CompilerResult(size, used, maxUsed, free, blobs, nmethods, adapters, enabled, queueSize, deoptCount);
    }

    /**
     * Parse {@code sun.ci.totalInvalidates} from {@code jcmd PerfCounter.print} output.
     * This counter tracks runtime nmethod invalidations (deopts due to class redefinition,
     * type-profile mismatches, etc.) and has been stable in HotSpot since JDK 8. Returns
     * 0 when the counter is absent or the perfcounter call failed.
     */
    static long parseDeoptCount(String perfCounterOutput) {
        if (perfCounterOutput == null || perfCounterOutput.isEmpty()) return 0L;
        for (String line : perfCounterOutput.split("\n")) {
            String trimmed = line.trim();
            int eq = trimmed.indexOf('=');
            if (eq <= 0) continue;
            String key = trimmed.substring(0, eq);
            if (!key.equals("sun.ci.totalInvalidates")) continue;
            String value = trimmed.substring(eq + 1).trim();
            try {
                return Long.parseLong(value);
            } catch (NumberFormatException ignored) {
                return 0L;
            }
        }
        return 0L;
    }

    private static long extractKb(String line, String prefix) {
        int idx = line.indexOf(prefix);
        if (idx < 0) return 0;
        String rest = line.substring(idx + prefix.length());
        StringBuilder sb = new StringBuilder();
        for (char c : rest.toCharArray()) {
            if (Character.isDigit(c)) sb.append(c);
            else break;
        }
        if (sb.length() == 0) return 0;
        return Long.parseLong(sb.toString());
    }

    private static int extractInt(String line, String prefix) {
        int idx = line.indexOf(prefix);
        if (idx < 0) return 0;
        String rest = line.substring(idx + prefix.length());
        StringBuilder sb = new StringBuilder();
        for (char c : rest.toCharArray()) {
            if (Character.isDigit(c)) sb.append(c);
            else break;
        }
        if (sb.length() == 0) return 0;
        return Integer.parseInt(sb.toString());
    }
}
