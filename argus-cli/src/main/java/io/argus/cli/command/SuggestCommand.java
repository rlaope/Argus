package io.argus.cli.command;

import io.argus.cli.config.CliConfig;
import io.argus.cli.config.Messages;
import io.argus.cli.doctor.JvmSnapshot;
import io.argus.cli.doctor.JvmSnapshotCollector;
import io.argus.cli.provider.ProviderRegistry;
import io.argus.cli.render.AnsiStyle;
import io.argus.cli.render.RichRenderer;
import io.argus.core.command.CommandGroup;

import java.util.ArrayList;
import java.util.List;

/**
 * Analyzes current JVM configuration and workload, then suggests optimal JVM flags.
 * Supports workload profiles: web, batch, microservice, streaming.
 *
 * <p>Usage:
 * <pre>
 * argus suggest                        # auto-detect workload
 * argus suggest --profile=web          # optimize for web server
 * argus suggest --profile=batch        # optimize for batch processing
 * argus suggest --compare              # compare current vs recommended
 * </pre>
 */
public final class SuggestCommand implements Command {

    private static final int WIDTH = RichRenderer.DEFAULT_WIDTH;

    @Override public String name() { return "suggest"; }
    @Override public CommandGroup group() { return CommandGroup.PROFILING; }

    @Override
    public String description(Messages messages) {
        return messages.get("cmd.suggest.desc");
    }

    @Override
    public void execute(String[] args, CliConfig config, ProviderRegistry registry, Messages messages) {
        boolean json = "json".equals(config.format());
        boolean useColor = config.color();
        String profile = null;
        long pid = 0;

        for (int i = 0; i < args.length; i++) {
            if (args[i].startsWith("--profile=")) profile = args[i].substring(10);
            else if (args[i].equals("--format=json")) json = true;
            else if (!args[i].startsWith("--")) {
                try { pid = Long.parseLong(args[i]); } catch (NumberFormatException ignored) {}
            }
        }

        JvmSnapshot s = JvmSnapshotCollector.collect(pid);

        // Auto-detect profile if not specified
        if (profile == null) {
            profile = detectProfile(s);
        }

        List<Suggestion> suggestions = generateSuggestions(s, profile);

        if (json) {
            printJson(suggestions, profile);
            return;
        }

        printRich(suggestions, s, profile, useColor);
    }

    private String detectProfile(JvmSnapshot s) {
        // Heuristics for workload detection
        if (s.threadCount() > 200) return "web";
        if (s.heapMax() > 4L * 1024 * 1024 * 1024) return "batch";
        if (s.heapMax() < 512L * 1024 * 1024) return "microservice";
        return "web"; // default
    }

    private List<Suggestion> generateSuggestions(JvmSnapshot s, String profile) {
        List<Suggestion> suggestions = new ArrayList<>();

        String gc = s.gcAlgorithm().toLowerCase();
        long heapMB = s.heapMax() / (1024 * 1024);

        // GC algorithm recommendation
        switch (profile) {
            case "web" -> {
                if (!gc.contains("g1") && !gc.contains("zgc")) {
                    suggestions.add(new Suggestion("GC Algorithm",
                            "Switch to G1GC for balanced latency/throughput",
                            "-XX:+UseG1GC", "Current: " + s.gcAlgorithm()));
                }
                suggestions.add(new Suggestion("GC Pause Target",
                        "Set max pause target for web workloads",
                        "-XX:MaxGCPauseMillis=200", "Balances latency vs throughput"));
                if (heapMB >= 8192 && !gc.contains("zgc")) {
                    suggestions.add(new Suggestion("Consider ZGC",
                            "For heaps > 8GB, ZGC provides sub-ms pauses",
                            "-XX:+UseZGC", "Requires Java 17+"));
                }
            }
            case "batch" -> {
                if (!gc.contains("parallel")) {
                    suggestions.add(new Suggestion("GC Algorithm",
                            "Parallel GC maximizes throughput for batch processing",
                            "-XX:+UseParallelGC", "Current: " + s.gcAlgorithm()));
                }
                suggestions.add(new Suggestion("GC Threads",
                        "Match GC threads to available processors",
                        "-XX:ParallelGCThreads=" + s.availableProcessors(),
                        s.availableProcessors() + " processors available"));
            }
            case "microservice" -> {
                if (heapMB < 256) {
                    suggestions.add(new Suggestion("GC Algorithm",
                            "Serial GC is efficient for small heaps (<256MB)",
                            "-XX:+UseSerialGC", "Lowest footprint"));
                }
                suggestions.add(new Suggestion("Class Data Sharing",
                        "Enable CDS for faster startup",
                        "-XX:+UseAppCDS", "Reduces startup time by 20-30%"));
                suggestions.add(new Suggestion("Compact Strings",
                        "Reduce memory footprint with compact strings",
                        "-XX:+CompactStrings", "Default since Java 9"));
            }
            case "streaming" -> {
                suggestions.add(new Suggestion("TLAB Sizing",
                        "Optimize TLAB for steady allocation rate",
                        "-XX:+ResizeTLAB", "Adapts to allocation patterns"));
                if (!gc.contains("g1")) {
                    suggestions.add(new Suggestion("GC Algorithm",
                            "G1GC handles mixed workloads well for streaming",
                            "-XX:+UseG1GC", ""));
                }
            }
        }

        // Universal recommendations based on current state
        double heapPct = s.heapUsagePercent();
        if (heapPct > 75) {
            long suggestedMB = (long) (heapMB * 1.5);
            suggestions.add(new Suggestion("Heap Size",
                    String.format("Heap usage %.0f%% — increase max heap", heapPct),
                    "-Xmx" + (suggestedMB >= 1024 ? suggestedMB / 1024 + "g" : suggestedMB + "m"),
                    "Current: " + heapMB + "MB used " + String.format("%.0f%%", heapPct)));
        }

        if (s.gcOverheadPercent() > 5) {
            suggestions.add(new Suggestion("GC Overhead",
                    String.format("GC overhead %.1f%% — tune GC or increase heap", s.gcOverheadPercent()),
                    "-XX:GCTimeRatio=19", "Target: 95% throughput (5% GC time)"));
        }

        // Metaspace
        for (var pool : s.memoryPools().values()) {
            if (pool.name().toLowerCase().contains("metaspace") && pool.max() <= 0) {
                suggestions.add(new Suggestion("Metaspace Limit",
                        "No metaspace limit set — risk of unbounded growth",
                        "-XX:MaxMetaspaceSize=512m", "Prevents runaway class loading"));
            }
        }

        return suggestions;
    }

