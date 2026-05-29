package io.argus.aggregator.http;

import io.argus.aggregator.model.AlertEvent;
import io.argus.aggregator.model.FleetRightsize;
import io.argus.aggregator.model.FleetSummary;
import io.argus.aggregator.model.MetricSample;
import io.argus.aggregator.model.PodTarget;
import io.argus.aggregator.model.Tile;
import io.argus.aggregator.model.TileMetrics;

import java.time.Instant;
import java.util.List;

/**
 * Hand-built JSON serializer for aggregator wire types.
 *
 * <p>Keeps the aggregator zero-runtime-dependency on Jackson/Gson — matches
 * the project rule documented in {@code docs/architecture.md}: "Zero external
 * dependencies in core (except Netty). JSON: Hand-built with StringBuilder."
 *
 * <p>{@code null} fields are omitted from output (matches {@code @JsonInclude(NON_NULL)}
 * semantics in the API contract).
 */
public final class JsonWriter {

    private JsonWriter() {}

    public static String error(int code, String message) {
        StringBuilder sb = new StringBuilder(128);
        sb.append("{\"error\":{\"code\":").append(code)
          .append(",\"message\":\"").append(escape(message)).append("\"}}");
        return sb.toString();
    }

    public static String podTarget(PodTarget t) {
        StringBuilder sb = new StringBuilder(256);
        appendPodTarget(sb, t);
        return sb.toString();
    }

    public static String tileList(List<Tile> tiles, int totalCount, int filteredCount) {
        StringBuilder sb = new StringBuilder(1024);
        sb.append("{\"tiles\":[");
        for (int i = 0; i < tiles.size(); i++) {
            if (i > 0) sb.append(',');
            appendTile(sb, tiles.get(i));
        }
        sb.append("],\"totalCount\":").append(totalCount)
          .append(",\"filteredCount\":").append(filteredCount).append('}');
        return sb.toString();
    }

    public static String podDetail(Tile tile, List<AlertEvent> alerts,
                                   long windowSeconds, List<MetricSample> samples) {
        StringBuilder sb = new StringBuilder(2048);
        sb.append("{\"tile\":");
        appendTile(sb, tile);
        sb.append(",\"alerts\":[");
        for (int i = 0; i < alerts.size(); i++) {
            if (i > 0) sb.append(',');
            appendAlert(sb, alerts.get(i));
        }
        sb.append("],\"history\":{")
          .append("\"windowSeconds\":").append(windowSeconds)
          .append(",\"sampleCount\":").append(samples.size())
          .append(",\"samples\":[");
        for (int i = 0; i < samples.size(); i++) {
            if (i > 0) sb.append(',');
            appendSample(sb, samples.get(i));
        }
        sb.append("]}}");
        return sb.toString();
    }

    public static String summary(FleetSummary s) {
        StringBuilder sb = new StringBuilder(1024);
        sb.append("{\"summary\":{")
          .append("\"totalTargets\":").append(s.totalTargets())
          .append(",\"upTargets\":").append(s.upTargets())
          .append(",\"downTargets\":").append(s.downTargets())
          .append(",\"greenCount\":").append(s.greenCount())
          .append(",\"yellowCount\":").append(s.yellowCount())
          .append(",\"redCount\":").append(s.redCount())
          .append(",\"greyCount\":").append(s.greyCount())
          .append(",\"totalAlerts\":").append(s.totalAlerts())
          .append(",\"heap\":");
        appendMinMaxAvg(sb, s.heap());
        sb.append(",\"gc\":");
        appendMinMaxAvg(sb, s.gc());
        sb.append(",\"cpu\":");
        appendMinMaxAvg(sb, s.cpu());
        sb.append(",\"totalActiveVThreads\":").append(s.totalActiveVThreads())
          .append(",\"leakSuspectedCount\":").append(s.leakSuspectedCount());
        sb.append(",\"worstPodId\":");
        appendStringOrNull(sb, s.worstPodId());
        sb.append(",\"worstReason\":");
        appendStringOrNull(sb, s.worstReason());
        sb.append("}}");
        return sb.toString();
    }

