package io.argus.cli.command;

import io.argus.cli.config.CliConfig;
import io.argus.cli.config.Messages;
import io.argus.cli.model.ProfileSnapshot;
import io.argus.cli.provider.ProviderRegistry;
import io.argus.cli.render.AnsiStyle;
import io.argus.cli.render.RichRenderer;
import io.argus.core.command.CommandGroup;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

/**
 * CI/CD gate command that compares two profile snapshots and exits non-zero on regression.
 *
 * <p>Usage:
 * <pre>
 * argus profile-gate before.json after.json [options]
 * argus profile-gate --before=PATH --after=PATH [options]
 * </pre>
 *
 * <p>Exit codes: 0=pass, 1=regression(s) detected, 2=usage/IO error
 */
public final class ProfileGateCommand implements Command {

    private static final int WIDTH = RichRenderer.DEFAULT_WIDTH;

    @Override public String name()  { return "profile-gate"; }
    @Override public CommandGroup group() { return CommandGroup.PROFILING; }
    @Override public CommandMode mode()   { return CommandMode.READ; }
    @Override public boolean supportsTui() { return false; }

    @Override
    public String description(Messages messages) {
        return messages.get("cmd.profilegate.desc");
    }

    @Override
    public void execute(String[] args, CliConfig config, ProviderRegistry registry, Messages messages) {
        if (args.length == 0) {
            printHelp(config.color(), messages);
            return;
        }

        // --- parse arguments ---
        String beforePath = null;
        String afterPath  = null;
        double threshold       = 10.0;
        int    thresholdSamples = 0;
        int    top             = 20;
        boolean json           = "json".equals(config.format());
        boolean annotateGithub = false;
        int    maxRegressions  = Integer.MAX_VALUE;
        boolean baselineOnly   = false;

        int positional = 0;
        for (String arg : args) {
            if (arg.equals("--help") || arg.equals("-h")) {
                printHelp(config.color(), messages);
                return;
            } else if (arg.startsWith("--before=")) {
                beforePath = arg.substring(9);
            } else if (arg.startsWith("--after=")) {
                afterPath = arg.substring(8);
            } else if (arg.startsWith("--threshold=")) {
                try { threshold = Double.parseDouble(arg.substring(12)); }
                catch (NumberFormatException ignored) {}
            } else if (arg.startsWith("--threshold-samples=")) {
                try { thresholdSamples = Integer.parseInt(arg.substring(20)); }
                catch (NumberFormatException ignored) {}
            } else if (arg.startsWith("--top=")) {
                try { top = Integer.parseInt(arg.substring(6)); }
                catch (NumberFormatException ignored) {}
            } else if (arg.equals("--format=json")) {
                json = true;
            } else if (arg.startsWith("--annotate=")) {
                annotateGithub = "github".equalsIgnoreCase(arg.substring(11));
            } else if (arg.startsWith("--max-regressions=")) {
                try { maxRegressions = Integer.parseInt(arg.substring(18)); }
                catch (NumberFormatException ignored) {}
            } else if (arg.equals("--baseline-only")) {
                baselineOnly = true;
            } else if (!arg.startsWith("--")) {
                if (positional == 0) beforePath = arg;
                else if (positional == 1) afterPath = arg;
                positional++;
            }
        }

        if (beforePath == null || afterPath == null) {
            System.err.println(messages.get("error.profilegate.paths.required"));
            throw new CommandExitException(2);
        }

        // --- load snapshots ---
        ProfileSnapshot before;
        ProfileSnapshot after;
        try {
            before = ProfileSnapshot.load(Path.of(beforePath));
        } catch (IOException e) {
            System.err.println(messages.get("error.profilegate.load", beforePath, e.getMessage()));
            throw new CommandExitException(2);
        }
        try {
            after = ProfileSnapshot.load(Path.of(afterPath));
        } catch (IOException e) {
            System.err.println(messages.get("error.profilegate.load", afterPath, e.getMessage()));
            throw new CommandExitException(2);
        }

        // --- compute diff ---
        List<ProfileSnapshot.DiffEntry> allDeltas = ProfileSnapshot.diff(before, after);

        // Categorize entries
        List<RegressionEntry> regressions  = new ArrayList<>();
        List<RegressionEntry> improvements = new ArrayList<>();
        List<ProfileSnapshot.DiffEntry> newMethods  = new ArrayList<>();
        List<ProfileSnapshot.DiffEntry> goneMethods = new ArrayList<>();

        for (ProfileSnapshot.DiffEntry de : allDeltas) {
            if ("new".equals(de.state())) {
                newMethods.add(de);
            } else if ("gone".equals(de.state())) {
                goneMethods.add(de);
            } else {
                // Compute percentage-point delta based on after's total samples
                double afterPct  = after.totalSamples() > 0
                        ? (de.afterSamples()  * 100.0) / after.totalSamples() : 0.0;
                double beforePct = after.totalSamples() > 0
                        ? (de.beforeSamples() * 100.0) / after.totalSamples() : 0.0;
                // Use before snapshot's total for before% (more accurate)
                double beforePctActual = before.totalSamples() > 0
                        ? (de.beforeSamples() * 100.0) / before.totalSamples() : 0.0;

                double deltaPp = afterPct - beforePctActual;
                if (de.deltaSamples() > 0) {
                    regressions.add(new RegressionEntry(de, beforePctActual, afterPct, deltaPp));
                } else if (de.deltaSamples() < 0) {
                    double absDeltaPp = beforePctActual - afterPct;
                    improvements.add(new RegressionEntry(de, beforePctActual, afterPct, -absDeltaPp));
                }
            }
        }

        // Sort regressions by deltaPp descending
        regressions.sort((a2, b2) -> Double.compare(b2.deltaPp, a2.deltaPp));
        improvements.sort((a2, b2) -> Double.compare(a2.deltaPp, b2.deltaPp)); // most improved first

        // Apply threshold filtering for gate decision
        int threshSamples = thresholdSamples;
        double thresh = threshold;
        List<RegressionEntry> failing = regressions.stream()
                .filter(r -> r.deltaPp >= thresh
                        && Math.abs(r.de.deltaSamples()) >= threshSamples)
                .collect(Collectors.toList());

        // Pass iff no threshold violations; also fail when total (any) regressions > maxRegressions
        boolean gatePass = baselineOnly || failing.isEmpty();
        if (!baselineOnly && gatePass && maxRegressions != Integer.MAX_VALUE) {
            long anyRegressionCount = regressions.stream()
                    .filter(r -> Math.abs(r.de.deltaSamples()) >= threshSamples)
                    .count();
            if (anyRegressionCount > maxRegressions) gatePass = false;
        }

        // --- output ---
        if (json) {
            printJson(beforePath, afterPath, before, after, regressions, improvements,
                    newMethods, goneMethods, failing, gatePass, threshold, top, threshSamples);
        } else {
            printText(before, after, beforePath, afterPath, regressions, improvements,
                    failing, gatePass, threshold, top, config.color(), messages, threshSamples);
        }

        // GitHub annotations
        if (annotateGithub) {
            emitAnnotations(failing, regressions, threshold, threshSamples);
        }

        if (!gatePass) throw new CommandExitException(1);
    }

