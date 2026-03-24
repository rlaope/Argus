package io.argus.cli.model;

import java.util.List;

/**
 * Snapshot of native memory tracking from jcmd VM.native_memory summary.
 */
public final class NmtResult {
    private final long totalReservedKB;
    private final long totalCommittedKB;
    private final List<NmtCategory> categories;

    public NmtResult(long totalReservedKB, long totalCommittedKB, List<NmtCategory> categories) {
        this.totalReservedKB = totalReservedKB;
        this.totalCommittedKB = totalCommittedKB;
        this.categories = categories;
    }

    public long totalReservedKB() { return totalReservedKB; }
    public long totalCommittedKB() { return totalCommittedKB; }
    public List<NmtCategory> categories() { return categories; }

    public static final class NmtCategory {
        private final String name;
        private final long reservedKB;
        private final long committedKB;

        public NmtCategory(String name, long reservedKB, long committedKB) {
            this.name = name;
            this.reservedKB = reservedKB;
            this.committedKB = committedKB;
        }

        public String name() { return name; }
        public long reservedKB() { return reservedKB; }
        public long committedKB() { return committedKB; }
    }
}
