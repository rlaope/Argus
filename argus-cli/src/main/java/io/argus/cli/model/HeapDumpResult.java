package io.argus.cli.model;

import io.argus.cli.json.JsonWritable;
import io.argus.cli.render.RichRenderer;

/**
 * Result of a heap dump operation.
 */
public final class HeapDumpResult implements JsonWritable {

    private final String status;
    private final String filePath;
    private final long fileSizeBytes;
    private final String errorMessage;

    public HeapDumpResult(String status, String filePath, long fileSizeBytes, String errorMessage) {
        this.status = status;
        this.filePath = filePath;
        this.fileSizeBytes = fileSizeBytes;
        this.errorMessage = errorMessage;
    }

    public String status() {
        return status;
    }

    public String filePath() {
        return filePath;
    }

    public long fileSizeBytes() {
        return fileSizeBytes;
    }

    public String errorMessage() {
        return errorMessage;
    }

    @Override
    public void writeJson(StringBuilder out) {
        out.append("{\"status\":\"").append(RichRenderer.escapeJson(status)).append('"')
           .append(",\"filePath\":\"").append(RichRenderer.escapeJson(filePath)).append('"')
           .append(",\"fileSizeBytes\":").append(fileSizeBytes);
        if (errorMessage != null) {
            out.append(",\"errorMessage\":\"").append(RichRenderer.escapeJson(errorMessage)).append('"');
        } else {
            out.append(",\"errorMessage\":null");
        }
        out.append('}');
    }
}
