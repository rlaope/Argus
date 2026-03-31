package io.argus.cli.provider.jdk;

import io.argus.cli.model.MetaspaceResult;
import io.argus.cli.model.MetaspaceResult.SpaceInfo;
import io.argus.cli.provider.MetaspaceProvider;

import java.util.ArrayList;
import java.util.List;

/**
 * MetaspaceProvider that uses {@code jcmd VM.metaspace}.
 *
 * <p>Parses two output formats:
 * <ul>
 *   <li>Top section: "Non-Class: ... capacity, committed, used"</li>
 *   <li>Virtual space section: "Non-class space: reserved, committed"</li>
 * </ul>
 */
public final class JdkMetaspaceProvider implements MetaspaceProvider {

    @Override
    public boolean isAvailable(long pid) {
        return JcmdExecutor.isJcmdAvailable();
    }

    @Override
    public int priority() { return 10; }

    @Override
    public String source() { return "jdk"; }

    @Override
    public MetaspaceResult getMetaspaceInfo(long pid) {
        String output;
        try {
            output = JcmdExecutor.execute(pid, "VM.metaspace");
        } catch (RuntimeException e) {
            return new MetaspaceResult(0, 0, 0, List.of());
        }
        return parseOutput(output);
    }

    static MetaspaceResult parseOutput(String output) {
        if (output == null || output.isEmpty()) {
            return new MetaspaceResult(0, 0, 0, List.of());
        }

        long totalReserved = 0, totalCommitted = 0, totalUsed = 0;
        long ncReserved = 0, ncCommitted = 0, ncUsed = 0;
        long cReserved = 0, cCommitted = 0, cUsed = 0;

        String[] lines = output.split("\n");
        for (int i = 0; i < lines.length; i++) {
            String trimmed = lines[i].trim();

            // Top summary section:
            // "Non-Class: 28797 chunks, 481.81 MB capacity, 481.44 MB (>99%) committed, 477.70 MB (>99%) used, ..."
            // "    Class: 8544 chunks,  73.35 MB capacity,  73.17 MB (>99%) committed,  70.40 MB ( 96%) used, ..."
            // "     Both: 37341 chunks, 555.17 MB capacity, 554.60 MB (>99%) committed, 548.09 MB ( 99%) used, ..."
            if (trimmed.startsWith("Non-Class:") && trimmed.contains("capacity")) {
                ncCommitted = extractSizeBeforeKeyword(trimmed, "committed");
                ncUsed = extractSizeBeforeKeyword(trimmed, "used");
            } else if (trimmed.startsWith("Class:") && trimmed.contains("capacity")) {
                cCommitted = extractSizeBeforeKeyword(trimmed, "committed");
                cUsed = extractSizeBeforeKeyword(trimmed, "used");
            } else if (trimmed.startsWith("Both:") && trimmed.contains("capacity")) {
                totalCommitted = extractSizeBeforeKeyword(trimmed, "committed");
                totalUsed = extractSizeBeforeKeyword(trimmed, "used");
            }

            // Virtual space section:
            // "Non-class space:  512.00 MB reserved,  481.50 MB ( 94%) committed, 8 nodes."
            // "    Class space:    1.00 GB reserved,   73.19 MB (  7%) committed, 1 nodes."
            // "           Both:    1.50 GB reserved,  554.69 MB ( 36%) committed."
            if (trimmed.startsWith("Non-class space:") && trimmed.contains("reserved")) {
                ncReserved = extractSizeBeforeKeyword(trimmed, "reserved");
                if (ncCommitted == 0) ncCommitted = extractSizeBeforeKeyword(trimmed, "committed");
            } else if (trimmed.startsWith("Class space:") && trimmed.contains("reserved")) {
                cReserved = extractSizeBeforeKeyword(trimmed, "reserved");
                if (cCommitted == 0) cCommitted = extractSizeBeforeKeyword(trimmed, "committed");
            } else if (trimmed.startsWith("Both:") && trimmed.contains("reserved")) {
                totalReserved = extractSizeBeforeKeyword(trimmed, "reserved");
                if (totalCommitted == 0) totalCommitted = extractSizeBeforeKeyword(trimmed, "committed");
            }

            // Alternative format: "reserved X, committed Y, used Z"
            if (trimmed.contains("reserved") && trimmed.contains("committed") && trimmed.contains("used")
                    && !trimmed.contains("chunks")) {
                long r = extractSizeAfterKeyword(trimmed, "reserved");
                long c = extractSizeAfterKeyword(trimmed, "committed");
                long u = extractSizeAfterKeyword(trimmed, "used");
                if (r > 0 && r > totalReserved) {
                    totalReserved = r;
                    totalCommitted = c;
                    totalUsed = u;
                }
            }
        }

        List<SpaceInfo> spaces = new ArrayList<>();
        if (ncReserved > 0 || ncCommitted > 0 || ncUsed > 0) {
            spaces.add(new SpaceInfo("Non-Class", ncReserved, ncCommitted, ncUsed));
        }
        if (cReserved > 0 || cCommitted > 0 || cUsed > 0) {
            spaces.add(new SpaceInfo("Class", cReserved, cCommitted, cUsed));
        }

        // Fallback: sum spaces for total
        if (totalReserved == 0 && !spaces.isEmpty()) {
            for (SpaceInfo sp : spaces) {
                totalReserved += sp.reserved();
                totalCommitted += sp.committed();
                totalUsed += sp.used();
            }
        }

        return new MetaspaceResult(totalReserved, totalCommitted, totalUsed, List.copyOf(spaces));
    }

