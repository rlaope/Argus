package io.argus.cli.provider.jdk;

import io.argus.cli.model.AgeDistribution;
import io.argus.cli.provider.GcAgeProvider;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * GcAgeProvider that uses {@code jcmd <pid> GC.heap_info} to extract object age
 * distribution from the survivor spaces. Falls back to jstat for basic survivor
 * data when age histogram is not available in heap_info output.
 */
public final class JdkGcAgeProvider implements GcAgeProvider {

    // Patterns for jcmd GC.heap_info / VM.info age table output
    private static final Pattern DESIRED_SURVIVOR = Pattern.compile(
            "Desired survivor size (\\d+) bytes, new threshold (\\d+) \\(max threshold (\\d+)\\)");
    private static final Pattern AGE_ENTRY = Pattern.compile(
            "-\\s+age\\s+(\\d+):\\s+(\\d+) bytes,\\s+(\\d+) total");

    @Override
    public boolean isAvailable(long pid) {
        return isJcmdAvailable();
    }

    @Override
    public int priority() { return 10; }

    @Override
    public String source() { return "jdk"; }

    @Override
    public AgeDistribution getAgeDistribution(long pid) {
        // Try jcmd GC.heap_info first
        try {
            ProcessBuilder pb = new ProcessBuilder("jcmd", String.valueOf(pid), "GC.heap_info");
            pb.redirectErrorStream(true);
            Process process = pb.start();
            String output = new String(process.getInputStream().readAllBytes()).trim();
            process.waitFor();

            AgeDistribution result = parseAgeOutput(output);
            if (result != null && !result.entries().isEmpty()) return result;
        } catch (Exception ignored) {}

        // Fall back to VM.info which may include age table
        try {
            ProcessBuilder pb = new ProcessBuilder("jcmd", String.valueOf(pid), "VM.info");
            pb.redirectErrorStream(true);
            Process process = pb.start();
            String output = new String(process.getInputStream().readAllBytes()).trim();
            process.waitFor();

            AgeDistribution result = parseAgeOutput(output);
            if (result != null && !result.entries().isEmpty()) return result;
        } catch (Exception ignored) {}

        // Fall back to jstat for survivor capacity, no per-age data
        return fallbackFromJstat(pid);
    }

    /**
     * Parses age table from jcmd output.
     * Matches lines like:
     *   Desired survivor size 1048576 bytes, new threshold 6 (max threshold 15)
     *   - age   1:     524288 bytes,     524288 total
     */
    static AgeDistribution parseAgeOutput(String output) {
        if (output == null || output.isBlank()) return null;

        int tenuringThreshold = 0;
        int maxTenuringThreshold = 15;
        long desiredSurvivorSize = 0;
        List<AgeDistribution.AgeEntry> entries = new ArrayList<>();

        for (String line : output.split("\n")) {
            Matcher dm = DESIRED_SURVIVOR.matcher(line);
            if (dm.find()) {
                desiredSurvivorSize = Long.parseLong(dm.group(1));
                tenuringThreshold = Integer.parseInt(dm.group(2));
                maxTenuringThreshold = Integer.parseInt(dm.group(3));
                continue;
            }

            Matcher am = AGE_ENTRY.matcher(line);
            if (am.find()) {
                int age = Integer.parseInt(am.group(1));
                long bytes = Long.parseLong(am.group(2));
                long cumulative = Long.parseLong(am.group(3));
                entries.add(new AgeDistribution.AgeEntry(age, bytes, cumulative));
            }
        }

        long survivorCapacity = entries.isEmpty() ? 0
                : entries.getLast().cumulativeBytes();
        return new AgeDistribution(entries, tenuringThreshold, maxTenuringThreshold,
                desiredSurvivorSize, survivorCapacity);
    }

    private AgeDistribution fallbackFromJstat(long pid) {
        try {
            ProcessBuilder pb = new ProcessBuilder("jstat", "-gcnew", String.valueOf(pid));
            pb.redirectErrorStream(true);
            Process process = pb.start();
            String output = new String(process.getInputStream().readAllBytes()).trim();
            process.waitFor();

            String[] lines = output.split("\n");
            if (lines.length < 2) return empty();

            String dataLine = lines[lines.length - 1].trim();
            String[] values = dataLine.split("\\s+");
            if (values.length < 7) return empty();

            // S0C=0, S1C=1, S0U=2, S1U=3, TT=4, MTT=5, DSS=6
            long s0c = (long) JdkParseUtils.parseDouble(values[0]) * 1024;
            long s1c = (long) JdkParseUtils.parseDouble(values[1]) * 1024;
            long s0u = (long) JdkParseUtils.parseDouble(values[2]) * 1024;
            long s1u = (long) JdkParseUtils.parseDouble(values[3]) * 1024;
            int tt = (int) JdkParseUtils.parseLong(values[4]);
            int mtt = (int) JdkParseUtils.parseLong(values[5]);
            long dss = (long) JdkParseUtils.parseDouble(values[6]) * 1024;

            // Survivor used = whichever is non-zero
            long survivorUsed = s0u > 0 ? s0u : s1u;
            long survivorCap = s0c > 0 ? s0c : s1c;

            // Without per-age breakdown, return empty entries but with metadata
            return new AgeDistribution(List.of(), tt, mtt, dss, survivorCap);
        } catch (Exception e) {
            return empty();
        }
    }

    private static AgeDistribution empty() {
        return new AgeDistribution(List.of(), 0, 15, 0, 0);
    }

    private static boolean isJcmdAvailable() {
        try {
            Process p = new ProcessBuilder("jcmd", "--help")
                    .redirectErrorStream(true).start();
            p.getInputStream().readAllBytes();
            p.waitFor();
            return true;
        } catch (Exception e) {
            return false;
        }
    }
}
