package io.argus.cli.model;

/**
 * A single method sample entry from async-profiler collapsed stack output.
 */
public final class MethodSample {
    private final String method;
    private final long samples;
    private final double percentage;

    public MethodSample(String method, long samples, double percentage) {
        this.method = method;
        this.samples = samples;
        this.percentage = percentage;
    }

    public String method() { return method; }
    public long samples() { return samples; }
    public double percentage() { return percentage; }
}
