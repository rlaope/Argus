package io.argus.cli.command;

import io.argus.cli.cluster.ClusterHealthAggregator;
import io.argus.cli.cluster.ClusterHealthAggregator.AggregateStats;
import io.argus.cli.cluster.ClusterHealthAggregator.InstanceMetrics;
import io.argus.cli.cluster.PrometheusTextParser;
import io.argus.cli.config.CliConfig;
import io.argus.cli.config.Messages;
import io.argus.cli.provider.ProviderRegistry;
import io.argus.cli.render.AnsiStyle;
import io.argus.cli.render.RichRenderer;
import io.argus.core.command.CommandGroup;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;

/**
 * Discovers multiple Argus-enabled JVM instances and shows aggregated health metrics.
 *
 * <p>Usage:
 * <pre>
 *   argus cluster scan localhost:9202 localhost:9203
 *   argus cluster scan --file=targets.txt
 *   argus cluster health localhost:9202 localhost:9203
 * </pre>
 */
public final class ClusterCommand implements Command {

    private static final int WIDTH = RichRenderer.DEFAULT_WIDTH;

    @Override
    public String name() { return "cluster"; }

    @Override
    public CommandGroup group() { return CommandGroup.MONITORING; }

    @Override
    public CommandMode mode() { return CommandMode.READ; }

    @Override
    public boolean supportsTui() { return true; }

    @Override
    public String description(Messages messages) {
        return messages.get("cmd.cluster.desc");
    }

    @Override
    public void execute(String[] args, CliConfig config, ProviderRegistry registry, Messages messages) {
        if (args.length == 0) {
            printHelp();
            return;
        }

        String subCommand = args[0];
        if (!subCommand.equals("scan") && !subCommand.equals("health")) {
            System.err.println("Unknown subcommand: " + subCommand);
            printHelp();
            return;
        }

        boolean json = "json".equals(config.format());
        boolean useColor = config.color();
        String file = null;
        List<String> targets = new ArrayList<>();

        for (int i = 1; i < args.length; i++) {
            String arg = args[i];
            if (arg.startsWith("--file=")) {
                file = arg.substring(7);
            } else if (arg.equals("--format=json")) {
                json = true;
            } else if (!arg.startsWith("--")) {
                targets.add(arg);
            }
        }

        if (file != null) {
            targets.addAll(readTargetsFile(file));
        }

        if (targets.isEmpty()) {
            System.err.println(messages.get("error.cluster.no.targets"));
            return;
        }

        List<InstanceMetrics> results = fetchAll(targets);
        AggregateStats stats = ClusterHealthAggregator.aggregate(results);

        if (json) {
            printJson(results, stats);
        } else {
            printTable(useColor, results, stats, messages);
        }
    }

    // ── Fetching ────────────────────────────────────────────────────────────

    private List<InstanceMetrics> fetchAll(List<String> targets) {
        HttpClient client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(3))
                .build();

        List<CompletableFuture<InstanceMetrics>> futures = targets.stream()
                .map(target -> fetchOne(client, target))
                .toList();

        CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join();

