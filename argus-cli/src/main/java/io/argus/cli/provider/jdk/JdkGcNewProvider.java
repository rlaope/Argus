package io.argus.cli.provider.jdk;

import io.argus.cli.model.GcNewResult;
import io.argus.cli.provider.GcNewProvider;

/**
 * GcNewProvider that uses {@code jstat -gcnew} for young gen stats.
 */
public final class JdkGcNewProvider implements GcNewProvider {

    @Override
    public boolean isAvailable(long pid) {
        return isJstatAvailable();
    }

    @Override
    public int priority() { return 10; }

    @Override
    public String source() { return "jdk"; }

    @Override
    public GcNewResult getGcNew(long pid) {
        try {
            ProcessBuilder pb = new ProcessBuilder("jstat", "-gcnew", String.valueOf(pid));
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
     * Parses jstat -gcnew output.
     * Header: S0C    S1C    S0U    S1U   TT MTT  DSS      EC       EU     YGC     YGCT
     */
    static GcNewResult parseOutput(String output) {
        if (output == null || output.isBlank()) return empty();

        String[] lines = output.split("\n");
        if (lines.length < 2) return empty();

        String dataLine = lines[lines.length - 1].trim();
        String[] values = dataLine.split("\\s+");
        if (values.length < 11) return empty();

        return new GcNewResult(
                JdkParseUtils.parseDouble(values[0]),  // S0C
                JdkParseUtils.parseDouble(values[1]),  // S1C
                JdkParseUtils.parseDouble(values[2]),  // S0U
                JdkParseUtils.parseDouble(values[3]),  // S1U
                (int) JdkParseUtils.parseLong(values[4]),  // TT
                (int) JdkParseUtils.parseLong(values[5]),  // MTT
                JdkParseUtils.parseDouble(values[6]),  // DSS
                JdkParseUtils.parseDouble(values[7]),  // EC
                JdkParseUtils.parseDouble(values[8]),  // EU
                JdkParseUtils.parseLong(values[9]),    // YGC
                JdkParseUtils.parseDouble(values[10])  // YGCT
        );
    }

    private static GcNewResult empty() {
        return new GcNewResult(0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0);
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