    // -------------------------------------------------------------------------
    // Text rendering
    // -------------------------------------------------------------------------

    private static void printText(ProfileSnapshot before, ProfileSnapshot after,
                                  String beforePath, String afterPath,
                                  List<RegressionEntry> regressions,
                                  List<RegressionEntry> improvements,
                                  List<RegressionEntry> failing,
                                  boolean gatePass, double threshold,
                                  int top, boolean useColor,
                                  Messages messages, int threshSamples) {
        String bold  = AnsiStyle.style(useColor, AnsiStyle.BOLD);
        String dim   = AnsiStyle.style(useColor, AnsiStyle.DIM);
        String reset = AnsiStyle.style(useColor, AnsiStyle.RESET);
        String red   = AnsiStyle.style(useColor, AnsiStyle.RED);
        String green = AnsiStyle.style(useColor, AnsiStyle.GREEN);
        String cyan  = AnsiStyle.style(useColor, AnsiStyle.CYAN);

        // Header
        System.out.println(RichRenderer.boxHeader(useColor, messages.get("header.profilegate"), WIDTH,
                "before", "after"));
        System.out.println(RichRenderer.boxLine("  before: " + beforePath, WIDTH));
        System.out.println(RichRenderer.boxLine("  after:  " + afterPath,  WIDTH));
        System.out.println(RichRenderer.boxLine("  threshold: " + threshold + "pp"
                + (threshSamples > 0 ? " + " + threshSamples + " samples min" : ""), WIDTH));
        System.out.println(RichRenderer.emptyLine(WIDTH));

        // Column headers
        String colHeader = bold
                + "  " + RichRenderer.padRight("#", 4)
                + RichRenderer.padRight(messages.get("label.method"), 44)
                + RichRenderer.padLeft("before%", 9)
                + "   " + RichRenderer.padLeft("after%", 9)
                + "   " + RichRenderer.padLeft("Δ samples", 10)
                + "   " + RichRenderer.padLeft("Δ %", 7)
                + reset;
        System.out.println(RichRenderer.boxLine(colHeader, WIDTH));

        String sep = dim + "  " + "─".repeat(4) + "  " + "─".repeat(42)
                + "  " + "─".repeat(9) + "   " + "─".repeat(9)
                + "   " + "─".repeat(10) + "   " + "─".repeat(8) + reset;
        System.out.println(RichRenderer.boxLine(sep, WIDTH));

        int limit = Math.min(top, regressions.size());
        for (int i = 0; i < limit; i++) {
            RegressionEntry r = regressions.get(i);
            boolean isFailing = r.deltaPp >= threshold
                    && Math.abs(r.de.deltaSamples()) >= threshSamples;
            String rowColor = isFailing ? red : "";
            String sign = r.de.deltaSamples() >= 0 ? "+" : "";
            String dpSign = r.deltaPp >= 0 ? "+" : "";
            String line = "  "
                    + RichRenderer.padRight(String.valueOf(i + 1), 4)
                    + cyan + RichRenderer.padRight(RichRenderer.truncate(r.de.method(), 44), 44) + reset
                    + RichRenderer.padLeft(String.format("%.1f%%", r.beforePct), 9)
                    + "   " + rowColor + RichRenderer.padLeft(String.format("%.1f%%", r.afterPct), 9) + reset
                    + "   " + rowColor + RichRenderer.padLeft(sign + formatCommas(r.de.deltaSamples()), 10) + reset
                    + "   " + rowColor + RichRenderer.padLeft(String.format("%s%.1f%%", dpSign, r.deltaPp), 7) + reset;
            System.out.println(RichRenderer.boxLine(line, WIDTH));
        }

        System.out.println(RichRenderer.emptyLine(WIDTH));

        // Footer gate verdict
        String gateLabel = gatePass
                ? green + "PASS" + reset
                : red   + "FAIL" + reset;
        String footer = String.format("%s regressions, %s improvements; gate: %s (threshold=%.1f%%)",
                regressions.size(), improvements.size(), gateLabel, threshold);
        System.out.println(RichRenderer.boxFooter(useColor, footer, WIDTH));
    }