        List<InstanceMetrics> results = new ArrayList<>();
        for (CompletableFuture<InstanceMetrics> f : futures) {
            try {
                results.add(f.get());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } catch (ExecutionException e) {
                // Should not happen — fetchOne catches all exceptions internally
            }
        }
        return results;
    }

    private CompletableFuture<InstanceMetrics> fetchOne(HttpClient client, String target) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                HttpRequest request = HttpRequest.newBuilder()
                        .uri(URI.create("http://" + target + "/prometheus"))
                        .timeout(Duration.ofSeconds(5))
                        .GET()
                        .build();
                HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
                if (response.statusCode() == 200) {
                    Map<String, Double> metrics = PrometheusTextParser.parse(response.body());
                    return ClusterHealthAggregator.extract(target, metrics);
                }
                // Non-200 response: treat as unreachable
                return downInstance(target);
            } catch (Exception e) {
                return downInstance(target);
            }
        });
    }

    private static InstanceMetrics downInstance(String target) {
        return new InstanceMetrics(target, -1, -1, -1, false, 0, false);
    }

    // ── Output: table ───────────────────────────────────────────────────────

    private void printTable(boolean useColor, List<InstanceMetrics> instances,
                            AggregateStats stats, Messages messages) {
        String title = messages.get("header.cluster");
        System.out.println(RichRenderer.boxHeader(useColor, title, WIDTH,
                instances.size() + " instances"));
        System.out.println(RichRenderer.emptyLine(WIDTH));

        // Column header
        String hdr = String.format("  %-20s  %-6s  %-6s  %-6s  %-7s  %-10s  %-14s",
                "Instance", "Heap%", "GC OH", "CPU", "Leak?", "VThreads", "Status");
        System.out.println(RichRenderer.boxLine(
                AnsiStyle.style(useColor, AnsiStyle.BOLD) + hdr
                        + AnsiStyle.style(useColor, AnsiStyle.RESET), WIDTH));
        System.out.println(RichRenderer.emptyLine(WIDTH));

        for (InstanceMetrics m : instances) {
            System.out.println(RichRenderer.boxLine(formatInstanceRow(useColor, m), WIDTH));
        }

        System.out.println(RichRenderer.emptyLine(WIDTH));
        System.out.println(RichRenderer.boxSeparator(WIDTH));
        System.out.println(RichRenderer.emptyLine(WIDTH));

        // Aggregate row
        String aggHeap = formatRange(stats.heapMin(), stats.heapMax(), "%");
        String aggGc   = formatRange(stats.gcMin(),   stats.gcMax(),   "%");
        String aggCpu  = formatRange(stats.cpuMin(),  stats.cpuMax(),  "%");
        String aggLeak = stats.leakCount() + "/" + instances.size();
        String aggVt   = stats.vtTotal() > 0 ? String.format("%,d total", stats.vtTotal()) : "N/A";

        String aggRow = String.format("  %-20s  %-6s  %-6s  %-6s  %-7s  %-24s",
                "Aggregate", aggHeap, aggGc, aggCpu, aggLeak, aggVt);
        System.out.println(RichRenderer.boxLine(
                AnsiStyle.style(useColor, AnsiStyle.BOLD) + aggRow
                        + AnsiStyle.style(useColor, AnsiStyle.RESET), WIDTH));

        if (stats.worstTarget() != null && !stats.worstReason().isEmpty()) {
            String worstMsg = "  Worst: " + stats.worstTarget() + " \u2014 " + stats.worstReason();
            System.out.println(RichRenderer.boxLine(
                    AnsiStyle.style(useColor, AnsiStyle.YELLOW) + worstMsg
                            + AnsiStyle.style(useColor, AnsiStyle.RESET), WIDTH));
        }

        System.out.println(RichRenderer.boxFooter(useColor, null, WIDTH));
    }

    private String formatInstanceRow(boolean useColor, InstanceMetrics m) {
        if (!m.reachable()) {
            return String.format("  %-20s  %-6s  %-6s  %-6s  %-7s  %-10s  %s",
                    truncate(m.target(), 20), "N/A", "N/A", "N/A", "N/A", "N/A",
                    AnsiStyle.style(useColor, AnsiStyle.RED) + "\u2717 DOWN"
                            + AnsiStyle.style(useColor, AnsiStyle.RESET));
        }

        String heap = m.heapPercent() >= 0 ? String.format("%.0f%%", m.heapPercent()) : "N/A";
        String gc   = m.gcOverhead()  >= 0 ? String.format("%.1f%%", m.gcOverhead())  : "N/A";
        String cpu  = m.cpuPercent()  >= 0 ? String.format("%.0f%%", m.cpuPercent())  : "N/A";
        String leak = m.leakSuspected()
                ? AnsiStyle.style(useColor, AnsiStyle.YELLOW) + "\u26a0 Yes" + AnsiStyle.style(useColor, AnsiStyle.RESET)
                : "No";
        String vt   = m.activeVThreads() > 0 ? String.format("%,d", m.activeVThreads()) : "0";

        boolean warn = (m.heapPercent() >= 80) || (m.gcOverhead() >= 5) || m.leakSuspected();
        String status = warn
                ? AnsiStyle.style(useColor, AnsiStyle.YELLOW) + "\u26a0 Warning" + AnsiStyle.style(useColor, AnsiStyle.RESET)
                : AnsiStyle.style(useColor, AnsiStyle.GREEN)  + "\u2713 Healthy" + AnsiStyle.style(useColor, AnsiStyle.RESET);

        return String.format("  %-20s  %-6s  %-6s  %-6s  %-7s  %-10s  %s",
                truncate(m.target(), 20), heap, gc, cpu, leak, vt, status);
    }

    private static String formatRange(double min, double max, String suffix) {
        if (min < 0) return "N/A";
        if (Math.abs(max - min) < 0.05) return String.format("%.0f%s", min, suffix);
        return String.format("%.0f-%.0f%s", min, max, suffix);
    }

    private static String truncate(String s, int max) {
        return s.length() <= max ? s : s.substring(0, max - 1) + "\u2026";
    }

    // ── Output: JSON ────────────────────────────────────────────────────────

    private void printJson(List<InstanceMetrics> instances, AggregateStats stats) {
        StringBuilder sb = new StringBuilder();
        sb.append("{\"instances\":[");
        for (int i = 0; i < instances.size(); i++) {
            if (i > 0) sb.append(',');
            InstanceMetrics m = instances.get(i);
            sb.append("{\"target\":\"").append(m.target()).append('"')
              .append(",\"reachable\":").append(m.reachable())
              .append(",\"heapPercent\":").append(m.heapPercent())
              .append(",\"gcOverhead\":").append(m.gcOverhead())
              .append(",\"cpuPercent\":").append(m.cpuPercent())
              .append(",\"leakSuspected\":").append(m.leakSuspected())
              .append(",\"activeVThreads\":").append(m.activeVThreads())
              .append('}');
        }
        sb.append("],\"aggregate\":{")
          .append("\"heapMin\":").append(stats.heapMin())
          .append(",\"heapMax\":").append(stats.heapMax())
          .append(",\"heapAvg\":").append(stats.heapAvg())
          .append(",\"gcMin\":").append(stats.gcMin())
          .append(",\"gcMax\":").append(stats.gcMax())
          .append(",\"gcAvg\":").append(stats.gcAvg())
          .append(",\"cpuMin\":").append(stats.cpuMin())
          .append(",\"cpuMax\":").append(stats.cpuMax())
          .append(",\"cpuAvg\":").append(stats.cpuAvg())
          .append(",\"vtTotal\":").append(stats.vtTotal())
          .append(",\"leakCount\":").append(stats.leakCount())
          .append(",\"worstTarget\":\"").append(stats.worstTarget() != null ? stats.worstTarget() : "").append('"')
          .append(",\"worstReason\":\"").append(stats.worstReason() != null ? stats.worstReason() : "").append('"')
          .append("}}");
        System.out.println(sb);
    }

    // ── Targets file ────────────────────────────────────────────────────────

    private static List<String> readTargetsFile(String filePath) {
        List<String> targets = new ArrayList<>();
        try {
            for (String line : Files.readAllLines(Path.of(filePath))) {
                String trimmed = line.trim();
                if (!trimmed.isEmpty() && !trimmed.startsWith("#")) {
                    targets.add(trimmed);
                }
            }
        } catch (IOException e) {
            System.err.println("Warning: could not read targets file '" + filePath + "': " + e.getMessage());
        }
        return targets;
    }

    private static void printHelp() {
        System.out.println("Usage: argus cluster <subcommand> [targets...] [options]");
        System.out.println();
        System.out.println("Subcommands:");
        System.out.println("  scan    Discover and display health of multiple JVM instances");
        System.out.println("  health  Show aggregated health metrics");
        System.out.println();
        System.out.println("Options:");
        System.out.println("  --file=FILE      Read host:port targets from file (one per line)");
        System.out.println("  --format=json    Output as JSON");
        System.out.println();
        System.out.println("Examples:");
        System.out.println("  argus cluster scan localhost:9202 localhost:9203 localhost:9204");
        System.out.println("  argus cluster scan --file=targets.txt");
        System.out.println("  argus cluster health localhost:9202 localhost:9203");
    }
}
