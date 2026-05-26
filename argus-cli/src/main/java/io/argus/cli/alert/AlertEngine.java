package io.argus.cli.alert;

import io.argus.cli.render.AnsiStyle;
import io.argus.cli.render.RichRenderer;
import io.argus.core.alert.AlertEvaluator;
import io.argus.core.alert.AlertRule;
import io.argus.core.alert.WebhookSender;
import io.argus.core.cluster.PrometheusTextParser;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;

/**
 * Polls a Prometheus endpoint at a configurable interval, evaluates alert rules
 * against current metric values, and dispatches webhook notifications on breach.
 *
 * <p>Deduplication: an alert fires once when it first breaches its threshold, and
 * is suppressed until the metric returns to a non-breaching state.
 */
public final class AlertEngine {

    private static final DateTimeFormatter TIME_FMT = DateTimeFormatter.ofPattern("HH:mm:ss");
    private static final int WIDTH = RichRenderer.DEFAULT_WIDTH;

    private final String target;
    private final int intervalSeconds;
    private final boolean useColor;
    private final AlertEvaluator evaluator;
    private final WebhookSender webhookSender;

    public AlertEngine(String target, int intervalSeconds, List<AlertRule> rules, boolean useColor) {
        this.target = target;
        this.intervalSeconds = intervalSeconds;
        this.useColor = useColor;
        this.evaluator = new AlertEvaluator(rules);
        this.webhookSender = new WebhookSender();
    }

    /**
     * Starts the polling loop. Blocks until interrupted (e.g. Ctrl+C).
     */
    public void run() {
        String intervalLabel = intervalSeconds + "s";
        System.out.println(RichRenderer.boxHeader(useColor, "Alert Monitor",
                WIDTH, target, "checking every " + intervalLabel));
        System.out.println(RichRenderer.emptyLine(WIDTH));

        String rulesLine = "  Rules: " + evaluator.rules().size() + " active";
        System.out.println(RichRenderer.boxLine(
                AnsiStyle.style(useColor, AnsiStyle.BOLD) + rulesLine
                        + AnsiStyle.style(useColor, AnsiStyle.RESET), WIDTH));
        System.out.println(RichRenderer.emptyLine(WIDTH));

        HttpClient client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(5))
                .build();

        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            System.out.println(RichRenderer.emptyLine(WIDTH));
            System.out.println(RichRenderer.boxLine(
                    AnsiStyle.style(useColor, AnsiStyle.DIM) + "  Stopped."
                            + AnsiStyle.style(useColor, AnsiStyle.RESET), WIDTH));
            System.out.println(RichRenderer.boxFooter(useColor, null, WIDTH));
        }, "argus-alert-cleanup"));

        try {
            while (!Thread.currentThread().isInterrupted()) {
                poll(client);
                Thread.sleep(intervalSeconds * 1000L);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private void poll(HttpClient client) {
        String now = LocalTime.now().format(TIME_FMT);
        Map<String, Double> metrics = fetchMetrics(client);

        for (AlertEvaluator.Outcome outcome : evaluator.evaluate(metrics)) {
            AlertRule rule = outcome.rule();
            double value = outcome.value();
            String displayValue = formatValue(rule, value);
            String line;
            if (outcome.breached()) {
                if (outcome.firstFire()) {
                    webhookSender.send(rule, value, target);
                    line = "  [" + now + "] "
                            + AnsiStyle.style(useColor, AnsiStyle.YELLOW) + "⚠ "
                            + rule.name() + ": " + displayValue + " EXCEEDED → webhook sent"
                            + AnsiStyle.style(useColor, AnsiStyle.RESET);
                } else {
                    line = "  [" + now + "] "
                            + AnsiStyle.style(useColor, AnsiStyle.YELLOW) + "⚠ "
                            + rule.name() + ": " + displayValue + " EXCEEDED (ongoing)"
                            + AnsiStyle.style(useColor, AnsiStyle.RESET);
                }
            } else {
                line = "  [" + now + "] "
                        + AnsiStyle.style(useColor, AnsiStyle.GREEN) + "✓ "
                        + rule.name() + ": " + displayValue
                        + AnsiStyle.style(useColor, AnsiStyle.RESET);
            }
            System.out.println(RichRenderer.boxLine(line, WIDTH));
        }
    }

    private Map<String, Double> fetchMetrics(HttpClient client) {
        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create("http://" + target + "/prometheus"))
                    .timeout(Duration.ofSeconds(5))
                    .GET()
                    .build();
            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() == 200) {
                return PrometheusTextParser.parse(response.body());
            }
            System.err.println("[alert] HTTP " + response.statusCode() + " from " + target);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (Exception e) {
            System.err.println("[alert] Failed to reach " + target + ": " + e.getMessage());
        }
        return Map.of();
    }

    /**
     * Formats the metric value for display. Percentage metrics are shown as percentages,
     * boolean-style metrics (threshold=1) are shown as detected/not detected.
     */
    private static String formatValue(AlertRule rule, double value) {
        if (rule.threshold() <= 1.0 && rule.metric().contains("suspected")) {
            return value >= 1.0 ? "detected" : "not detected";
        }
        if (rule.metric().contains("ratio") || rule.metric().contains("overhead")) {
            return String.format("%.1f%%", value * 100);
        }
        return String.format("%.1f", value);
    }
}
