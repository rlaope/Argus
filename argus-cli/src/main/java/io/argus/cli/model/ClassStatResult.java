package io.argus.cli.model;

import io.argus.diagnostics.json.JsonWritable;

/**
 * Class loading statistics from jstat -class.
 */
public final class ClassStatResult implements JsonWritable {
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

    @Override
    public void writeJson(StringBuilder out) {
        out.append("{\"loaded\":").append(loaded)
           .append(",\"loadedBytes\":").append(loadedBytes)
           .append(",\"unloaded\":").append(unloaded)
           .append(",\"unloadedBytes\":").append(unloadedBytes)
           .append(",\"timeMs\":").append(timeMs)
           .append('}');
    }
}
