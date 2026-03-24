package io.argus.cli.provider.jdk;

import io.argus.cli.model.NmtResult;
import io.argus.cli.provider.NmtProvider;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Provides native memory tracking data via {@code jcmd VM.native_memory summary}.
 */
public final class JdkNmtProvider implements NmtProvider {

    // Matches: "Total: reserved=8192000KB, committed=2048000KB"
    private static final Pattern TOTAL_PATTERN =
            Pattern.compile("Total:\\s+reserved=(\\S+),\\s+committed=(\\S+)");

    // Matches: "-   Category (reserved=1234KB, committed=567KB)"
    // Also handles leading spaces before the dash
    private static final Pattern CATEGORY_PATTERN =
            Pattern.compile("^-\\s+(\\S[^(]+?)\\s+\\(reserved=(\\S+),\\s+committed=(\\S+)\\)");

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
    public NmtResult getNativeMemory(long pid) {
        try {
            String output = JcmdExecutor.execute(pid, "VM.native_memory summary");
            if (output.contains("Native memory tracking is not enabled")) {
                return new NmtResult(0, 0, List.of());
            }
            return parse(output);
        } catch (Exception e) {
            return new NmtResult(0, 0, List.of());
        }
    }

    private static NmtResult parse(String output) {
        long totalReservedKB = 0;
        long totalCommittedKB = 0;
        List<NmtResult.NmtCategory> categories = new ArrayList<>();

        String[] lines = output.split("\n");
        for (String line : lines) {
            // Try total line first
            Matcher totalMatcher = TOTAL_PATTERN.matcher(line);
            if (totalMatcher.find()) {
                totalReservedKB = parseToKB(totalMatcher.group(1));
                totalCommittedKB = parseToKB(totalMatcher.group(2));
                continue;
            }

            // Try category line
            Matcher catMatcher = CATEGORY_PATTERN.matcher(line.trim());
            if (catMatcher.find()) {
                String name = catMatcher.group(1).trim();
                long reservedKB = parseToKB(catMatcher.group(2));
                long committedKB = parseToKB(catMatcher.group(3));
                categories.add(new NmtResult.NmtCategory(name, reservedKB, committedKB));
            }
        }

        // Sort categories by committed size descending
        categories.sort((a, b) -> Long.compare(b.committedKB(), a.committedKB()));

        return new NmtResult(totalReservedKB, totalCommittedKB, categories);
    }

    /**
     * Converts a size string like "4096000KB", "2048MB", "4GB" to KB.
     */
    private static long parseToKB(String s) {
        if (s == null || s.isEmpty()) return 0;
        s = s.trim();
        try {
            if (s.endsWith("KB")) {
                return Long.parseLong(s.substring(0, s.length() - 2).replace(",", "").trim());
            } else if (s.endsWith("MB")) {
                return Long.parseLong(s.substring(0, s.length() - 2).replace(",", "").trim()) * 1024;
            } else if (s.endsWith("GB")) {
                // May be decimal: "4GB"
                double gb = Double.parseDouble(s.substring(0, s.length() - 2).replace(",", "").trim());
                return (long) (gb * 1024 * 1024);
            } else if (s.endsWith("TB")) {
                double tb = Double.parseDouble(s.substring(0, s.length() - 2).replace(",", "").trim());
                return (long) (tb * 1024 * 1024 * 1024);
            } else {
                // plain bytes
                long bytes = Long.parseLong(s.replace(",", "").trim());
                return bytes / 1024;
            }
        } catch (NumberFormatException e) {
            return 0;
        }
    }
}
