package io.argus.cli.model;

import java.util.Map;

/**
 * Heap memory usage result.
 */
public final class HeapResult {
    private final long used;
    private final long committed;
    private final long max;
    private final Map<String, SpaceInfo> spaces;

    public HeapResult(long used, long committed, long max, Map<String, SpaceInfo> spaces) {
        this.used = used;
        this.committed = committed;
        this.max = max;
        this.spaces = spaces;
    }

    public long used() { return used; }
    public long committed() { return committed; }
    public long max() { return max; }
    public Map<String, SpaceInfo> spaces() { return spaces; }

    public static final class SpaceInfo {
        private final String name;
        private final long used;
        private final long committed;
        private final long max;

        public SpaceInfo(String name, long used, long committed, long max) {
            this.name = name;
            this.used = used;
            this.committed = committed;
            this.max = max;
        }

        public String name() { return name; }
        public long used() { return used; }
        public long committed() { return committed; }
        public long max() { return max; }
    }
}
