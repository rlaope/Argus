package io.argus.cli.model;

import java.util.List;

/**
 * Snapshot of native memory tracking from jcmd VM.native_memory summary.
 */
public record NmtResult(long totalReservedKB, long totalCommittedKB, List<NmtCategory> categories) {
    public record NmtCategory(String name, long reservedKB, long committedKB) {}
}