    // -------------------------------------------------------------------------
    // JSON output
    // -------------------------------------------------------------------------

    private static void printJson(String beforePath, String afterPath,
                                  ProfileSnapshot before, ProfileSnapshot after,
                                  List<RegressionEntry> regressions,
                                  List<RegressionEntry> improvements,
                                  List<ProfileSnapshot.DiffEntry> newMethods,
                                  List<ProfileSnapshot.DiffEntry> goneMethods,
                                  List<RegressionEntry> failing,
                                  boolean gatePass, double threshold,
                                  int top, int threshSamples) {
        StringBuilder sb = new StringBuilder();
        sb.append('{');
        sb.append("\"status\":\"").append(gatePass ? "pass" : "fail").append('"');
        sb.append(",\"threshold\":").append(threshold);
        sb.append(",\"before\":\"").append(ProfileSnapshot.escape(beforePath)).append('"');
        sb.append(",\"after\":\"").append(ProfileSnapshot.escape(afterPath)).append('"');

        // regressions array
        sb.append(",\"regressions\":[");
        int regLimit = Math.min(top, regressions.size());
        for (int i = 0; i < regLimit; i++) {
            RegressionEntry r = regressions.get(i);
            if (i > 0) sb.append(',');
            sb.append("{\"method\":\"").append(ProfileSnapshot.escape(r.de.method())).append('"')
              .append(",\"beforePct\":").append(String.format("%.2f", r.beforePct))
              .append(",\"afterPct\":").append(String.format("%.2f", r.afterPct))
              .append(",\"deltaPct\":").append(String.format("%.2f", r.deltaPp))
              .append(",\"deltaSamples\":").append(r.de.deltaSamples())
              .append('}');
        }
        sb.append(']');

        // improvements array
        sb.append(",\"improvements\":[");
        int impLimit = Math.min(top, improvements.size());
        for (int i = 0; i < impLimit; i++) {
            RegressionEntry r = improvements.get(i);
            if (i > 0) sb.append(',');
            sb.append("{\"method\":\"").append(ProfileSnapshot.escape(r.de.method())).append('"')
              .append(",\"beforePct\":").append(String.format("%.2f", r.beforePct))
              .append(",\"afterPct\":").append(String.format("%.2f", r.afterPct))
              .append(",\"deltaPct\":").append(String.format("%.2f", r.deltaPp))
              .append(",\"deltaSamples\":").append(r.de.deltaSamples())
              .append('}');
        }
        sb.append(']');

        // newMethods
        sb.append(",\"newMethods\":[");
        for (int i = 0; i < Math.min(top, newMethods.size()); i++) {
            ProfileSnapshot.DiffEntry de = newMethods.get(i);
            if (i > 0) sb.append(',');
            sb.append("{\"method\":\"").append(ProfileSnapshot.escape(de.method())).append('"')
              .append(",\"afterSamples\":").append(de.afterSamples())
              .append('}');
        }
        sb.append(']');

        // goneMethods
        sb.append(",\"goneMethods\":[");
        for (int i = 0; i < Math.min(top, goneMethods.size()); i++) {
            ProfileSnapshot.DiffEntry de = goneMethods.get(i);
            if (i > 0) sb.append(',');
            sb.append("{\"method\":\"").append(ProfileSnapshot.escape(de.method())).append('"')
              .append(",\"beforeSamples\":").append(de.beforeSamples())
              .append('}');
        }
        sb.append(']');

        // verdict
        sb.append(",\"verdict\":{");
        sb.append("\"failed\":").append(failing.size());
        if (!gatePass) {
            String reason = failing.size() + " regression(s) exceed threshold (" + threshold + "pp)";
            sb.append(",\"reason\":\"").append(ProfileSnapshot.escape(reason)).append('"');
        }
        sb.append('}');

        sb.append('}');
        System.out.println(sb);
    }

