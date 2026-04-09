package io.argus.cli.command;

import io.argus.cli.config.CliConfig;
import io.argus.cli.config.Messages;
import io.argus.cli.provider.ProviderRegistry;
import io.argus.cli.provider.jdk.JcmdExecutor;
import io.argus.cli.render.AnsiStyle;
import io.argus.cli.render.RichRenderer;
import io.argus.core.command.CommandGroup;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Displays JVM internal performance counters via jcmd PerfCounter.print.
 *
 * <p>Performance counters expose low-level JVM metrics not available through
 * standard MXBeans: GC invocation counts, compiler time, class loading stats,
 * internal timers, and more.
 *
 * <p>Usage:
 * <pre>
 * argus perfcounter 12345                    # all counters
 * argus perfcounter 12345 --filter gc        # only gc-related
 * argus perfcounter 12345 --filter sun.gc    # specific prefix
 * argus perfcounter 12345 --format=json      # JSON output
 * </pre>
 */
public final class PerfCounterCommand implements Command {

    private static final int WIDTH = RichRenderer.DEFAULT_WIDTH;

    @Override public String name() { return "perfcounter"; }
    @Override public CommandGroup group() { return CommandGroup.MONITORING; }

    @Override
    public String description(Messages messages) {
        return messages.get("cmd.perfcounter.desc");
    }

    @Override
    public void execute(String[] args, CliConfig config, ProviderRegistry registry, Messages messages) {
        if (args.length == 0) {
            System.err.println(messages.get("error.pid.required"));
            return;
        }

        long pid;
        try { pid = Long.parseLong(args[0]); }
        catch (NumberFormatException e) { System.err.println(messages.get("error.pid.invalid", args[0])); return; }

        String filter = null;
        boolean json = "json".equals(config.format());
        boolean useColor = config.color();

        for (int i = 1; i < args.length; i++) {
            String arg = args[i];
            if (arg.startsWith("--filter=")) filter = arg.substring(9).toLowerCase();
            else if (arg.equals("--filter") && i + 1 < args.length) filter = args[++i].toLowerCase();
            else if (arg.equals("--format=json")) json = true;
        }

        String output;
        try {
            output = JcmdExecutor.execute(pid, "PerfCounter.print");
        } catch (RuntimeException e) {
            System.err.println("Error: " + e.getMessage());
            return;
        }

        // Parse counters: "name=value" format
        Map<String, List<Counter>> grouped = parseAndGroup(output, filter);

        if (json) {
            printJson(grouped);
            return;
        }

        System.out.print(RichRenderer.brandedHeader(useColor, "perfcounter",
                "JVM internal performance counters"));
        System.out.println(RichRenderer.boxHeader(useColor, "Performance Counters", WIDTH,
                "pid:" + pid, filter != null ? "filter:" + filter : "all"));
        System.out.println(RichRenderer.emptyLine(WIDTH));

        if (grouped.isEmpty()) {
            System.out.println(RichRenderer.boxLine(
                    AnsiStyle.style(useColor, AnsiStyle.DIM) + "  No counters found"
                            + (filter != null ? " matching '" + filter + "'" : "")
                            + AnsiStyle.style(useColor, AnsiStyle.RESET), WIDTH));
        } else {
            int totalCount = grouped.values().stream().mapToInt(List::size).sum();
            System.out.println(RichRenderer.boxLine(
                    AnsiStyle.style(useColor, AnsiStyle.DIM) + "  " + totalCount + " counters"
                            + (filter != null ? " (filtered by '" + filter + "')" : "")
                            + AnsiStyle.style(useColor, AnsiStyle.RESET), WIDTH));
            System.out.println(RichRenderer.emptyLine(WIDTH));

            for (var entry : grouped.entrySet()) {
                // Category header
                System.out.println(RichRenderer.boxLine(
                        "  " + AnsiStyle.style(useColor, AnsiStyle.BOLD, AnsiStyle.CYAN)
                                + entry.getKey()
                                + AnsiStyle.style(useColor, AnsiStyle.RESET), WIDTH));

                for (Counter c : entry.getValue()) {
                    String nameShort = c.name.substring(c.name.indexOf('.', c.name.indexOf('.') + 1) + 1);
                    String valueFmt = formatValue(c.value);
                    System.out.println(RichRenderer.boxLine(
                            "    " + AnsiStyle.style(useColor, AnsiStyle.GREEN)
                                    + RichRenderer.padRight(nameShort, 40)
                                    + AnsiStyle.style(useColor, AnsiStyle.RESET)
                                    + " " + valueFmt, WIDTH));
                }
                System.out.println(RichRenderer.emptyLine(WIDTH));
            }
        }

        System.out.println(RichRenderer.boxFooter(useColor, null, WIDTH));
    }

    private Map<String, List<Counter>> parseAndGroup(String output, String filter) {
        Map<String, List<Counter>> grouped = new LinkedHashMap<>();
        for (String line : output.split("\n")) {
            String trimmed = line.trim();
            int eq = trimmed.indexOf('=');
            if (eq <= 0) continue;

            String name = trimmed.substring(0, eq).trim();
            String value = trimmed.substring(eq + 1).trim();
            // Strip quotes from string values
            if (value.startsWith("\"") && value.endsWith("\"")) {
                value = value.substring(1, value.length() - 1);
            }

            if (filter != null && !name.toLowerCase().contains(filter)) continue;

            // Group by first two segments: "sun.gc" from "sun.gc.collector.0.invocations"
            String category = extractCategory(name);
            grouped.computeIfAbsent(category, k -> new ArrayList<>()).add(new Counter(name, value));
        }
        return grouped;
    }

    private String extractCategory(String name) {
        int first = name.indexOf('.');
        if (first < 0) return name;
        int second = name.indexOf('.', first + 1);
        if (second < 0) return name;
        return name.substring(0, second);
    }

    private String formatValue(String value) {
        try {
            long num = Long.parseLong(value);
            if (num > 1_000_000_000) return String.format("%,d  (%s)", num, RichRenderer.formatBytes(num));
            if (num > 10_000) return String.format("%,d", num);
            return value;
        } catch (NumberFormatException e) {
            return value;
        }
    }

    private void printJson(Map<String, List<Counter>> grouped) {
        StringBuilder sb = new StringBuilder();
        sb.append('{');
        boolean firstGroup = true;
        for (var entry : grouped.entrySet()) {
            if (!firstGroup) sb.append(',');
            sb.append('"').append(RichRenderer.escapeJson(entry.getKey())).append("\":{");
            boolean firstCounter = true;
            for (Counter c : entry.getValue()) {
                if (!firstCounter) sb.append(',');
                sb.append('"').append(RichRenderer.escapeJson(c.name)).append("\":");
                try {
                    long num = Long.parseLong(c.value);
                    sb.append(num);
                } catch (NumberFormatException e) {
                    sb.append('"').append(RichRenderer.escapeJson(c.value)).append('"');
                }
                firstCounter = false;
            }
            sb.append('}');
            firstGroup = false;
        }
        sb.append('}');
        System.out.println(sb);
    }

    private record Counter(String name, String value) {}
}
