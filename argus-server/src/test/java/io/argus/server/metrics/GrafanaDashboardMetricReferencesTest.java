package io.argus.server.metrics;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GrafanaDashboardMetricReferencesTest {

    private static final Pattern EXPR = Pattern.compile("\"expr\"\\s*:\\s*\"((?:\\\\.|[^\"\\\\])*)\"");
    private static final Pattern METRIC_TOKEN = Pattern.compile("\\b(?:argus|jvm)_[A-Za-z0-9_:]+\\b");
    private static final Pattern APPEND_METRIC_LITERAL =
            Pattern.compile("append(?:Gauge|Counter)\\(sb,\\s*\"([a-zA-Z_:][a-zA-Z0-9_:]*)\"");
    private static final Pattern HELP_METRIC_LITERAL =
            Pattern.compile("# HELP ([a-zA-Z_:][a-zA-Z0-9_:]*)\\b");
    private static final Pattern SEMCONV_PROMETHEUS_NAME =
            Pattern.compile("new Metric\\(\\s*\"[^\"]+\",\\s*\"([a-zA-Z_:][a-zA-Z0-9_:]*)\"");
    private static final Pattern DOC_METRIC = Pattern.compile("`((?:argus|jvm)_[A-Za-z0-9_:]+)`");
    private static final Pattern VARIABLE_NAME = Pattern.compile("\"name\"\\s*:\\s*\"([A-Za-z0-9_]+)\"");
    private static final Pattern NUMERIC_PANEL_ID = Pattern.compile("\"id\"\\s*:\\s*(\\d+)");
    private static final List<String> FLEET_LABEL_MATCHERS = List.of(
            "namespace=~\"$namespace\"",
            "deployment=~\"$deployment\"",
            "pod=~\"$pod\"",
            "instance=~\"$instance\""
    );
    private static final Set<String> HISTOGRAM_FAMILIES = Set.of(
            "argus_gc_pause_seconds",
            "jvm_gc_duration_seconds"
    );

    @Test
    void dashboardPromqlReferencesOnlyExportedAndDocumentedMetrics() throws Exception {
        String dashboard = readRepoFile("docs/grafana-dashboard.json");
        Set<String> referenced = extractReferencedMetrics(dashboard);
        Set<String> exported = exportedMetricNames();
        Set<String> documented = documentedMetricNames();

        assertFalse(referenced.isEmpty(), "Grafana dashboard should contain PromQL metric references");
        for (String metric : referenced) {
            assertTrue(containsMetric(exported, metric),
                    () -> "Grafana references metric not emitted by PrometheusMetricsCollector: " + metric);
            assertTrue(containsMetric(documented, metric),
                    () -> "Grafana references metric missing from docs/dashboard-contract.md: " + metric);
        }
    }

    @Test
    void dashboardPromqlAppliesFleetVariablesToMetricSelectors() throws Exception {
        String dashboard = readRepoFile("docs/grafana-dashboard.json");
        for (String expr : extractExpressions(dashboard)) {
            Matcher tokens = METRIC_TOKEN.matcher(expr);
            while (tokens.find()) {
                int selectorStart = tokens.end();
                assertTrue(selectorStart < expr.length() && expr.charAt(selectorStart) == '{',
                        () -> "metric selector missing fleet label matchers for " + tokens.group() + " in " + expr);
                int selectorEnd = expr.indexOf('}', selectorStart);
                assertTrue(selectorEnd > selectorStart,
                        () -> "metric selector is not closed for " + tokens.group() + " in " + expr);
                String selector = expr.substring(selectorStart + 1, selectorEnd);
                for (String matcher : FLEET_LABEL_MATCHERS) {
                    assertTrue(selector.contains(matcher),
                            () -> "metric selector missing " + matcher + " for " + tokens.group() + " in " + expr);
                }
            }
        }
    }

    @Test
    void dashboardHasFleetVariablesAndLocalDrilldownLinks() throws Exception {
        String dashboard = readRepoFile("docs/grafana-dashboard.json");
        Set<String> variables = extract(VARIABLE_NAME, dashboard);

        for (String expected : Set.of("datasource", "namespace", "deployment", "pod", "instance")) {
            assertTrue(variables.contains(expected), "missing Grafana variable: " + expected);
        }

        assertTrue(dashboard.contains("/fleet.html"), "dashboard should link to Fleet");
        assertTrue(dashboard.contains("/profiles.html?"), "dashboard should link to Profiles with query state");
        assertTrue(dashboard.contains("/console.html?"), "dashboard should link to Console with pod context");
        assertTrue(dashboard.contains("/prometheus"), "dashboard should link to the Prometheus scrape endpoint");
        assertTrue(dashboard.contains("docs/kubernetes.md"), "dashboard should link to Kubernetes setup docs");
    }

    @Test
    void dashboardPanelsKeepRequiredShapeAndUniqueIds() throws Exception {
        String dashboard = readRepoFile("docs/grafana-dashboard.json");
        Set<String> ids = new HashSet<>();
        Matcher matcher = NUMERIC_PANEL_ID.matcher(dashboard);
        int panelIdCount = 0;
        while (matcher.find()) {
            panelIdCount++;
            assertTrue(ids.add(matcher.group(1)), "duplicate Grafana panel id: " + matcher.group(1));
        }

        assertTrue(panelIdCount >= 50, "expected the dashboard to keep the existing panel coverage");
        assertTrue(countOccurrences(dashboard, "\"gridPos\"") >= panelIdCount,
                "every panel should carry a gridPos block");
        assertTrue(countOccurrences(dashboard, "\"title\"") >= panelIdCount,
                "every panel should carry a title");
        assertTrue(countOccurrences(dashboard, "\"type\"") >= panelIdCount,
                "every panel should carry a type");
    }

    @Test
    void helmPackagedDashboardMatchesPrimaryGrafanaDashboard() throws Exception {
        assertEquals(
                readRepoFile("docs/grafana-dashboard.json"),
                readRepoFile("charts/argus/dashboards/argus-jvm-observability.json"),
                "Helm-packaged Grafana dashboard must stay in sync with docs/grafana-dashboard.json");
    }

    private static Set<String> extractReferencedMetrics(String dashboard) {
        Set<String> metrics = new LinkedHashSet<>();
        for (String expr : extractExpressions(dashboard)) {
            Matcher tokens = METRIC_TOKEN.matcher(expr);
            while (tokens.find()) {
                metrics.add(tokens.group());
            }
        }
        return metrics;
    }

    private static List<String> extractExpressions(String dashboard) {
        List<String> expressions = new ArrayList<>();
        Matcher exprs = EXPR.matcher(dashboard);
        while (exprs.find()) {
            expressions.add(unescapeJsonString(exprs.group(1)));
        }
        return expressions;
    }

    private static Set<String> exportedMetricNames() throws IOException {
        Set<String> names = new HashSet<>();
        String collector = readRepoFile("argus-server/src/main/java/io/argus/server/metrics/PrometheusMetricsCollector.java");
        names.addAll(extract(APPEND_METRIC_LITERAL, collector));
        names.addAll(extract(HELP_METRIC_LITERAL, collector));

        String semconv = readRepoFile("argus-server/src/main/java/io/argus/server/metrics/SemconvMetrics.java");
        names.addAll(extract(SEMCONV_PROMETHEUS_NAME, semconv));
        return names;
    }

    private static Set<String> documentedMetricNames() throws IOException {
        String contract = readRepoFile("docs/dashboard-contract.md");
        return extract(DOC_METRIC, contract);
    }

    private static boolean containsMetric(Set<String> names, String metric) {
        if (names.contains(metric)) {
            return true;
        }
        String family = histogramFamily(metric);
        return family != null && names.contains(family);
    }

    private static String histogramFamily(String name) {
        String family = name.replaceFirst("_(bucket|sum|count)$", "");
        return !family.equals(name) && HISTOGRAM_FAMILIES.contains(family) ? family : null;
    }

    private static Set<String> extract(Pattern pattern, String text) {
        Set<String> values = new LinkedHashSet<>();
        Matcher matcher = pattern.matcher(text);
        while (matcher.find()) {
            values.add(matcher.group(1));
        }
        return values;
    }

    private static int countOccurrences(String text, String needle) {
        int count = 0;
        int index = 0;
        while ((index = text.indexOf(needle, index)) >= 0) {
            count++;
            index += needle.length();
        }
        return count;
    }

    private static String unescapeJsonString(String value) {
        return value
                .replace("\\\"", "\"")
                .replace("\\\\", "\\")
                .replace("\\n", "\n")
                .replace("\\t", "\t");
    }

    private static String readRepoFile(String relative) throws IOException {
        Path userDir = Path.of(System.getProperty("user.dir")).toAbsolutePath();
        for (Path root = userDir; root != null; root = root.getParent()) {
            Path candidate = root.resolve(relative);
            if (Files.exists(candidate)) {
                return Files.readString(candidate);
            }
        }
        throw new IOException("Unable to find repo file: " + relative + " from " + userDir);
    }
}
