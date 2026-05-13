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
    private final long deoptCount;

    public CompilerResult(long codeCacheSizeKb, long codeCacheUsedKb, long codeCacheMaxUsedKb,
                          long codeCacheFreeKb, int totalBlobs, int nmethods, int adapters,
                          boolean compilationEnabled, int queueSize) {
        this(codeCacheSizeKb, codeCacheUsedKb, codeCacheMaxUsedKb, codeCacheFreeKb,
                totalBlobs, nmethods, adapters, compilationEnabled, queueSize, 0L);
    }

    public CompilerResult(long codeCacheSizeKb, long codeCacheUsedKb, long codeCacheMaxUsedKb,
                          long codeCacheFreeKb, int totalBlobs, int nmethods, int adapters,
                          boolean compilationEnabled, int queueSize, long deoptCount) {
        this.codeCacheSizeKb = codeCacheSizeKb;
        this.codeCacheUsedKb = codeCacheUsedKb;
        this.codeCacheMaxUsedKb = codeCacheMaxUsedKb;
        this.codeCacheFreeKb = codeCacheFreeKb;
        this.totalBlobs = totalBlobs;
        this.nmethods = nmethods;
        this.adapters = adapters;
        this.compilationEnabled = compilationEnabled;
        this.queueSize = queueSize;
        this.deoptCount = deoptCount;
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
    /** Total runtime deoptimizations since JVM start; 0 when unknown. */
    public long deoptCount() { return deoptCount; }
}
