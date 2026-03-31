package io.argus.cli.provider.jdk;

import io.argus.cli.model.ClassStatResult;
import io.argus.cli.provider.ClassStatProvider;

/**
 * ClassStatProvider that uses {@code jstat -class} for class loading stats.
 */
public final class JdkClassStatProvider implements ClassStatProvider {

    @Override
    public boolean isAvailable(long pid) {
        return isJstatAvailable();
    }

    @Override
    public int priority() { return 10; }

    @Override
    public String source() { return "jdk"; }

    @Override
    public ClassStatResult getClassStats(long pid) {
        try {
            ProcessBuilder pb = new ProcessBuilder("jstat", "-class", String.valueOf(pid));
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
     * Parses jstat -class output.
     * Header: Loaded  Bytes  Unloaded  Bytes     Time
     */
    static ClassStatResult parseOutput(String output) {
        if (output == null || output.isBlank()) return empty();

        String[] lines = output.split("\n");
        if (lines.length < 2) return empty();

        String dataLine = lines[lines.length - 1].trim();
        String[] values = dataLine.split("\\s+");
        if (values.length < 5) return empty();

        return new ClassStatResult(
                JdkParseUtils.parseLong(values[0]),    // Loaded
                JdkParseUtils.parseDouble(values[1]),  // Bytes (KB)
                JdkParseUtils.parseLong(values[2]),    // Unloaded
                JdkParseUtils.parseDouble(values[3]),  // Bytes (KB)
                JdkParseUtils.parseDouble(values[4])   // Time
        );
    }

    private static ClassStatResult empty() {
        return new ClassStatResult(0, 0, 0, 0, 0);
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
