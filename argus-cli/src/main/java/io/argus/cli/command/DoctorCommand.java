package io.argus.cli.command;

import io.argus.cli.config.CliConfig;
import io.argus.cli.config.Messages;
import io.argus.diagnostics.doctor.*;
import io.argus.cli.doctor.ProfileRules;
import io.argus.cli.llm.LlmAdvisoryRenderer;
import io.argus.cli.llm.LlmConfig;
import io.argus.cli.llm.LlmRootCause;
import io.argus.cli.provider.jdk.AsProfCapabilities;
import io.argus.diagnostics.doctor.rules.MaxPauseRule;
import io.argus.cli.export.HtmlExporter;
import io.argus.cli.model.ProfileSnapshot;
import io.argus.cli.model.ProfileResult;
import io.argus.cli.provider.ProfileProvider;
import io.argus.cli.provider.ProviderRegistry;
import io.argus.cli.render.AnsiStyle;
import io.argus.cli.render.RichRenderer;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
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
        String exportHtml = null;
        long pid = 0; // 0 = local
        long pauseThresholdMs = MaxPauseRule.DEFAULT_WARN_MS;

        // Profile flags
        boolean profileLive = false;
        String profileSnapshotPath = null;  // non-null → load from file
        int profileDurationSec = 5;

        // Opt-in LLM root-cause flag (default OFF — gated by LlmConfig).
        boolean rca = false;

        for (String arg : args) {
            if (arg.startsWith("--export=")) exportHtml = arg.substring(9);
            else if (arg.equals("--format=json")) json = true;
            else if (arg.equals("--rca")) rca = true;
            else if (arg.startsWith("--pause-threshold-ms=")) {
                try { pauseThresholdMs = Long.parseLong(arg.substring(21)); }
                catch (NumberFormatException ignored) {}
            } else if (arg.equals("--profile")) {
                profileLive = true;
            } else if (arg.startsWith("--profile=")) {
                profileSnapshotPath = arg.substring(10);
            } else if (arg.startsWith("--profile-duration=")) {
                try { profileDurationSec = Integer.parseInt(arg.substring(19)); }
                catch (NumberFormatException ignored) {}
            } else if (!arg.startsWith("--")) {
                try { pid = Long.parseLong(arg); } catch (NumberFormatException ignored) {}
            }
        }

        JvmSnapshot snapshot = JvmSnapshotCollector.collect(pid);
        List<Finding> findings = DoctorEngine.diagnose(snapshot, pauseThresholdMs);
        List<String> suggestedFlags = DoctorEngine.collectSuggestedFlags(findings);
        int exitCode = DoctorEngine.exitCode(findings);

        // Collect profile findings (if requested)
        List<Finding> profileFindings = List.of();
        String profileError = null;

        if (profileSnapshotPath != null) {
            try {
                ProfileSnapshot ps = ProfileSnapshot.load(Path.of(profileSnapshotPath));
                profileFindings = ProfileRules.diagnose(ps);
            } catch (Exception e) {
                profileError = "Could not load snapshot '" + profileSnapshotPath + "': " + e.getMessage();
            }
        } else if (profileLive) {
            final long targetPid = pid > 0 ? pid : ProcessHandle.current().pid();
            try {
                ProfileProvider pp = registry.find(ProfileProvider.class, targetPid, null);
                if (pp == null) {
                    profileError = "No profile provider available for pid " + targetPid;
                } else {
                    ProfileResult pr = pp.profile(targetPid, "cpu", profileDurationSec);
                    if (!"ok".equals(pr.status())) {
                        profileError = "Profile capture failed: " + pr.errorMessage();
                    } else {
                        // Convert ProfileResult → ProfileSnapshot in-memory
                        ProfileSnapshot ps = new ProfileSnapshot(
                                "live", java.time.Instant.now().toString(), java.time.Instant.now().getEpochSecond(),
                                targetPid, pr.type() != null ? pr.type() : "cpu",
                                pr.durationSec(), pr.totalSamples(), pr.topMethods());
                        profileFindings = ProfileRules.diagnose(ps);
                    }
                }
            } catch (Exception e) {
                profileError = "Profile analysis skipped: " + e.getMessage();
            }
        }

        if (json) {
            printJson(findings, suggestedFlags, snapshot, exitCode, profileFindings, profileError);
            return;
        }

        if (exportHtml != null) {
            // Capture output to string, convert to HTML
            PrintStream original = System.out;
            ByteArrayOutputStream capture = new ByteArrayOutputStream();
            System.setOut(new PrintStream(capture));
            printRich(findings, suggestedFlags, snapshot, true, exitCode, pid,
                      profileFindings, profileError, profileSnapshotPath != null || profileLive);
            System.setOut(original);
            String html = HtmlExporter.toHtml(capture.toString(), "Argus Doctor Report");
            try {
                Path outPath = Path.of(exportHtml.equals("html") ? "argus-doctor.html" : exportHtml);
                Files.writeString(outPath, html);
                System.out.println("Exported to: " + outPath.toAbsolutePath());
            } catch (Exception e) {
                System.err.println("Export failed: " + e.getMessage());
            }
            return;
        }

        printRich(findings, suggestedFlags, snapshot, useColor, exitCode, pid,
                  profileFindings, profileError, profileSnapshotPath != null || profileLive);

        // Opt-in LLM root cause. The deterministic findings above are ALWAYS
        // shown; this only appends an advisory block when --rca is passed AND
        // the feature is enabled with a key. Disabled ⇒ no provider, no network.
        if (rca) {
            List<Finding> all = new java.util.ArrayList<>(findings);
            all.addAll(profileFindings);
            LlmRootCause.Result rcaResult =
                    new LlmRootCause(LlmConfig.fromEnvironment()).analyze(all);
            LlmAdvisoryRenderer.print(rcaResult, useColor, messages);
        }

        if (exitCode > 0) throw new CommandExitException(exitCode);
    }

    private void printRich(List<Finding> findings, List<String> flags,
                           JvmSnapshot s, boolean c, int exitCode, long targetPid,
                           List<Finding> profileFindings, String profileError, boolean profileRequested) {
        System.out.print(RichRenderer.brandedHeader(c, "doctor",
                "One-click JVM health diagnosis with actionable recommendations"));

        // Header with VM info — show the PID the user actually asked about, not the CLI process.
        long displayPid = targetPid > 0 ? targetPid : ProcessHandle.current().pid();
        System.out.println(RichRenderer.boxHeader(c, "JVM Health Report", WIDTH,
                "pid:" + displayPid,
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
                String sevColor;
                switch (f.severity()) {
                    case CRITICAL: sevColor = AnsiStyle.style(c, AnsiStyle.RED, AnsiStyle.BOLD);    break;
                    case WARNING:  sevColor = AnsiStyle.style(c, AnsiStyle.YELLOW, AnsiStyle.BOLD); break;
                    default:       sevColor = AnsiStyle.style(c, AnsiStyle.CYAN);                   break;
                }

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

        // \u2500\u2500 Profile Findings section \u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500
        if (profileRequested) {
            System.out.println(RichRenderer.boxSeparator(WIDTH));
            System.out.println(RichRenderer.emptyLine(WIDTH));
            System.out.println(RichRenderer.boxLine(
                    "  " + AnsiStyle.style(c, AnsiStyle.BOLD, AnsiStyle.CYAN)
                            + "\ud83d\udd0d Profile Findings"
                            + AnsiStyle.style(c, AnsiStyle.RESET), WIDTH));
            System.out.println(RichRenderer.emptyLine(WIDTH));

            if (profileError != null) {
                System.out.println(RichRenderer.boxLine(
                        "  " + AnsiStyle.style(c, AnsiStyle.YELLOW)
                                + "\u26a0 Profile analysis skipped: " + profileError
                                + AnsiStyle.style(c, AnsiStyle.RESET), WIDTH));
            } else if (profileFindings.isEmpty()) {
                System.out.println(RichRenderer.boxLine(
                        "  " + AnsiStyle.style(c, AnsiStyle.GREEN)
                                + "\u2714 No notable hot-method patterns found"
                                + AnsiStyle.style(c, AnsiStyle.RESET), WIDTH));
            } else {
                for (Finding f : profileFindings) {
                    String sevColor;
                    switch (f.severity()) {
                        case CRITICAL: sevColor = AnsiStyle.style(c, AnsiStyle.RED, AnsiStyle.BOLD);    break;
                        case WARNING:  sevColor = AnsiStyle.style(c, AnsiStyle.YELLOW, AnsiStyle.BOLD); break;
                        default:       sevColor = AnsiStyle.style(c, AnsiStyle.CYAN);                   break;
                    }
                    System.out.println(RichRenderer.boxLine(
                            "  " + sevColor + f.severity().icon() + " " + f.severity().label()
                                    + ": " + f.title()
                                    + AnsiStyle.style(c, AnsiStyle.RESET), WIDTH));
                    if (!f.detail().isEmpty()) {
                        for (String line : wordWrap(f.detail(), WIDTH - 12)) {
                            System.out.println(RichRenderer.boxLine(
                                    "     " + AnsiStyle.style(c, AnsiStyle.DIM) + line
                                            + AnsiStyle.style(c, AnsiStyle.RESET), WIDTH));
                        }
                    }
                    for (String rec : f.recommendations()) {
                        System.out.println(RichRenderer.boxLine("     \u2192 " + rec, WIDTH));
                    }
                    System.out.println(RichRenderer.emptyLine(WIDTH));
                }
            }
            System.out.println(RichRenderer.emptyLine(WIDTH));
        }

        // Profiling capabilities summary \u2014 always shown so users see it without --profile
        System.out.println(RichRenderer.boxSeparator(WIDTH));
        System.out.println(RichRenderer.emptyLine(WIDTH));
        String profCapLine = "  " + AnsiStyle.style(c, AnsiStyle.GREEN) + "\u2714" + AnsiStyle.style(c, AnsiStyle.RESET)
                + " Profiling: async-profiler v" + AsProfCapabilities.ASPROF_VERSION
                + " (" + AsProfCapabilities.displayPlatform() + ")"
                + " \u2014 events: " + AsProfCapabilities.supportedEventList();
        System.out.println(RichRenderer.boxLine(profCapLine, WIDTH));
        System.out.println(RichRenderer.emptyLine(WIDTH));

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
                "  " + g + "\u2714" + r + " Threads: " + s.threadCount()
                + " (" + s.threadStates().getOrDefault("BLOCKED", 0) + " blocked, "
                + s.deadlockedThreads() + " deadlocked)", WIDTH));
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
                if (current.length() > 0) current.append(' ');
                current.append(word);
            }
        }
        if (current.length() > 0) lines.add(current.toString());
        return lines;
    }

    private static void printJson(List<Finding> findings, List<String> flags,
                                  JvmSnapshot s, int exitCode,
                                  List<Finding> profileFindings, String profileError) {
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
            sb.append('"').append(RichRenderer.escapeJson(flags.get(i))).append('"');
        }
        sb.append("]");
        // Profile findings array
        sb.append(",\"profileFindings\":[");
        for (int i = 0; i < profileFindings.size(); i++) {
            Finding f = profileFindings.get(i);
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
        if (profileError != null) {
            sb.append(",\"profileError\":\"").append(RichRenderer.escapeJson(profileError)).append('"');
        }
        sb.append("}");
        System.out.println(sb);
    }
}
