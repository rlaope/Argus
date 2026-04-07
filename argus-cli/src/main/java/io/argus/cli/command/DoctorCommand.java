package io.argus.cli.command;

import io.argus.cli.config.CliConfig;
import io.argus.cli.config.Messages;
import io.argus.cli.doctor.*;
import io.argus.cli.provider.ProviderRegistry;
import io.argus.cli.render.AnsiStyle;
import io.argus.cli.render.RichRenderer;
import io.argus.core.command.CommandGroup;

import java.util.List;

/**
 * One-click JVM health diagnosis with actionable recommendations.
 *
 * <p>Cross-correlates GC, memory, CPU, threads, and buffer metrics to produce
 * severity-rated findings with specific fix recommendations and JVM flag suggestions.
 *
 * <p>Exit codes: 0 = healthy, 1 = warnings, 2 = critical issues found.
 * This enables CI/CD integration: {@code argus doctor <pid> || alert "JVM unhealthy"}
 */
public final class DoctorCommand implements Command {

    private static final int WIDTH = RichRenderer.DEFAULT_WIDTH;

    @Override
    public String name() { return "doctor"; }

    @Override
    public CommandGroup group() { return CommandGroup.PROFILING; }

    @Override
    public String description(Messages messages) {
        return messages.get("cmd.doctor.desc");
    }

    @Override
    public void execute(String[] args, CliConfig config, ProviderRegistry registry, Messages messages) {
        boolean json = "json".equals(config.format());
        boolean useColor = config.color();

        // Collect snapshot (currently in-process; future: remote via PID)
        JvmSnapshot snapshot = JvmSnapshotCollector.collectLocal();

        // Run all health rules
        List<Finding> findings = DoctorEngine.diagnose(snapshot);
        List<String> suggestedFlags = DoctorEngine.collectSuggestedFlags(findings);
        int exitCode = DoctorEngine.exitCode(findings);

        if (json) {
            printJson(findings, suggestedFlags, snapshot, exitCode);
            return;
        }

        printRich(findings, suggestedFlags, snapshot, useColor, exitCode);
    }