    /**
     * Extracts a size value that appears BEFORE a keyword.
     * E.g., from "481.81 MB capacity" extracts the "481.81 MB" before "capacity".
     * Or from "554.60 MB (>99%) committed" extracts "554.60 MB" before "committed".
     */
    private static long extractSizeBeforeKeyword(String line, String keyword) {
        int kwIdx = line.indexOf(keyword);
        if (kwIdx < 0) return 0;

        // Walk backwards from keyword to find the size
        String before = line.substring(0, kwIdx).trim();
        // Remove trailing parenthetical like "(>99%)" or "( 96%)"
        before = before.replaceAll("\\([^)]*\\)\\s*$", "").trim();
        // Remove trailing comma
        if (before.endsWith(",")) before = before.substring(0, before.length() - 1).trim();

        // Now the last tokens should be like "481.81 MB" or "1.00 GB"
        String[] tokens = before.split("[,\\s]+");
        if (tokens.length < 2) return 0;

        String unit = tokens[tokens.length - 1].toUpperCase();
        String numStr = tokens[tokens.length - 2];

        return parseSize(numStr, unit);
    }

    /**
     * Extracts a size value that appears AFTER a keyword.
     * E.g., from "reserved 1073741824" or "reserved: 1.00 GB"
     */
    private static long extractSizeAfterKeyword(String line, String keyword) {
        int idx = line.indexOf(keyword);
        if (idx < 0) return 0;
        String rest = line.substring(idx + keyword.length()).trim();
        if (rest.startsWith(":")) rest = rest.substring(1).trim();
        if (rest.startsWith(",")) rest = rest.substring(1).trim();

        String[] tokens = rest.split("[,\\s]+");
        if (tokens.length == 0) return 0;

        String numStr = tokens[0];
        String unit = tokens.length > 1 ? tokens[1].toUpperCase() : "";

        return parseSize(numStr, unit);
    }

    private static long parseSize(String numStr, String unit) {
        try {
            double value = Double.parseDouble(numStr);
            return switch (unit) {
                case "KB" -> (long) (value * 1024);
                case "MB" -> (long) (value * 1024 * 1024);
                case "GB" -> (long) (value * 1024L * 1024 * 1024);
                default -> (long) value;
            };
        } catch (NumberFormatException e) {
            return 0;
        }
    }
}
