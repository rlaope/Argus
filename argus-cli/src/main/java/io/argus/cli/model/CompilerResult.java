package io.argus.cli.model;

/**
 * JIT compiler and code cache statistics.
 */
public final class CompilerResult {
    private final long codeCacheSizeKb;
    private final long codeCacheUsedKb;
    private final long codeCacheMaxUsedKb;
    private final long codeCacheFreeKb;
    private final int totalBlobs;
    private final int nmethods;
    private final int adapters;
    private final boolean compilationEnabled;
    private final int queueSize;

    public CompilerResult(long codeCacheSizeKb, long codeCacheUsedKb, long codeCacheMaxUsedKb,
                          long codeCacheFreeKb, int totalBlobs, int nmethods, int adapters,
                          boolean compilationEnabled, int queueSize) {
        this.codeCacheSizeKb = codeCacheSizeKb;
        this.codeCacheUsedKb = codeCacheUsedKb;
        this.codeCacheMaxUsedKb = codeCacheMaxUsedKb;
        this.codeCacheFreeKb = codeCacheFreeKb;
        this.totalBlobs = totalBlobs;
        this.nmethods = nmethods;
        this.adapters = adapters;
        this.compilationEnabled = compilationEnabled;
        this.queueSize = queueSize;
    }

    public long codeCacheSizeKb() { return codeCacheSizeKb; }
    public long codeCacheUsedKb() { return codeCacheUsedKb; }
    public long codeCacheMaxUsedKb() { return codeCacheMaxUsedKb; }
    public long codeCacheFreeKb() { return codeCacheFreeKb; }
    public int totalBlobs() { return totalBlobs; }
    public int nmethods() { return nmethods; }
    public int adapters() { return adapters; }
    public boolean compilationEnabled() { return compilationEnabled; }
    public int queueSize() { return queueSize; }
}