    public static String rightsize(FleetRightsize r) {
        StringBuilder sb = new StringBuilder(1024);
        sb.append("{\"rightsize\":{")
          .append("\"safetyFactor\":");
        appendNumberOrNull(sb, r.safetyFactor());
        sb.append(",\"aggregateSavingsPercent\":");
        appendNumberOrNull(sb, r.aggregateSavingsPercent());
        sb.append(",\"deployments\":[");
        List<FleetRightsize.DeploymentRow> rows = r.deployments();
        for (int i = 0; i < rows.size(); i++) {
            FleetRightsize.DeploymentRow row = rows.get(i);
            if (i > 0) sb.append(',');
            sb.append('{')
              .append("\"namespace\":\"").append(escape(row.namespace())).append("\",")
              .append("\"deployment\":\"").append(escape(row.deployment())).append("\",")
              .append("\"podCount\":").append(row.podCount()).append(',')
              .append("\"peakHeapPercent\":");
            appendNumberOrNull(sb, row.peakHeapPercent());
            sb.append(",\"recommendedHeapPercent\":");
            appendNumberOrNull(sb, row.recommendedHeapPercent());
            sb.append(",\"savingsPercent\":");
            appendNumberOrNull(sb, row.savingsPercent());
            sb.append('}');
        }
        sb.append("]}}");
        return sb.toString();
    }

    public static String alertList(List<AlertEvent> alerts, int totalCount) {
        StringBuilder sb = new StringBuilder(1024);
        sb.append("{\"alerts\":[");
        for (int i = 0; i < alerts.size(); i++) {
            if (i > 0) sb.append(',');
            appendAlert(sb, alerts.get(i));
        }
        sb.append("],\"totalCount\":").append(totalCount).append('}');
        return sb.toString();
    }

    public static String registrationResponse(String podId, Instant registeredAt, boolean updated) {
        StringBuilder sb = new StringBuilder(128);
        sb.append("{\"podId\":\"").append(escape(podId)).append("\",")
          .append("\"registeredAt\":\"").append(registeredAt.toString()).append("\",")
          .append("\"updated\":").append(updated).append('}');
        return sb.toString();
    }

    // ── Internal appenders ──────────────────────────────────────────────────

    private static void appendPodTarget(StringBuilder sb, PodTarget t) {
        sb.append('{')
          .append("\"podId\":\"").append(escape(t.podId())).append("\",")
          .append("\"namespace\":\"").append(escape(t.namespace())).append("\",")
          .append("\"podName\":\"").append(escape(t.podName())).append("\",")
          .append("\"deployment\":\"").append(escape(t.deployment())).append("\",")
          .append("\"host\":\"").append(escape(t.host())).append("\",")
          .append("\"port\":").append(t.port()).append(',')
          .append("\"scrapeUrl\":\"").append(escape(t.scrapeUrl())).append("\",")
          .append("\"registeredAt\":\"").append(t.registeredAt().toString()).append("\",")
          .append("\"lastScrapeAt\":");
        if (t.lastScrapeAt() == null) {
            sb.append("null");
        } else {
            sb.append('"').append(t.lastScrapeAt().toString()).append('"');
        }
        sb.append(",\"scrapeOk\":").append(t.scrapeOk()).append('}');
    }

    private static void appendTileMetrics(StringBuilder sb, TileMetrics m) {
        sb.append('{');
        sb.append("\"heapPercent\":");
        appendNumberOrNull(sb, m.heapPercent());
        sb.append(",\"gcOverheadPercent\":");
        appendNumberOrNull(sb, m.gcOverheadPercent());
        sb.append(",\"cpuPercent\":");
        appendNumberOrNull(sb, m.cpuPercent());
        sb.append(",\"activeVThreads\":").append(m.activeVThreads());
        sb.append(",\"leakSuspected\":").append(m.leakSuspected());
        sb.append('}');
    }