    // -------------------------------------------------------------------------
    // GitHub annotations
    // -------------------------------------------------------------------------

    private static void emitAnnotations(List<RegressionEntry> failing,
                                        List<RegressionEntry> regressions,
                                        double threshold,
                                        int threshSamples) {
        // Error annotations for failing regressions
        for (RegressionEntry r : failing) {
            String msg = String.format(
                    "Method %s increased from %.1f%% to %.1f%% (+%.1fpp, +%s samples)",
                    r.de.method(), r.beforePct, r.afterPct, r.deltaPp,
                    formatCommas(r.de.deltaSamples()));
            System.out.println("::error file=,line=,title=Profile Regression::" + msg);
        }

        // Warning annotations for sub-threshold significant changes (>= half threshold)
        double halfThresh = threshold / 2.0;
        for (RegressionEntry r : regressions) {
            boolean isFailing = r.deltaPp >= threshold && Math.abs(r.de.deltaSamples()) >= threshSamples;
            if (!isFailing && r.deltaPp >= halfThresh) {
                String msg = String.format(
                        "Method %s increased from %.1f%% to %.1f%% (+%.1fpp, +%s samples)",
                        r.de.method(), r.beforePct, r.afterPct, r.deltaPp,
                        formatCommas(r.de.deltaSamples()));
                System.out.println("::warning file=,line=,title=Profile Regression::" + msg);
            }
        }
    }

