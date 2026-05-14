package io.argus.cli.model;

import io.argus.cli.json.JsonWritable;
import io.argus.cli.render.RichRenderer;

/**
 * Result of a JFR Flight Recorder control command.
 */
public final class JfrResult implements JsonWritable {
    private final String status;
    private final String message;
    private final String recordingInfo;

    public JfrResult(String status, String message, String recordingInfo) {
        this.status = status;
        this.message = message;
        this.recordingInfo = recordingInfo;
    }

    public String status() { return status; }
    public String message() { return message; }
    public String recordingInfo() { return recordingInfo; }

    @Override
    public void writeJson(StringBuilder out) {
        out.append("{\"status\":\"").append(RichRenderer.escapeJson(status)).append('"')
           .append(",\"message\":\"").append(RichRenderer.escapeJson(message)).append('"');
        if (recordingInfo != null) {
            out.append(",\"recordingInfo\":\"").append(RichRenderer.escapeJson(recordingInfo)).append('"');
        } else {
            out.append(",\"recordingInfo\":null");
        }
        out.append('}');
    }
}
