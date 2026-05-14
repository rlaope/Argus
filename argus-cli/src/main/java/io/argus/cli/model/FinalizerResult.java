package io.argus.cli.model;

import io.argus.cli.json.JsonWritable;
import io.argus.cli.render.RichRenderer;

/**
 * Finalizer queue status.
 */
public final class FinalizerResult implements JsonWritable {
    private final int pendingCount;
    private final String finalizerThreadState;

    public FinalizerResult(int pendingCount, String finalizerThreadState) {
        this.pendingCount = pendingCount;
        this.finalizerThreadState = finalizerThreadState;
    }

    public int pendingCount() { return pendingCount; }
    public String finalizerThreadState() { return finalizerThreadState; }

    @Override
    public void writeJson(StringBuilder out) {
        out.append("{\"pendingCount\":").append(pendingCount)
           .append(",\"finalizerThreadState\":\"").append(RichRenderer.escapeJson(finalizerThreadState))
           .append("\"}");
    }
}