    private void printRich(List<Suggestion> suggestions, JvmSnapshot s, String profile, boolean c) {
        System.out.print(RichRenderer.brandedHeader(c, "suggest",
                "JVM flag optimization based on workload analysis"));
        System.out.println(RichRenderer.boxHeader(c, "JVM Optimization", WIDTH,
                "profile:" + profile, "heap:" + (s.heapMax() / (1024 * 1024)) + "MB",
                "gc:" + s.gcAlgorithm()));
        System.out.println(RichRenderer.emptyLine(WIDTH));

        System.out.println(RichRenderer.boxLine(
                "  " + AnsiStyle.style(c, AnsiStyle.BOLD) + "Detected profile: "
                        + AnsiStyle.style(c, AnsiStyle.CYAN) + profile.toUpperCase()
                        + AnsiStyle.style(c, AnsiStyle.RESET), WIDTH));
        System.out.println(RichRenderer.emptyLine(WIDTH));

        for (int i = 0; i < suggestions.size(); i++) {
            Suggestion sg = suggestions.get(i);
            System.out.println(RichRenderer.boxLine(
                    "  " + AnsiStyle.style(c, AnsiStyle.BOLD) + (i + 1) + ". " + sg.area
                            + AnsiStyle.style(c, AnsiStyle.RESET), WIDTH));
            System.out.println(RichRenderer.boxLine("     " + sg.reason, WIDTH));
            System.out.println(RichRenderer.boxLine(
                    "     " + AnsiStyle.style(c, AnsiStyle.GREEN) + sg.flag
                            + AnsiStyle.style(c, AnsiStyle.RESET)
                            + (sg.note.isEmpty() ? "" : "  " + AnsiStyle.style(c, AnsiStyle.DIM)
                            + "(" + sg.note + ")" + AnsiStyle.style(c, AnsiStyle.RESET)), WIDTH));
            System.out.println(RichRenderer.emptyLine(WIDTH));
        }

        // Summary: all flags on one line for copy-paste
        System.out.println(RichRenderer.boxSeparator(WIDTH));
        System.out.println(RichRenderer.emptyLine(WIDTH));
        System.out.println(RichRenderer.boxLine(
                "  " + AnsiStyle.style(c, AnsiStyle.BOLD) + "Copy-paste flags:"
                        + AnsiStyle.style(c, AnsiStyle.RESET), WIDTH));
        StringBuilder allFlags = new StringBuilder("  ");
        for (Suggestion sg : suggestions) {
            allFlags.append(sg.flag).append(" ");
        }
        System.out.println(RichRenderer.boxLine(
                AnsiStyle.style(c, AnsiStyle.GREEN) + allFlags.toString().trim()
                        + AnsiStyle.style(c, AnsiStyle.RESET), WIDTH));

        System.out.println(RichRenderer.emptyLine(WIDTH));
        System.out.println(RichRenderer.boxFooter(c, suggestions.size() + " suggestions", WIDTH));
    }

    private static void printJson(List<Suggestion> suggestions, String profile) {
        StringBuilder sb = new StringBuilder();
        sb.append("{\"profile\":\"").append(profile).append('"');
        sb.append(",\"suggestions\":[");
        for (int i = 0; i < suggestions.size(); i++) {
            Suggestion sg = suggestions.get(i);
            if (i > 0) sb.append(',');
            sb.append("{\"area\":\"").append(RichRenderer.escapeJson(sg.area)).append('"');
            sb.append(",\"reason\":\"").append(RichRenderer.escapeJson(sg.reason)).append('"');
            sb.append(",\"flag\":\"").append(RichRenderer.escapeJson(sg.flag)).append("\"}");
        }
        sb.append("],\"flags\":[");
        for (int i = 0; i < suggestions.size(); i++) {
            if (i > 0) sb.append(',');
            sb.append('"').append(suggestions.get(i).flag).append('"');
        }
        sb.append("]}");
        System.out.println(sb);
    }

    private record Suggestion(String area, String reason, String flag, String note) {}
}
