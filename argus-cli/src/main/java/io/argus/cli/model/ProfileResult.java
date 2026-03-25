package io.argus.cli.model;

import java.util.Collections;
import java.util.List;

/**
 * Result of an async-profiler profiling run.
 */
public final class ProfileResult {
    private final String status;
    private final String type;
    private final int durationSec;
    private final long totalSamples;
    private final List<MethodSample> topMethods;
    private final String flameGraphPath;
    private final String errorMessage;

    private ProfileResult(String status, String type, int durationSec, long totalSamples,
                          List<MethodSample> topMethods, String flameGraphPath, String errorMessage) {
        this.status = status;
        this.type = type;
        this.durationSec = durationSec;
        this.totalSamples = totalSamples;
        this.topMethods = topMethods;
        this.flameGraphPath = flameGraphPath;
        this.errorMessage = errorMessage;
    }

    public static ProfileResult ok(String type, int durationSec, long totalSamples,
                                   List<MethodSample> topMethods, String flameGraphPath) {
        List<MethodSample> methods = topMethods != null ? Collections.unmodifiableList(topMethods)
                : Collections.emptyList();
        return new ProfileResult("ok", type, durationSec, totalSamples, methods, flameGraphPath, null);
    }

    public static ProfileResult error(String message) {
        return new ProfileResult("error", null, 0, 0L, Collections.emptyList(), null, message);
    }

    public String status() { return status; }
    public String type() { return type; }
    public int durationSec() { return durationSec; }
    public long totalSamples() { return totalSamples; }
    public List<MethodSample> topMethods() { return topMethods; }
    public String flameGraphPath() { return flameGraphPath; }
    public String errorMessage() { return errorMessage; }
}
