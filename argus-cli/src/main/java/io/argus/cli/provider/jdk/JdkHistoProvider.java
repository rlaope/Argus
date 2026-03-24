package io.argus.cli.provider.jdk;

import io.argus.cli.model.HistoResult;
import io.argus.cli.provider.HistoProvider;

import java.util.ArrayList;
import java.util.List;

/**
 * HistoProvider that uses {@code jcmd GC.class_histogram} to obtain heap object histograms.
 */
public final class JdkHistoProvider implements HistoProvider {

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
    public HistoResult getHistogram(long pid, int topN) {
        String output;
        try {
            output = JcmdExecutor.execute(pid, "GC.class_histogram");
        } catch (RuntimeException e) {
            return new HistoResult(List.of(), 0L, 0L);
        }
        return parseOutput(output, topN);
    }

    /**
     * Parses the text output of {@code jcmd GC.class_histogram} into a {@link HistoResult}.
     * Package-private for testing.
     */
    static HistoResult parseOutput(String output, int topN) {
        if (output == null || output.isBlank()) {
            return new HistoResult(List.of(), 0L, 0L);
        }

        List<HistoResult.Entry> entries = new ArrayList<>();
        long totalInstances = 0L;
        long totalBytes = 0L;
        boolean pastHeader = false;

        for (String line : output.split("\n")) {
            String trimmed = line.trim();
            if (trimmed.isEmpty()) continue;

            // The separator line marks end of header
            if (trimmed.startsWith("---")) {
                pastHeader = true;
                continue;
            }

            if (!pastHeader) continue;

            // Parse "Total" line: "Total        500000       50000000"
            if (trimmed.startsWith("Total")) {
                String[] parts = trimmed.split("\\s+");
                if (parts.length >= 3) {
                    totalInstances = JdkParseUtils.parseLong(parts[1]);
                    totalBytes = JdkParseUtils.parseLong(parts[2]);
                }
                continue;
            }

            // Parse data lines: "     1:        123456       12345678  [B (java.base@21)"
            // Format after trim: "1:        123456       12345678  [B (java.base@21)"
            if (!trimmed.matches("\\d+:.*")) continue;

            String[] parts = trimmed.split("\\s+", 4);
            if (parts.length < 4) continue;

            String rankStr = parts[0].replace(":", "");
            int rank;
            try {
                rank = Integer.parseInt(rankStr);
            } catch (NumberFormatException e) {
                continue;
            }

            long instances = JdkParseUtils.parseLong(parts[1]);
            long bytes = JdkParseUtils.parseLong(parts[2]);
            String className = stripModuleInfo(parts[3]);

            if (entries.size() < topN) {
                entries.add(new HistoResult.Entry(rank, className, instances, bytes));
            }
        }

        return new HistoResult(List.copyOf(entries), totalInstances, totalBytes);
    }

    /**
     * Removes module info such as "(java.base@21)" from a class name.
     */
    private static String stripModuleInfo(String raw) {
        int parenIdx = raw.indexOf('(');
        if (parenIdx > 0) {
            return raw.substring(0, parenIdx).trim();
        }
        return raw.trim();
    }

}
