package io.argus.cli.command;

import io.argus.cli.config.CliConfig;
import io.argus.cli.config.Messages;
import io.argus.cli.gclog.GcEvent;
import io.argus.cli.gclog.GcLogAnalysis;
import io.argus.cli.gclog.GcLogAnalyzer;
import io.argus.cli.gclog.GcLogParser;
import io.argus.cli.gcscore.AxisScore;
import io.argus.cli.gcscore.GcScoreCalculator;
import io.argus.cli.gcscore.GcScoreResult;
import io.argus.cli.provider.ProviderRegistry;
import io.argus.cli.render.AnsiStyle;
import io.argus.cli.render.RichRenderer;
import io.argus.core.command.CommandGroup;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * Produces a one-page GC Health Score Card from a GC log file.
 *
 * <p>Usage: {@code argus gcscore <gc-log-file> [--format=json]}
 */
public final class GcScoreCommand implements Command {

    private static final int WIDTH = RichRenderer.DEFAULT_WIDTH;

    @Override public String name() { return "gcscore"; }
    @Override public CommandGroup group() { return CommandGroup.MEMORY; }
    @Override public CommandMode mode() { return CommandMode.READ; }
    @Override public boolean supportsTui() { return false; }

    @Override
    public String description(Messages messages) {
        return messages.get("cmd.gcscore.desc");
    }

    @Override
    public void execute(String[] args, CliConfig config, ProviderRegistry registry, Messages messages) {
        if (args.length == 0) {
            System.err.println("Usage: argus gcscore <gc-log-file> [--format=json]");
            return;
        }

        boolean json = "json".equals(config.format());
        boolean useColor = config.color();
        for (int i = 1; i < args.length; i++) {
            if (args[i].equals("--format=json")) json = true;
        }

        Path logFile = Path.of(args[0]);
        if (!Files.exists(logFile)) {
            System.err.println("File not found: " + logFile);
            return;
        }

        List<GcEvent> events;
        try {
            events = GcLogParser.parse(logFile);
        } catch (IOException e) {
            System.err.println("Failed to read " + logFile + ": " + e.getMessage());
            return;
        }
        if (events.isEmpty()) {
            System.err.println("No GC events parsed from " + logFile);
            return;
        }

        GcLogAnalysis analysis = GcLogAnalyzer.analyze(events);
        GcScoreResult result = GcScoreCalculator.compute(analysis);

        if (json) {
            printJson(result);
            return;
        }
        printCard(result, useColor, messages, logFile);
    }

    // ── Rendering ───────────────────────────────────────────────────────────

    private static void printCard(GcScoreResult r, boolean useColor, Messages messages, Path logFile) {
        System.out.print(RichRenderer.brandedHeader(useColor, "gcscore", messages.get("desc.gcscore")));
        System.out.println(RichRenderer.boxHeader(useColor, messages.get("header.gcscore"),
                WIDTH, "source:" + logFile.getFileName()));
        System.out.println(RichRenderer.emptyLine(WIDTH));

        String bold = AnsiStyle.style(useColor, AnsiStyle.BOLD);
        String reset = AnsiStyle.style(useColor, AnsiStyle.RESET);
        String gradeColor = gradeColor(useColor, r.grade());

        String overallLine = "  " + bold + "Overall: " + reset
                + gradeColor + r.grade() + reset + "  "
                + "(" + r.summary() + ")  "
                + AnsiStyle.style(useColor, AnsiStyle.DIM) + "[" + r.overall() + "/100]" + reset;
        System.out.println(RichRenderer.boxLine(overallLine, WIDTH));
        System.out.println(RichRenderer.emptyLine(WIDTH));

        for (AxisScore ax : r.axes()) {
            System.out.println(RichRenderer.boxLine(renderAxis(ax, useColor), WIDTH));
        }

        System.out.println(RichRenderer.emptyLine(WIDTH));
        System.out.println(RichRenderer.boxLine("  " + bold + "Hints:" + reset, WIDTH));
        for (String hint : r.hints()) {
            System.out.println(RichRenderer.boxLine("   • " + hint, WIDTH));
        }

        System.out.println(RichRenderer.boxFooter(useColor, null, WIDTH));
    }

    private static String renderAxis(AxisScore ax, boolean useColor) {
        String mark;
        String color;
        switch (ax.verdict()) {
            case PASS -> { mark = "\u2713 Pass "; color = AnsiStyle.GREEN; }
            case WARN -> { mark = "\u26A0 Warn "; color = AnsiStyle.YELLOW; }
            case FAIL -> { mark = "\u2717 Fail "; color = AnsiStyle.RED; }
            default   -> { mark = "\u2013 N/A  "; color = AnsiStyle.DIM;    }
        }
        String coloredMark = AnsiStyle.style(useColor, color) + mark + AnsiStyle.style(useColor, AnsiStyle.RESET);
        String valueStr = ax.available() ? formatValue(ax) : "-";
        String dim = AnsiStyle.style(useColor, AnsiStyle.DIM);
        String reset = AnsiStyle.style(useColor, AnsiStyle.RESET);
        return String.format("  %-20s %s %10s   %s(target %s)%s",
                ax.name(), coloredMark, valueStr, dim, ax.target(), reset);
    }

    private static String formatValue(AxisScore ax) {
        return switch (ax.name()) {
            case "Pause p99", "Pause tail (max)" -> String.format("%.0f %s", ax.value(), ax.unit());
            case "Throughput", "Promotion ratio" -> String.format("%.1f%s", ax.value(), ax.unit());
            case "Allocation rate" -> String.format("%.0f %s", ax.value(), ax.unit());
            case "Full GC frequency" -> String.format("%.1f %s", ax.value(), ax.unit());
            default -> String.format("%.2f %s", ax.value(), ax.unit());
        };
    }

    private static String gradeColor(boolean useColor, String grade) {
        String code = switch (grade) {
            case "A" -> AnsiStyle.GREEN;
            case "B" -> AnsiStyle.CYAN;
            case "C" -> AnsiStyle.YELLOW;
            case "D" -> AnsiStyle.YELLOW;
            default  -> AnsiStyle.RED;
        };
        return AnsiStyle.style(useColor, AnsiStyle.BOLD) + AnsiStyle.style(useColor, code);
    }

    private static void printJson(GcScoreResult r) {
        StringBuilder sb = new StringBuilder();
        sb.append("{\"overall\":").append(r.overall())
                .append(",\"grade\":\"").append(r.grade()).append('"')
                .append(",\"summary\":\"").append(RichRenderer.escapeJson(r.summary())).append('"')
                .append(",\"axes\":[");
        for (int i = 0; i < r.axes().size(); i++) {
            AxisScore ax = r.axes().get(i);
            if (i > 0) sb.append(',');
            sb.append('{')
                    .append("\"name\":\"").append(RichRenderer.escapeJson(ax.name())).append('"')
                    .append(",\"available\":").append(ax.available())
                    .append(",\"value\":").append(ax.value())
                    .append(",\"unit\":\"").append(ax.unit()).append('"')
                    .append(",\"target\":\"").append(RichRenderer.escapeJson(ax.target())).append('"')
                    .append(",\"score\":").append(ax.score())
                    .append(",\"verdict\":\"").append(ax.verdict().name()).append('"')
                    .append('}');
        }
        sb.append("],\"hints\":[");
        for (int i = 0; i < r.hints().size(); i++) {
            if (i > 0) sb.append(',');
            sb.append('"').append(RichRenderer.escapeJson(r.hints().get(i))).append('"');
        }
        sb.append("]}");
        System.out.println(sb);
    }
}
