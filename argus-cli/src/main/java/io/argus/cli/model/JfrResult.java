package io.argus.cli.model;

/**
 * Result of a JFR Flight Recorder control command.
 */
public final class JfrResult {
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
}
