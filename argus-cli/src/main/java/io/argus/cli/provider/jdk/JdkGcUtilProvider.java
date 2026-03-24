package io.argus.cli.provider.jdk;

import io.argus.cli.model.GcUtilResult;
import io.argus.cli.provider.GcUtilProvider;

/**
 * Provides GC utilization data via {@code jstat -gcutil}.
 */
public final class JdkGcUtilProvider implements GcUtilProvider {

    @Override
    public boolean isAvailable(long pid) {
        return isJstatAvailable();
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
    public GcUtilResult getGcUtil(long pid) {
        try {
            ProcessBuilder pb = new ProcessBuilder("jstat", "-gcutil", String.valueOf(pid));
            pb.redirectErrorStream(true);
            Process process = pb.start();
            String output = new String(process.getInputStream().readAllBytes()).trim();
            process.waitFor();
            return parseOutput(output);
        } catch (Exception e) {
            return empty();
        }
    }

    /**
     * Parses the text output of {@code jstat -gcutil} into a {@link GcUtilResult}.
     * Package-private for testing.
     */
    static GcUtilResult parseOutput(String output) {
        if (output == null || output.isBlank()) {
            return empty();
        }
        String[] lines = output.split("\n");
        if (lines.length < 2) {
            return empty();
        }

        // Parse the data line (last line)
        String dataLine = lines[lines.length - 1].trim();
        String[] values = dataLine.split("\\s+");
        if (values.length < 11) {
            return empty();
        }

        return new GcUtilResult(
                JdkParseUtils.parseDouble(values[0]),   // S0
                JdkParseUtils.parseDouble(values[1]),   // S1
                JdkParseUtils.parseDouble(values[2]),   // E
                JdkParseUtils.parseDouble(values[3]),   // O
                JdkParseUtils.parseDouble(values[4]),   // M
                JdkParseUtils.parseDouble(values[5]),   // CCS
                JdkParseUtils.parseLong(values[6]),     // YGC
                JdkParseUtils.parseDouble(values[7]),   // YGCT
                JdkParseUtils.parseLong(values[8]),     // FGC
                JdkParseUtils.parseDouble(values[9]),   // FGCT
                JdkParseUtils.parseDouble(values[10])   // GCT
        );
    }

    private static GcUtilResult empty() {
        return new GcUtilResult(0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0);
    }

    private static boolean isJstatAvailable() {
        try {
            Process p = new ProcessBuilder("jstat", "-help")
                    .redirectErrorStream(true).start();
            p.getInputStream().readAllBytes();
            return p.waitFor() == 0;
        } catch (Exception e) {
            return false;
        }
    }
}
