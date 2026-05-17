package io.argus.diagnostics.model;

import java.util.List;

/**
 * Object age distribution from the young generation survivor spaces.
 * Age data is extracted from GC log debug output (-Xlog:gc+age=debug)
 * or from jcmd GC.heap_info when available.
 */
public final class AgeDistribution {
    private final List<AgeEntry> entries;
    private final int tenuringThreshold;
    private final int maxTenuringThreshold;
    private final long desiredSurvivorSize;
    private final long survivorCapacity;

    public AgeDistribution(List<AgeEntry> entries, int tenuringThreshold,
                           int maxTenuringThreshold, long desiredSurvivorSize,
                           long survivorCapacity) {
        this.entries = entries;
        this.tenuringThreshold = tenuringThreshold;
        this.maxTenuringThreshold = maxTenuringThreshold;
        this.desiredSurvivorSize = desiredSurvivorSize;
        this.survivorCapacity = survivorCapacity;
    }

    public List<AgeEntry> entries() { return entries; }
    public int tenuringThreshold() { return tenuringThreshold; }
    public int maxTenuringThreshold() { return maxTenuringThreshold; }
    public long desiredSurvivorSize() { return desiredSurvivorSize; }
    public long survivorCapacity() { return survivorCapacity; }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof AgeDistribution)) return false;
        AgeDistribution that = (AgeDistribution) o;
        return tenuringThreshold == that.tenuringThreshold
                && maxTenuringThreshold == that.maxTenuringThreshold
                && desiredSurvivorSize == that.desiredSurvivorSize
                && survivorCapacity == that.survivorCapacity
                && java.util.Objects.equals(entries, that.entries);
    }

    @Override
    public int hashCode() {
        return java.util.Objects.hash(entries, tenuringThreshold, maxTenuringThreshold,
                desiredSurvivorSize, survivorCapacity);
    }

    @Override
    public String toString() {
        return "AgeDistribution[entries=" + entries + ", tenuringThreshold=" + tenuringThreshold
                + ", maxTenuringThreshold=" + maxTenuringThreshold
                + ", desiredSurvivorSize=" + desiredSurvivorSize
                + ", survivorCapacity=" + survivorCapacity + "]";
    }

    /**
     * A single age bucket: objects of this age in the survivor space.
     */
    public static final class AgeEntry {
        private final int age;
        private final long bytes;
        private final long cumulativeBytes;

        public AgeEntry(int age, long bytes, long cumulativeBytes) {
            this.age = age;
            this.bytes = bytes;
            this.cumulativeBytes = cumulativeBytes;
        }

        public int age() { return age; }
        public long bytes() { return bytes; }
        public long cumulativeBytes() { return cumulativeBytes; }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (!(o instanceof AgeEntry)) return false;
            AgeEntry that = (AgeEntry) o;
            return age == that.age
                    && bytes == that.bytes
                    && cumulativeBytes == that.cumulativeBytes;
        }

        @Override
        public int hashCode() {
            return java.util.Objects.hash(age, bytes, cumulativeBytes);
        }

        @Override
        public String toString() {
            return "AgeEntry[age=" + age + ", bytes=" + bytes
                    + ", cumulativeBytes=" + cumulativeBytes + "]";
        }
    }
}
