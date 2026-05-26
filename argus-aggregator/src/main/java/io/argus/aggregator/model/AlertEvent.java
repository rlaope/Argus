package io.argus.aggregator.model;

import java.time.Instant;

/**
 * A single firing alert.
 *
 * @param alertId    stable ID: {@code <podId>/<ruleName>}
 * @param podId      which target triggered this alert
 * @param ruleName   alert rule name as configured
 * @param metric     Prometheus metric name that breached
 * @param value      metric value at breach time
 * @param threshold  configured threshold
 * @param comparator one of: ">", ">=", "<", "<="
 * @param severity   one of: "critical", "warning", "info"
 * @param firedAt    timestamp when alert first fired
 * @param ongoing    true if still breached on latest scrape
 */
public record AlertEvent(
        String alertId,
        String podId,
        String ruleName,
        String metric,
        double value,
        double threshold,
        String comparator,
        String severity,
        Instant firedAt,
        boolean ongoing
) {
    public static String alertIdFor(String podId, String ruleName) {
        return podId + "/" + ruleName;
    }
}
