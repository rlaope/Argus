package io.argus.cli.command;

import io.argus.cli.config.CliConfig;
import io.argus.cli.config.Messages;
import io.argus.cli.gclog.GcEvent;
import io.argus.cli.gclog.GcLogParser;
import io.argus.cli.gcwhy.GcWhyAnalyzer;
import io.argus.cli.gcwhy.GcWhyResult;
import io.argus.cli.provider.ProviderRegistry;
import io.argus.cli.render.AnsiStyle;
import io.argus.cli.render.RichRenderer;
import io.argus.core.command.CommandGroup;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

/**
 * Narrates why the worst GC pause in a time window happened.
 *
 * <p>Usage: {@code argus gcwhy <gc-log-file> [--last=5m] [--format=json]}
 */
public final class GcWhyCommand implements Command {

    private static final int WIDTH = RichRenderer.DEFAULT_WIDTH;
    private static final double DEFAULT_WINDOW_SEC = 5 * 60.0;

    @Override public String name() { return "gcwhy"; }
    @Override public CommandGroup group() { return CommandGroup.MEMORY; }
    @Override public CommandMode mode() { return CommandMode.READ; }
    @Override public boolean supportsTui() { return false; }

    @Override
    public String description(Messages messages) {
        return messages.get("cmd.gcwhy.desc");
    }

    @Override
    public void execute(String[] args, CliConfig config, ProviderRegistry registry, Messages messages) {
        if (args.length == 0) {
            System.err.println("Usage: argus gcwhy <gc-log-file> [--last=5m] [--format=json]");
            return;
        }

        boolean json = "json".equals(config.format());
        boolean useColor = config.color();
        double windowSec = DEFAULT_WINDOW_SEC;

        for (int i = 1; i < args.length; i++) {
            if (args[i].equals("--format=json")) json = true;
            else if (args[i].startsWith("--last=")) {
                Double parsed = parseDuration(args[i].substring(7));
                if (parsed != null) windowSec = parsed;
            }
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

        GcWhyResult result = GcWhyAnalyzer.analyze(events, windowSec);
        if (result.cause().isEmpty() && result.pauseMs() == 0) {
            System.err.println("No qualifying pause event found in the last " + formatWindow(windowSec));
            return;
        }

        if (json) {
            printJson(result);
            return;
        }
        printReport(result, useColor, messages, windowSec);
    }

    // ── Rendering ───────────────────────────────────────────────────────────

    private static void printReport(GcWhyResult r, boolean useColor, Messages messages, double windowSec) {
        System.out.print(RichRenderer.brandedHeader(useColor, "gcwhy", messages.get("desc.gcwhy")));
        System.out.println(RichRenderer.boxHeader(useColor, messages.get("header.gcwhy"),
                WIDTH, "window:" + formatWindow(windowSec)));
        System.out.println(RichRenderer.emptyLine(WIDTH));

        String bold = AnsiStyle.style(useColor, AnsiStyle.BOLD);
        String reset = AnsiStyle.style(useColor, AnsiStyle.RESET);
        String dim = AnsiStyle.style(useColor, AnsiStyle.DIM);
        String red = AnsiStyle.style(useColor, AnsiStyle.RED);

        String head = "  " + bold + "Worst pause:" + reset + " "
                + red + String.format("%.0f ms", r.pauseMs()) + reset
                + " at " + dim + String.format("t=%.2fs", r.timestampSec()) + reset
                + "  (" + r.type() + " / " + r.cause() + ")";
        System.out.println(RichRenderer.boxLine(head, WIDTH));
        System.out.println(RichRenderer.emptyLine(WIDTH));

        System.out.println(RichRenderer.boxLine("  " + bold + "Why this happened:" + reset, WIDTH));
        for (String b : r.bullets()) {
            System.out.println(RichRenderer.boxLine("   • " + b, WIDTH));
        }

        System.out.println(RichRenderer.emptyLine(WIDTH));
        System.out.println(RichRenderer.boxLine("  " + bold + "Related counters:" + reset, WIDTH));
        for (Map.Entry<String, String> e : r.counters().entrySet()) {
            String line = String.format("   %-22s %s", e.getKey() + ":", e.getValue());
            System.out.println(RichRenderer.boxLine(line, WIDTH));
        }

        System.out.println(RichRenderer.boxFooter(useColor, null, WIDTH));
    }

    private static void printJson(GcWhyResult r) {
        StringBuilder sb = new StringBuilder();
        sb.append("{\"timestampSec\":").append(r.timestampSec())
                .append(",\"type\":\"").append(RichRenderer.escapeJson(r.type())).append('"')
                .append(",\"cause\":\"").append(RichRenderer.escapeJson(r.cause())).append('"')
                .append(",\"pauseMs\":").append(r.pauseMs())
                .append(",\"bullets\":[");
        for (int i = 0; i < r.bullets().size(); i++) {
            if (i > 0) sb.append(',');
            sb.append('"').append(RichRenderer.escapeJson(r.bullets().get(i))).append('"');
        }
        sb.append("],\"counters\":{");
        int i = 0;
        for (Map.Entry<String, String> e : r.counters().entrySet()) {
            if (i++ > 0) sb.append(',');
            sb.append('"').append(RichRenderer.escapeJson(e.getKey())).append("\":\"")
                    .append(RichRenderer.escapeJson(e.getValue())).append('"');
        }
        sb.append("}}");
        System.out.println(sb);
    }

    // ── Helpers ─────────────────────────────────────────────────────────────

    /** Accepts "5m", "30s", "2h", or a bare number (treated as seconds). Returns seconds, or null. */
    static Double parseDuration(String s) {
        if (s == null || s.isEmpty()) return null;
        try {
            char last = s.charAt(s.length() - 1);
            if (Character.isDigit(last)) return Double.parseDouble(s);
            double n = Double.parseDouble(s.substring(0, s.length() - 1));
            return switch (last) {
                case 's' -> n;
                case 'm' -> n * 60;
                case 'h' -> n * 3600;
                default -> null;
            };
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static String formatWindow(double sec) {
        if (sec >= 3600 && sec % 3600 == 0) return ((long) (sec / 3600)) + "h";
        if (sec >= 60 && sec % 60 == 0) return ((long) (sec / 60)) + "m";
        return ((long) sec) + "s";
    }
}
