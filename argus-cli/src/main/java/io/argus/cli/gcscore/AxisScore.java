package io.argus.cli.gcscore;

/**
 * Scored result for one KPI axis of the GC Health Score Card.
 *
 * @param name        axis display name (e.g. "Pause p99")
 * @param value       the axis value (e.g. 82.0 for 82 ms)
 * @param unit        human unit string (e.g. "ms", "%", "MB/s", "/hour")
 * @param target      short target-band description (e.g. "< 200 ms")
 * @param score       0–100 numeric score for this axis
 * @param verdict     Pass / Warn / Fail bucket
 * @param available   false if the underlying metric was missing (shown as N/A)
 */
public record AxisScore(
        String name,
        double value,
        String unit,
        String target,
        int score,
        Verdict verdict,
        boolean available) {

    public enum Verdict { PASS, WARN, FAIL, NA }

    public static AxisScore na(String name, String target) {
        return new AxisScore(name, 0, "", target, 0, Verdict.NA, false);
    }
}
