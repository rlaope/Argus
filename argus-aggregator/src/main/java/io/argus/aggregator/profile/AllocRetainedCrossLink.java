package io.argus.aggregator.profile;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Joins allocation-profile hot types with heap dominator / retained-size data to
 * answer the JVM-deep question "the types we allocate the most — how much heap do
 * they actually <em>retain</em>?".
 *
 * <p>This cross-link has no host-profiler / eBPF equivalent: allocation rate alone
 * (what a flame graph or perf counter shows) does not tell you whether those
 * allocations are short-lived churn or long-lived retention. Joining the
 * async-profiler / JFR {@code alloc} hot types against the dominator-tree retained
 * sizes (from {@code argus-diagnostics} heapgraph) attributes "allocation site/type
 * X retains N bytes", which only a JVM-object-graph-aware tool can produce.
 *
 * <p>Pure and dependency-free: it operates on plain inputs (a list of
 * {@link AllocType} hot types and a {@code className -> retainedBytes} map) so it is
 * unit-testable without a live JVM or heap dump. The glue that extracts those inputs
 * from {@code AllocationAnalyzer.AllocationAnalysisResult} (server) and
 * {@code HeapGraphAnalysis.retainedByClass} (diagnostics) is a thin caller-side
 * adapter and is the integration follow-up.
 */
public final class AllocRetainedCrossLink {

    private AllocRetainedCrossLink() {}

    /**
     * A hot allocation type from an allocation profile: the type's fully-qualified
     * name and the bytes/objects allocated to it over the profiling window.
     */
    public static final class AllocType {
        private final String className;
        private final long allocatedBytes;
        private final long allocationCount;

        public AllocType(String className, long allocatedBytes, long allocationCount) {
            this.className = className;
            this.allocatedBytes = allocatedBytes;
            this.allocationCount = allocationCount;
        }

        public String className() { return className; }
        public long allocatedBytes() { return allocatedBytes; }
        public long allocationCount() { return allocationCount; }
    }

    /**
     * One cross-linked entry: an allocation hot type attributed to the heap it
     * retains, with the ratio of retained-to-allocated bytes (a churn vs. retention
     * signal — a high ratio means the allocations stick around).
     */
    public static final class Entry {
        private final String className;
        private final long allocatedBytes;
        private final long allocationCount;
        private final long retainedBytes;

        public Entry(String className, long allocatedBytes, long allocationCount, long retainedBytes) {
            this.className = className;
            this.allocatedBytes = allocatedBytes;
            this.allocationCount = allocationCount;
            this.retainedBytes = retainedBytes;
        }

        public String className() { return className; }
        public long allocatedBytes() { return allocatedBytes; }
        public long allocationCount() { return allocationCount; }
        public long retainedBytes() { return retainedBytes; }

        /**
         * Retained-to-allocated ratio. {@code 0.0} when nothing was allocated.
         * A value near 1.0 means the allocated bytes are largely still retained;
         * a value near 0.0 means short-lived churn.
         */
        public double retainedRatio() {
            return allocatedBytes <= 0 ? 0.0 : (double) retainedBytes / (double) allocatedBytes;
        }

        /** Human-readable attribution line, e.g. for a report row. */
        public String describe() {
            return String.format("allocation type %s retains %d bytes (allocated %d bytes)",
                    className, retainedBytes, allocatedBytes);
        }
    }

    /**
     * Cross-links allocation hot types with their retained sizes.
     *
     * <p>For each hot allocation type, looks up its retained size in
     * {@code retainedByType} (0 when the type retains nothing reachable, e.g. pure
     * churn). The result is sorted by retained bytes descending, then by allocated
     * bytes descending, so the type that retains the most heap leads — which is the
     * actionable one for a leak / memory-pressure investigation.
     *
     * @param hotTypes       allocation-profile hot types (non-null; null/blank-named entries skipped)
     * @param retainedByType {@code className -> retainedBytes} from heap dominator analysis (non-null)
     * @return cross-linked entries, sorted by retained bytes (desc), then allocated bytes (desc)
     */
    public static List<Entry> link(List<AllocType> hotTypes, Map<String, Long> retainedByType) {
        if (hotTypes == null) {
            throw new IllegalArgumentException("hotTypes must not be null");
        }
        if (retainedByType == null) {
            throw new IllegalArgumentException("retainedByType must not be null");
        }
        List<Entry> entries = new ArrayList<>(hotTypes.size());
        for (AllocType t : hotTypes) {
            if (t == null || t.className() == null || t.className().isBlank()) {
                continue;
            }
            Long retained = retainedByType.get(t.className());
            long retainedBytes = retained == null ? 0L : retained;
            entries.add(new Entry(t.className(), t.allocatedBytes(), t.allocationCount(), retainedBytes));
        }
        entries.sort((a, b) -> {
            int byRetained = Long.compare(b.retainedBytes(), a.retainedBytes());
            if (byRetained != 0) {
                return byRetained;
            }
            return Long.compare(b.allocatedBytes(), a.allocatedBytes());
        });
        return entries;
    }
}