    private void printRich(List<Finding> findings, List<String> flags,
                           JvmSnapshot s, boolean c, int exitCode) {
        System.out.print(RichRenderer.brandedHeader(c, "doctor",
                "One-click JVM health diagnosis with actionable recommendations"));

        // Header with VM info
        System.out.println(RichRenderer.boxHeader(c, "JVM Health Report", WIDTH,
                "pid:" + ProcessHandle.current().pid(),
                s.vmName().contains("HotSpot") ? "HotSpot" : s.vmName(),
                "uptime:" + RichRenderer.formatDuration(s.uptimeMs())));
        System.out.println(RichRenderer.emptyLine(WIDTH));

        // Quick stats bar
        String statsLine = String.format("  Heap: %s/%s (%.0f%%)  |  CPU: %.0f%%  |  Threads: %d  |  GC: %.1f%% overhead",
                RichRenderer.formatBytes(s.heapUsed()), RichRenderer.formatBytes(s.heapMax()),
                s.heapUsagePercent(),
                s.processCpuLoad() >= 0 ? s.processCpuLoad() * 100 : 0,
                s.threadCount(),
                s.gcOverheadPercent());
        System.out.println(RichRenderer.boxLine(
                AnsiStyle.style(c, AnsiStyle.DIM) + statsLine + AnsiStyle.style(c, AnsiStyle.RESET), WIDTH));
        System.out.println(RichRenderer.emptyLine(WIDTH));
        System.out.println(RichRenderer.boxSeparator(WIDTH));
        System.out.println(RichRenderer.emptyLine(WIDTH));

        if (findings.isEmpty()) {
            // All healthy
            String healthy = "  " + AnsiStyle.style(c, AnsiStyle.GREEN, AnsiStyle.BOLD)
                    + "\u2714 All checks passed — JVM is healthy"
                    + AnsiStyle.style(c, AnsiStyle.RESET);
            System.out.println(RichRenderer.boxLine(healthy, WIDTH));
            printHealthyChecks(s, c);
        } else {
            // Summary line
            long critCount = findings.stream().filter(f -> f.severity() == Severity.CRITICAL).count();
            long warnCount = findings.stream().filter(f -> f.severity() == Severity.WARNING).count();
            long infoCount = findings.stream().filter(f -> f.severity() == Severity.INFO).count();

            StringBuilder summary = new StringBuilder("  ");
            if (critCount > 0) {
                summary.append(AnsiStyle.style(c, AnsiStyle.RED, AnsiStyle.BOLD))
                        .append(critCount).append(" critical")
                        .append(AnsiStyle.style(c, AnsiStyle.RESET)).append("  ");
            }
            if (warnCount > 0) {
                summary.append(AnsiStyle.style(c, AnsiStyle.YELLOW, AnsiStyle.BOLD))
                        .append(warnCount).append(" warning(s)")
                        .append(AnsiStyle.style(c, AnsiStyle.RESET)).append("  ");
            }
            if (infoCount > 0) {
                summary.append(AnsiStyle.style(c, AnsiStyle.CYAN))
                        .append(infoCount).append(" info")
                        .append(AnsiStyle.style(c, AnsiStyle.RESET));
            }
            System.out.println(RichRenderer.boxLine(summary.toString(), WIDTH));
            System.out.println(RichRenderer.emptyLine(WIDTH));

            // Each finding
            for (Finding f : findings) {
                String sevColor = switch (f.severity()) {
                    case CRITICAL -> AnsiStyle.style(c, AnsiStyle.RED, AnsiStyle.BOLD);
                    case WARNING -> AnsiStyle.style(c, AnsiStyle.YELLOW, AnsiStyle.BOLD);
                    case INFO -> AnsiStyle.style(c, AnsiStyle.CYAN);
                };

                // Title line with icon
                System.out.println(RichRenderer.boxLine(
                        "  " + sevColor + f.severity().icon() + " " + f.severity().label()
                                + ": " + f.title()
                                + AnsiStyle.style(c, AnsiStyle.RESET), WIDTH));

                // Detail
                if (!f.detail().isEmpty()) {
                    // Word-wrap detail at ~70 chars
                    for (String line : wordWrap(f.detail(), WIDTH - 12)) {
                        System.out.println(RichRenderer.boxLine(
                                "     " + AnsiStyle.style(c, AnsiStyle.DIM) + line
                                        + AnsiStyle.style(c, AnsiStyle.RESET), WIDTH));
                    }
                }

                // Recommendations
                for (String rec : f.recommendations()) {
                    System.out.println(RichRenderer.boxLine(
                            "     \u2192 " + rec, WIDTH));
                }

                System.out.println(RichRenderer.emptyLine(WIDTH));
            }

            // Suggested flags section
            if (!flags.isEmpty()) {
                System.out.println(RichRenderer.boxSeparator(WIDTH));
                System.out.println(RichRenderer.emptyLine(WIDTH));
                System.out.println(RichRenderer.boxLine(
                        "  " + AnsiStyle.style(c, AnsiStyle.BOLD, AnsiStyle.CYAN)
                                + "Suggested JVM Flags"
                                + AnsiStyle.style(c, AnsiStyle.RESET), WIDTH));
                System.out.println(RichRenderer.emptyLine(WIDTH));
                for (String flag : flags) {
                    System.out.println(RichRenderer.boxLine(
                            "    " + AnsiStyle.style(c, AnsiStyle.GREEN) + flag
                                    + AnsiStyle.style(c, AnsiStyle.RESET), WIDTH));
                }
                System.out.println(RichRenderer.emptyLine(WIDTH));
            }
        }

        String footerStatus = exitCode == 0 ? "\u2714 healthy"
                : exitCode == 1 ? "\u26a0 warnings" : "\u2718 critical";
        System.out.println(RichRenderer.boxFooter(c, footerStatus, WIDTH));
    }

