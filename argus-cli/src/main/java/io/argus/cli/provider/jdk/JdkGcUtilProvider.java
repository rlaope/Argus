package io.argus.cli.provider.jdk;

import io.argus.cli.model.GcUtilResult;
import io.argus.cli.provider.GcUtilProvider;

import java.util.HashMap;
import java.util.Map;

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
     * Columns are located by name from the header line so extra columns inserted
     * by newer JDKs (e.g. CGC/CGCT on JDK 21 with concurrent GC) do not shift
     * the GCT index and produce a wrong value.
     * Package-private for testing.
     */
    public static GcUtilResult parseOutput(String output) {
        if (output == null || output.isBlank()) {
            return empty();
        }
        String[] lines = output.split("\n");
        if (lines.length < 2) {
            return empty();
        }

        // Build column-name → index map from the header line (first line).
        String[] headers = lines[0].trim().split("\\s+");
        Map<String, Integer> col = new HashMap<>();
        for (int i = 0; i < headers.length; i++) {
            col.put(headers[i], i);
        }

        // Parse the data line (last line).
        String dataLine = lines[lines.length - 1].trim();
        String[] values = dataLine.split("\\s+");

        return new GcUtilResult(
                getDouble(values, col, "S0"),
                getDouble(values, col, "S1"),
                getDouble(values, col, "E"),
                getDouble(values, col, "O"),
                getDouble(values, col, "M"),
                getDouble(values, col, "CCS"),
                getLong(values, col, "YGC"),
                getDouble(values, col, "YGCT"),
                getLong(values, col, "FGC"),
                getDouble(values, col, "FGCT"),
                getDouble(values, col, "GCT")
        );
    }

    /** Returns the double value for a named column, or 0.0 if the column is absent. */
    private static double getDouble(String[] values, Map<String, Integer> col, String name) {
        Integer idx = col.get(name);
        if (idx == null || idx >= values.length) {
            return 0.0;
        }
        return JdkParseUtils.parseDouble(values[idx]);
    }

    /** Returns the long value for a named column, or 0 if the column is absent. */
    private static long getLong(String[] values, Map<String, Integer> col, String name) {
        Integer idx = col.get(name);
        if (idx == null || idx >= values.length) {
            return 0L;
        }
        return JdkParseUtils.parseLong(values[idx]);
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
