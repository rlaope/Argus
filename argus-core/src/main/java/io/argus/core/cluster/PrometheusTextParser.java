package io.argus.core.cluster;

import java.util.HashMap;
import java.util.Map;

/**
 * Parses Prometheus exposition text format into a map of metric name to value.
 * Skips comment lines (#) and handles optional labels in braces.
 */
public final class PrometheusTextParser {

    private PrometheusTextParser() {}

    /**
     * Parses Prometheus text format.
     * For metrics with labels (e.g. {@code metric{label="v"} 1.0}), the base metric name is used as the key.
     * When multiple samples share the same base name, the last value wins.
     *
     * @param text raw Prometheus exposition text
     * @return map of metric name to value
     */
    public static Map<String, Double> parse(String text) {
        Map<String, Double> metrics = new HashMap<>();
        if (text == null || text.isEmpty()) {
            return metrics;
        }
        for (String rawLine : text.split("\n")) {
            String line = rawLine.trim();
            if (line.isEmpty() || line.startsWith("#")) {
                continue;
            }
            int lastSpace = line.lastIndexOf(' ');
            if (lastSpace < 0) continue;
            String valueToken = line.substring(lastSpace + 1).trim();
            String rest = line.substring(0, lastSpace).trim();

            int secondSpace = rest.lastIndexOf(' ');
            if (secondSpace >= 0) {
                String candidate = rest.substring(secondSpace + 1).trim();
                if (isNumeric(candidate)) {
                    valueToken = candidate;
                    rest = rest.substring(0, secondSpace).trim();
                }
            }

            if (!isNumeric(valueToken)) continue;
            double value;
            try {
                value = Double.parseDouble(valueToken);
            } catch (NumberFormatException e) {
                continue;
            }

            String metricName;
            int braceOpen = rest.indexOf('{');
            if (braceOpen >= 0) {
                metricName = rest.substring(0, braceOpen).trim();
            } else {
                metricName = rest.trim();
            }
            if (!metricName.isEmpty()) {
                metrics.put(metricName, value);
            }
        }
        return metrics;
    }

    private static boolean isNumeric(String s) {
        if (s == null || s.isEmpty()) return false;
        try {
            Double.parseDouble(s);
            return true;
        } catch (NumberFormatException e) {
            return false;
        }
    }
}
