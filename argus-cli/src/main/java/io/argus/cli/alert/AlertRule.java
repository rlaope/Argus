package io.argus.cli.alert;

/**
 * A single alerting rule that compares a Prometheus metric value against a threshold.
 *
 * @param name        human-readable rule name (e.g. "gc-overhead")
 * @param metric      Prometheus metric name (e.g. "argus_gc_overhead_ratio")
 * @param threshold   numeric threshold value
 * @param comparator  "&gt;" or "&lt;" — defaults to "&gt;" when unrecognised
 * @param severity    "warning" or "critical"
 * @param webhookUrl  destination webhook URL (may be null)
 */
public record AlertRule(
        String name,
        String metric,
        double threshold,
        String comparator,
        String severity,
        String webhookUrl) {

    /** Returns true when the given metric value breaches this rule's threshold. */
    public boolean isBreached(double value) {
        return switch (comparator) {
            case "<"  -> value < threshold;
            case "<=" -> value <= threshold;
            case ">=" -> value >= threshold;
            default   -> value > threshold; // ">" and fallback
        };
    }
}
