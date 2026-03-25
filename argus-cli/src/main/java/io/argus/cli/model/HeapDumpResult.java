package io.argus.cli.model;

/**
 * Result of a heap dump operation.
 */
public final class HeapDumpResult {

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
}
