package io.argus.cli.model;

/**
 * String table (interned strings) statistics.
 */
public final class StringTableResult {
    private final long bucketCount;
    private final long entryCount;
    private final long literalCount;
    private final long bucketBytes;
    private final long entryBytes;
    private final long literalBytes;
    private final long totalBytes;
    private final double avgLiteralSize;

    public StringTableResult(long bucketCount, long entryCount, long literalCount,
                             long bucketBytes, long entryBytes, long literalBytes,
                             long totalBytes, double avgLiteralSize) {
        this.bucketCount = bucketCount;
        this.entryCount = entryCount;
        this.literalCount = literalCount;
        this.bucketBytes = bucketBytes;
        this.entryBytes = entryBytes;
        this.literalBytes = literalBytes;
        this.totalBytes = totalBytes;
        this.avgLiteralSize = avgLiteralSize;
    }

    public long bucketCount() { return bucketCount; }
    public long entryCount() { return entryCount; }
    public long literalCount() { return literalCount; }
    public long bucketBytes() { return bucketBytes; }
    public long entryBytes() { return entryBytes; }
    public long literalBytes() { return literalBytes; }
    public long totalBytes() { return totalBytes; }
    public double avgLiteralSize() { return avgLiteralSize; }
}
