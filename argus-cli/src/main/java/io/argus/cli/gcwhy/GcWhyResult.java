package io.argus.cli.gcwhy;

import java.util.List;
import java.util.Map;

/**
 * Result of the gcwhy correlation engine: the worst pause in the window,
 * a small set of "why" bullets, and a map of related counters to display.
 */
public final class GcWhyResult {
    private final double timestampSec;
    private final String type;
    private final String cause;
    private final double pauseMs;
    private final List<String> bullets;
    private final Map<String, String> counters;

    public GcWhyResult(double timestampSec, String type, String cause, double pauseMs,
                      List<String> bullets, Map<String, String> counters) {
        this.timestampSec = timestampSec;
        this.type = type;
        this.cause = cause;
        this.pauseMs = pauseMs;
        this.bullets = bullets;
        this.counters = counters;
    }

    public double timestampSec() { return timestampSec; }
    public String type() { return type; }
    public String cause() { return cause; }
    public double pauseMs() { return pauseMs; }
    public List<String> bullets() { return bullets; }
    public Map<String, String> counters() { return counters; }

    public static GcWhyResult empty() {
        return new GcWhyResult(0, "", "", 0, List.of(), Map.of());
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof GcWhyResult)) return false;
        GcWhyResult that = (GcWhyResult) o;
        return Double.compare(that.timestampSec, timestampSec) == 0
                && Double.compare(that.pauseMs, pauseMs) == 0
                && java.util.Objects.equals(type, that.type)
                && java.util.Objects.equals(cause, that.cause)
                && java.util.Objects.equals(bullets, that.bullets)
                && java.util.Objects.equals(counters, that.counters);
    }

    @Override
    public int hashCode() {
        return java.util.Objects.hash(timestampSec, type, cause, pauseMs, bullets, counters);
    }

    @Override
    public String toString() {
        return "GcWhyResult[timestampSec=" + timestampSec + ", type=" + type + ", cause=" + cause
                + ", pauseMs=" + pauseMs + ", bullets=" + bullets + ", counters=" + counters + "]";
    }
}
