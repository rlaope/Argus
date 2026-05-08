package io.argus.cli.harness;

import java.time.Duration;

/**
 * A pre-tuned bundle of {@code interval} + default {@code duration} for the
 * harness, balancing observation depth against load on the target JVM.
 *
 * <p>Users can still override either value via {@code --interval} / {@code --duration}.
 */
public enum HarnessProfile {

    /** Fast feedback for short investigations: 2s tick, 1m default duration. */
    QUICK(Duration.ofSeconds(2), Duration.ofMinutes(1)),

    /** Trend-focused: 10s tick, 30m default duration. */
    DEEP(Duration.ofSeconds(10), Duration.ofMinutes(30));

    private final Duration interval;
    private final Duration defaultDuration;

    HarnessProfile(Duration interval, Duration defaultDuration) {
        this.interval = interval;
        this.defaultDuration = defaultDuration;
    }

    public Duration interval() { return interval; }
    public Duration defaultDuration() { return defaultDuration; }

    public static HarnessProfile parse(String s) {
        if (s == null || s.isEmpty()) return DEEP;
        switch (s.toLowerCase()) {
            case "quick": return QUICK;
            case "deep":  return DEEP;
            default: throw new IllegalArgumentException("Unknown harness profile: " + s);
        }
    }
}