    private static void appendTile(StringBuilder sb, Tile tile) {
        sb.append('{')
          .append("\"podId\":\"").append(escape(tile.podId())).append("\",")
          .append("\"color\":\"").append(tile.color().wireName()).append("\",")
          .append("\"target\":");
        appendPodTarget(sb, tile.target());
        sb.append(",\"metrics\":");
        appendTileMetrics(sb, tile.metrics());
        sb.append(",\"alertCount\":").append(tile.alertCount())
          .append(",\"drillDownUrl\":\"").append(escape(tile.drillDownUrl())).append("\"")
          .append('}');
    }

    private static void appendAlert(StringBuilder sb, AlertEvent e) {
        sb.append('{')
          .append("\"alertId\":\"").append(escape(e.alertId())).append("\",")
          .append("\"podId\":\"").append(escape(e.podId())).append("\",")
          .append("\"ruleName\":\"").append(escape(e.ruleName())).append("\",")
          .append("\"metric\":\"").append(escape(e.metric())).append("\",")
          .append("\"value\":").append(e.value()).append(",")
          .append("\"threshold\":").append(e.threshold()).append(",")
          .append("\"comparator\":\"").append(escape(e.comparator())).append("\",")
          .append("\"severity\":\"").append(escape(e.severity())).append("\",")
          .append("\"firedAt\":\"").append(e.firedAt().toString()).append("\",")
          .append("\"ongoing\":").append(e.ongoing())
          .append('}');
    }

    private static void appendSample(StringBuilder sb, MetricSample s) {
        sb.append('{')
          .append("\"ts\":\"").append(s.ts().toString()).append("\",")
          .append("\"heapPercent\":");
        appendNumberOrNull(sb, s.heapPercent());
        sb.append(",\"gcOverheadPercent\":");
        appendNumberOrNull(sb, s.gcOverheadPercent());
        sb.append(",\"cpuPercent\":");
        appendNumberOrNull(sb, s.cpuPercent());
        sb.append(",\"activeVThreads\":").append(s.activeVThreads())
          .append('}');
    }

    private static void appendMinMaxAvg(StringBuilder sb, FleetSummary.MinMaxAvg m) {
        sb.append('{');
        sb.append("\"min\":");
        appendNumberOrNull(sb, m.min());
        sb.append(",\"max\":");
        appendNumberOrNull(sb, m.max());
        sb.append(",\"avg\":");
        appendNumberOrNull(sb, m.avg());
        sb.append('}');
    }

    private static void appendNumberOrNull(StringBuilder sb, Double v) {
        if (v == null || Double.isNaN(v) || Double.isInfinite(v)) {
            sb.append("null");
            return;
        }
        // Avoid scientific notation ("1.0E308") — both JSON consumers and
        // Prometheus text format are fragile around it. Integer-valued doubles
        // render as plain integers; non-integers render via BigDecimal's
        // toPlainString for full precision without exponents.
        double d = v;
        if (d == Math.floor(d) && Math.abs(d) < 1e15) {
            sb.append((long) d);
        } else {
            sb.append(java.math.BigDecimal.valueOf(d).toPlainString());
        }
    }

    private static void appendStringOrNull(StringBuilder sb, String v) {
        if (v == null) {
            sb.append("null");
        } else {
            sb.append('"').append(escape(v)).append('"');
        }
    }

    public static String escape(String s) {
        if (s == null) return "";
        StringBuilder sb = new StringBuilder(s.length() + 8);
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '\\': sb.append("\\\\"); break;
                case '"':  sb.append("\\\""); break;
                case '\n': sb.append("\\n"); break;
                case '\r': sb.append("\\r"); break;
                case '\t': sb.append("\\t"); break;
                case '\b': sb.append("\\b"); break;
                case '\f': sb.append("\\f"); break;
                default:
                    if (c < 0x20) {
                        sb.append(String.format("\\u%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
            }
        }
        return sb.toString();
    }
}
