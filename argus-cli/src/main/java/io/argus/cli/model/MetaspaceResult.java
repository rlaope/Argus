package io.argus.cli.model;

import java.util.List;

/**
 * Detailed metaspace statistics from jcmd VM.metaspace.
 */
public final class MetaspaceResult {
    private final long totalReserved;
    private final long totalCommitted;
    private final long totalUsed;
    private final List<SpaceInfo> spaces;

    public MetaspaceResult(long totalReserved, long totalCommitted, long totalUsed,
                           List<SpaceInfo> spaces) {
        this.totalReserved = totalReserved;
        this.totalCommitted = totalCommitted;
        this.totalUsed = totalUsed;
        this.spaces = spaces;
    }

    public long totalReserved() { return totalReserved; }
    public long totalCommitted() { return totalCommitted; }
    public long totalUsed() { return totalUsed; }
    public List<SpaceInfo> spaces() { return spaces; }

    public static final class SpaceInfo {
        private final String name;
        private final long reserved;
        private final long committed;
        private final long used;

        public SpaceInfo(String name, long reserved, long committed, long used) {
            this.name = name; this.reserved = reserved;
            this.committed = committed; this.used = used;
        }

        public String name() { return name; }
        public long reserved() { return reserved; }
        public long committed() { return committed; }
        public long used() { return used; }
    }
}
