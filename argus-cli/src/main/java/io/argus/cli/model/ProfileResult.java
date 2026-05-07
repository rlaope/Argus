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
    // Human-readable status text from session subcommands (start/stop/dump/status).
    // Null for the one-shot profile path. status() returns "ok" when a session
    // subcommand succeeded; statusText carries the asprof message detail.
    private final String statusText;
    // Raw collapsed-stack text from async-profiler; null unless explicitly requested
    // (e.g. --output-format=ascii). Preserved as-is for downstream renderers.
    private final String collapsedRaw;

    private ProfileResult(String status, String type, int durationSec, long totalSamples,
                          List<MethodSample> topMethods, String flameGraphPath, String errorMessage,
                          String statusText, String collapsedRaw) {
        this.status = status;
        this.type = type;
        this.durationSec = durationSec;
        this.totalSamples = totalSamples;
        this.topMethods = topMethods;
        this.flameGraphPath = flameGraphPath;
        this.errorMessage = errorMessage;
        this.statusText = statusText;
        this.collapsedRaw = collapsedRaw;
    }

    public static ProfileResult ok(String type, int durationSec, long totalSamples,
                                   List<MethodSample> topMethods, String flameGraphPath) {
        List<MethodSample> methods = topMethods != null ? Collections.unmodifiableList(topMethods)
                : Collections.emptyList();
        return new ProfileResult("ok", type, durationSec, totalSamples, methods, flameGraphPath, null, null, null);
    }

    /**
     * Like {@link #ok} but also stores the raw collapsed-stack text for ASCII flame rendering.
     */
    public static ProfileResult okWithRaw(String type, int durationSec, long totalSamples,
                                          List<MethodSample> topMethods, String flameGraphPath,
                                          String collapsedRaw) {
        List<MethodSample> methods = topMethods != null ? Collections.unmodifiableList(topMethods)
                : Collections.emptyList();
        return new ProfileResult("ok", type, durationSec, totalSamples, methods, flameGraphPath, null, null, collapsedRaw);
    }

    public static ProfileResult error(String message) {
        return new ProfileResult("error", null, 0, 0L, Collections.emptyList(), null, message, null, null);
    }

    /**
     * Result for session subcommands (start/stop/dump/status). Carries
     * a human-readable text payload alongside an "ok" status.
     */
    public static ProfileResult session(String type, String statusText, String flameGraphPath) {
        return new ProfileResult("ok", type, 0, 0L, Collections.emptyList(), flameGraphPath, null, statusText, null);
    }

    public String status() { return status; }
    public String type() { return type; }
    public int durationSec() { return durationSec; }
    public long totalSamples() { return totalSamples; }
    public List<MethodSample> topMethods() { return topMethods; }
    public String flameGraphPath() { return flameGraphPath; }
    public String errorMessage() { return errorMessage; }
    public String statusText() { return statusText; }
    public String collapsedRaw() { return collapsedRaw; }
}
