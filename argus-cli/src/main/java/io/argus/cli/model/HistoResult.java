package io.argus.cli.model;

import java.util.List;

/**
 * Heap object histogram result.
 */
public record HistoResult(List<Entry> entries, long totalInstances, long totalBytes) {

    public record Entry(int rank, String className, long instances, long bytes) {}
}
