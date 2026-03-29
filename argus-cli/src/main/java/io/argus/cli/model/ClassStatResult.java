package io.argus.cli.model;

/**
 * Class loading statistics from jstat -class.
 */
public final class ClassStatResult {
    private final long loaded;
    private final double loadedBytes;
    private final long unloaded;
    private final double unloadedBytes;
    private final double timeMs;

    public ClassStatResult(long loaded, double loadedBytes, long unloaded,
                           double unloadedBytes, double timeMs) {
        this.loaded = loaded;
        this.loadedBytes = loadedBytes;
        this.unloaded = unloaded;
        this.unloadedBytes = unloadedBytes;
        this.timeMs = timeMs;
    }

    public long loaded() { return loaded; }
    public double loadedBytes() { return loadedBytes; }
    public long unloaded() { return unloaded; }
    public double unloadedBytes() { return unloadedBytes; }
    public double timeMs() { return timeMs; }
}
