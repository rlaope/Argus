package io.argus.aggregator.http;

import io.argus.aggregator.model.AlertEvent;
import io.argus.aggregator.model.PodTarget;
import io.argus.aggregator.model.TileMetrics;
import io.argus.aggregator.scrape.ScrapeLoop;
import io.argus.aggregator.store.FleetRegistry;

import java.util.List;

/**
 * Renders the aggregator's own {@code /metrics} endpoint in Prometheus text
 * exposition format. Per the API contract, exposes both aggregator self-metrics
 * (target count, scrape errors) and per-pod fleet data (heap, gc, cpu, etc).
 */
public final class PrometheusMetricsExporter {

    private final FleetRegistry registry;
    private final ScrapeLoop scrapeLoop;

    public PrometheusMetricsExporter(FleetRegistry registry, ScrapeLoop scrapeLoop) {
        this.registry = registry;
        this.scrapeLoop = scrapeLoop;
    }

    public String render() {
        StringBuilder sb = new StringBuilder(2048);
        List<PodTarget> targets = registry.listTargets();
        int up = 0;
        for (PodTarget t : targets) if (t.scrapeOk()) up++;

        line(sb, "argus_aggregator_targets_total", "gauge",
                "Total registered scrape targets",
                "argus_aggregator_targets_total", targets.size());
        line(sb, "argus_aggregator_targets_up", "gauge",
                "Targets with last scrape OK",
                "argus_aggregator_targets_up", up);
        line(sb, "argus_aggregator_scrape_errors_total", "counter",
                "Cumulative scrape failures",
                "argus_aggregator_scrape_errors_total", scrapeLoop.scrapeErrors());
        line(sb, "argus_aggregator_scrapes_total", "counter",
                "Cumulative scrape successes",
                "argus_aggregator_scrapes_total", scrapeLoop.scrapeCount());

        // Per-pod gauges
        sb.append("# HELP argus_fleet_heap_percent Latest heap used percent per pod\n");
        sb.append("# TYPE argus_fleet_heap_percent gauge\n");
        for (PodTarget t : targets) {
            TileMetrics m = registry.latestMetrics(t.podId());
            if (m == null || m.heapPercent() == null) continue;
            appendGauge(sb, "argus_fleet_heap_percent", t, m.heapPercent());
        }
        sb.append("# HELP argus_fleet_gc_overhead_percent Latest GC overhead percent per pod\n");
        sb.append("# TYPE argus_fleet_gc_overhead_percent gauge\n");
        for (PodTarget t : targets) {
            TileMetrics m = registry.latestMetrics(t.podId());
            if (m == null || m.gcOverheadPercent() == null) continue;
            appendGauge(sb, "argus_fleet_gc_overhead_percent", t, m.gcOverheadPercent());
        }
        sb.append("# HELP argus_fleet_cpu_percent Latest CPU percent per pod\n");
        sb.append("# TYPE argus_fleet_cpu_percent gauge\n");
        for (PodTarget t : targets) {
            TileMetrics m = registry.latestMetrics(t.podId());
            if (m == null || m.cpuPercent() == null) continue;
            appendGauge(sb, "argus_fleet_cpu_percent", t, m.cpuPercent());
        }
        sb.append("# HELP argus_fleet_active_vthreads Active virtual threads per pod\n");
        sb.append("# TYPE argus_fleet_active_vthreads gauge\n");
        for (PodTarget t : targets) {
            TileMetrics m = registry.latestMetrics(t.podId());
            if (m == null) continue;
            appendGauge(sb, "argus_fleet_active_vthreads", t, (double) m.activeVThreads());
        }
        sb.append("# HELP argus_fleet_leak_suspected 1.0 if leak suspected per pod, 0.0 otherwise\n");
        sb.append("# TYPE argus_fleet_leak_suspected gauge\n");
        for (PodTarget t : targets) {
            TileMetrics m = registry.latestMetrics(t.podId());
            if (m == null) continue;
            appendGauge(sb, "argus_fleet_leak_suspected", t, m.leakSuspected() ? 1.0 : 0.0);
        }
        sb.append("# HELP argus_fleet_alert_firing 1.0 if alert firing per pod/rule/severity\n");
        sb.append("# TYPE argus_fleet_alert_firing gauge\n");
        for (AlertEvent e : registry.activeAlerts()) {
            sb.append("argus_fleet_alert_firing{")
              .append("pod_id=\"").append(escapeLabel(e.podId())).append("\",")
              .append("rule_name=\"").append(escapeLabel(e.ruleName())).append("\",")
              .append("severity=\"").append(escapeLabel(e.severity())).append("\"} 1.0\n");
        }
        return sb.toString();
    }

    private static void line(StringBuilder sb, String name, String type, String help,
                             String metricName, long value) {
        sb.append("# HELP ").append(name).append(' ').append(help).append('\n');
        sb.append("# TYPE ").append(name).append(' ').append(type).append('\n');
        sb.append(metricName).append(' ').append(value).append('\n');
    }

    private static void appendGauge(StringBuilder sb, String metric, PodTarget t, double value) {
        sb.append(metric).append('{')
          .append("pod_id=\"").append(escapeLabel(t.podId())).append("\",")
          .append("namespace=\"").append(escapeLabel(t.namespace())).append("\",")
          .append("deployment=\"").append(escapeLabel(t.deployment())).append("\"} ")
          .append(value).append('\n');
    }

    private static String escapeLabel(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n");
    }
}