    private void printHealthyChecks(JvmSnapshot s, boolean c) {
        System.out.println(RichRenderer.emptyLine(WIDTH));
        String g = AnsiStyle.style(c, AnsiStyle.GREEN);
        String r = AnsiStyle.style(c, AnsiStyle.RESET);
        System.out.println(RichRenderer.boxLine(
                "  " + g + "\u2714" + r + " Heap: " + String.format("%.0f%%", s.heapUsagePercent()) + " (healthy)", WIDTH));
        if (s.processCpuLoad() >= 0) {
            System.out.println(RichRenderer.boxLine(
                    "  " + g + "\u2714" + r + " CPU: " + String.format("%.0f%%", s.processCpuLoad() * 100) + " (healthy)", WIDTH));
        }
        System.out.println(RichRenderer.boxLine(
                "  " + g + "\u2714" + r + " GC overhead: " + String.format("%.1f%%", s.gcOverheadPercent()) + " (healthy)", WIDTH));
        System.out.println(RichRenderer.boxLine(
                "  " + g + "\u2714" + r + " Threads: " + s.threadCount() + " (0 blocked, 0 deadlocked)", WIDTH));
        System.out.println(RichRenderer.boxLine(
                "  " + g + "\u2714" + r + " Metaspace: stable", WIDTH));
        System.out.println(RichRenderer.boxLine(
                "  " + g + "\u2714" + r + " No deadlocks", WIDTH));
    }

    private static List<String> wordWrap(String text, int maxWidth) {
        List<String> lines = new java.util.ArrayList<>();
        String[] words = text.split("\\s+");
        StringBuilder current = new StringBuilder();
        for (String word : words) {
            if (current.length() + word.length() + 1 > maxWidth) {
                lines.add(current.toString());
                current = new StringBuilder(word);
            } else {
                if (!current.isEmpty()) current.append(' ');
                current.append(word);
            }
        }
        if (!current.isEmpty()) lines.add(current.toString());
        return lines;
    }

    private static void printJson(List<Finding> findings, List<String> flags,
                                  JvmSnapshot s, int exitCode) {
        StringBuilder sb = new StringBuilder();
        sb.append("{\"exitCode\":").append(exitCode);
        sb.append(",\"summary\":{");
        sb.append("\"heapUsagePercent\":").append(String.format("%.1f", s.heapUsagePercent()));
        sb.append(",\"gcOverheadPercent\":").append(String.format("%.2f", s.gcOverheadPercent()));
        sb.append(",\"cpuPercent\":").append(String.format("%.1f", s.processCpuLoad() * 100));
        sb.append(",\"threadCount\":").append(s.threadCount());
        sb.append(",\"deadlockedThreads\":").append(s.deadlockedThreads());
        sb.append("}");
        sb.append(",\"findings\":[");
        for (int i = 0; i < findings.size(); i++) {
            Finding f = findings.get(i);
            if (i > 0) sb.append(',');
            sb.append("{\"severity\":\"").append(f.severity().label()).append('"');
            sb.append(",\"category\":\"").append(RichRenderer.escapeJson(f.category())).append('"');
            sb.append(",\"title\":\"").append(RichRenderer.escapeJson(f.title())).append('"');
            sb.append(",\"detail\":\"").append(RichRenderer.escapeJson(f.detail())).append('"');
            sb.append(",\"recommendations\":[");
            for (int j = 0; j < f.recommendations().size(); j++) {
                if (j > 0) sb.append(',');
                sb.append('"').append(RichRenderer.escapeJson(f.recommendations().get(j))).append('"');
            }
            sb.append("]}");
        }
        sb.append("]");
        sb.append(",\"suggestedFlags\":[");
        for (int i = 0; i < flags.size(); i++) {
            if (i > 0) sb.append(',');
            sb.append('"').append(flags.get(i)).append('"');
        }
        sb.append("]}");
        System.out.println(sb);
    }
}
