package io.argus.cli.alert;

/**
 * A single alerting rule that compares a Prometheus metric value against a threshold.
 */
public final class AlertRule {
    private final String name;
    private final String metric;
    private final double threshold;
    private final String comparator;
    private final String severity;
    private final String webhookUrl;

    public AlertRule(String name, String metric, double threshold,
                     String comparator, String severity, String webhookUrl) {
        this.name = name;
        this.metric = metric;
        this.threshold = threshold;
        this.comparator = comparator;
        this.severity = severity;
        this.webhookUrl = webhookUrl;
    }

    public String name() { return name; }
    public String metric() { return metric; }
    public double threshold() { return threshold; }
    public String comparator() { return comparator; }
    public String severity() { return severity; }
    public String webhookUrl() { return webhookUrl; }

    /** Returns true when the given metric value breaches this rule's threshold. */
    public boolean isBreached(double value) {
        if ("<".equals(comparator)) return value < threshold;
        if ("<=".equals(comparator)) return value <= threshold;
        if (">=".equals(comparator)) return value >= threshold;
        return value > threshold; // ">" and fallback
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof AlertRule)) return false;
        AlertRule that = (AlertRule) o;
        return Double.compare(that.threshold, threshold) == 0
                && java.util.Objects.equals(name, that.name)
                && java.util.Objects.equals(metric, that.metric)
                && java.util.Objects.equals(comparator, that.comparator)
                && java.util.Objects.equals(severity, that.severity)
                && java.util.Objects.equals(webhookUrl, that.webhookUrl);
    }

    @Override
    public int hashCode() {
        return java.util.Objects.hash(name, metric, threshold, comparator, severity, webhookUrl);
    }

    @Override
    public String toString() {
        return "AlertRule[name=" + name + ", metric=" + metric + ", threshold=" + threshold
                + ", comparator=" + comparator + ", severity=" + severity
                + ", webhookUrl=" + webhookUrl + "]";
    }
}