    // -------------------------------------------------------------------------
    // Help
    // -------------------------------------------------------------------------

    private static void printHelp(boolean useColor, Messages messages) {
        System.out.print(RichRenderer.brandedHeader(useColor, "profile-gate",
                messages.get("cmd.profilegate.desc")));
        System.out.println(RichRenderer.boxHeader(useColor, "Usage", WIDTH));
        System.out.println(RichRenderer.boxLine("  argus profile-gate <before.json> <after.json> [options]", WIDTH));
        System.out.println(RichRenderer.boxLine("  argus profile-gate --before=PATH --after=PATH [options]", WIDTH));
        System.out.println(RichRenderer.emptyLine(WIDTH));
        System.out.println(RichRenderer.boxLine(
                AnsiStyle.style(useColor, AnsiStyle.BOLD) + "Options:"
                + AnsiStyle.style(useColor, AnsiStyle.RESET), WIDTH));
        System.out.println(RichRenderer.boxLine(
                RichRenderer.padRight("  --threshold=PCT", 38) + "Fail if any method jumps >=PCT pp (default: 10)", WIDTH));
        System.out.println(RichRenderer.boxLine(
                RichRenderer.padRight("  --threshold-samples=N", 38) + "Ignore changes with sample delta < N (default: 0)", WIDTH));
        System.out.println(RichRenderer.boxLine(
                RichRenderer.padRight("  --top=N", 38) + "Show top N regressions (default: 20)", WIDTH));
        System.out.println(RichRenderer.boxLine(
                RichRenderer.padRight("  --format=json", 38) + "Machine-readable JSON output", WIDTH));
        System.out.println(RichRenderer.boxLine(
                RichRenderer.padRight("  --annotate=github", 38) + "Emit ::error:: / ::warning:: annotations", WIDTH));
        System.out.println(RichRenderer.boxLine(
                RichRenderer.padRight("  --max-regressions=N", 38) + "Fail if more than N methods regress (any amount)", WIDTH));
        System.out.println(RichRenderer.boxLine(
                RichRenderer.padRight("  --baseline-only", 38) + "Print report but always exit 0", WIDTH));
        System.out.println(RichRenderer.emptyLine(WIDTH));
        System.out.println(RichRenderer.boxLine(
                AnsiStyle.style(useColor, AnsiStyle.BOLD) + "Exit codes:"
                + AnsiStyle.style(useColor, AnsiStyle.RESET), WIDTH));
        System.out.println(RichRenderer.boxLine("  0 = no regressions over threshold", WIDTH));
        System.out.println(RichRenderer.boxLine("  1 = at least one method exceeds threshold", WIDTH));
        System.out.println(RichRenderer.boxLine("  2 = usage / IO error", WIDTH));
        System.out.println(RichRenderer.boxFooter(useColor, null, WIDTH));
    }

    // -------------------------------------------------------------------------
    // Internal record
    // -------------------------------------------------------------------------

    private static final class RegressionEntry {
        final ProfileSnapshot.DiffEntry de;
        final double beforePct;
        final double afterPct;
        final double deltaPp;
        RegressionEntry(ProfileSnapshot.DiffEntry de, double beforePct, double afterPct, double deltaPp) {
            this.de = de;
            this.beforePct = beforePct;
            this.afterPct = afterPct;
            this.deltaPp = deltaPp;
        }
    }

    // -------------------------------------------------------------------------
    // Utilities
    // -------------------------------------------------------------------------

    private static String formatCommas(long n) {
        String s = String.valueOf(Math.abs(n));
        if (s.length() <= 3) return (n < 0 ? "-" : "") + s;
        StringBuilder sb = new StringBuilder();
        int rem = s.length() % 3;
        if (rem > 0) sb.append(s, 0, rem);
        for (int i = rem; i < s.length(); i += 3) {
            if (sb.length() > 0) sb.append(',');
            sb.append(s, i, i + 3);
        }
        return (n < 0 ? "-" : "") + sb;
    }
}
