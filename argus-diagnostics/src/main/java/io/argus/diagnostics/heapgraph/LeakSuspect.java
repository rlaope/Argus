package io.argus.diagnostics.heapgraph;

/**
 * A single leak-suspect accumulation point: an object (or its dominating class)
 * that retains a disproportionate share of the heap.
 *
 * @param objectIndex   the dominator object's index
 * @param className     the dominator object's class name
 * @param retainedBytes bytes retained by this dominator
 * @param heapPercent   retained bytes as a percentage of total reachable heap
 * @param dominatedCount number of objects dominated (subtree size)
 */
public final class LeakSuspect {
    private final int objectIndex;
    private final String className;
    private final long retainedBytes;
    private final double heapPercent;
    private final int dominatedCount;

    public LeakSuspect(int objectIndex, String className, long retainedBytes,
                       double heapPercent, int dominatedCount) {
        this.objectIndex = objectIndex;
        this.className = className;
        this.retainedBytes = retainedBytes;
        this.heapPercent = heapPercent;
        this.dominatedCount = dominatedCount;
    }

    public int objectIndex() { return objectIndex; }
    public String className() { return className; }
    public long retainedBytes() { return retainedBytes; }
    public double heapPercent() { return heapPercent; }
    public int dominatedCount() { return dominatedCount; }

    @Override
    public String toString() {
        return "LeakSuspect[" + className + " retains " + retainedBytes
                + " bytes (" + String.format("%.1f%%", heapPercent) + ")]";
    }
}
