package io.argus.cli.profiler;

import io.argus.cli.profiler.AllocationProfiler.AllocationProfile;
import io.argus.cli.profiler.AllocationProfiler.AllocationSite;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for allocation aggregation logic used by {@link AllocationProfiler}.
 *
 * <p>Since JFR recording files require a live JVM, the aggregation logic is
 * tested via a synthetic {@link AllocationAggregator} helper that mirrors the
 * internal map-reduce done in {@code AllocationProfiler.analyze()}.
 */
class AllocationProfilerTest {

    // -------------------------------------------------------------------------
    // AllocationAggregator — test-local helper matching production logic
    // -------------------------------------------------------------------------

    /**
     * Synthetic aggregator that mirrors the production aggregation in
     * {@link AllocationProfiler} without needing an actual JFR file.
     */
    static final class AllocationAggregator {

        private final Map<String, long[]> sites = new HashMap<>(); // key -> [bytes, count]
        private long totalBytes = 0;
        private long totalAllocations = 0;
        private final double durationSec;

        AllocationAggregator(double durationSec) {
            this.durationSec = durationSec;
        }

        void add(String className, String methodName, int line, long bytes) {
            String key = className + "." + methodName + ":" + line;
            sites.computeIfAbsent(key, k -> new long[2]);
            sites.get(key)[0] += bytes;
            sites.get(key)[1]++;
            totalBytes += bytes;
            totalAllocations++;
        }

        AllocationProfile build(int topN) {
            double dur = durationSec > 0 ? durationSec : 1.0;
            List<AllocationSite> result = new ArrayList<>();
            for (Map.Entry<String, long[]> entry : sites.entrySet()) {
                String key = entry.getKey();
                long[] agg = entry.getValue();
                // parse className.methodName:line from key
                int colonIdx = key.lastIndexOf(':');
                int dotIdx = key.lastIndexOf('.', colonIdx);
                String className = key.substring(0, dotIdx);
                String methodName = key.substring(dotIdx + 1, colonIdx);
                int lineNumber = Integer.parseInt(key.substring(colonIdx + 1));
                double bytesPerSec = agg[0] / dur;
                result.add(new AllocationSite(className, methodName, lineNumber, agg[0], agg[1], bytesPerSec));
            }
            result.sort((a, b) -> Long.compare(b.totalBytes(), a.totalBytes()));
            List<AllocationSite> top = result.size() <= topN ? result : result.subList(0, topN);
            return new AllocationProfile(new ArrayList<>(top), totalBytes, totalAllocations, dur);
        }
    }

    // -------------------------------------------------------------------------
    // Tests
    // -------------------------------------------------------------------------

    @Test
    void sortsByTotalBytesDescending() {
        AllocationAggregator agg = new AllocationAggregator(10.0);
        agg.add("com.example.Foo", "process", 42, 1_000);
        agg.add("com.example.Bar", "run", 10, 5_000);
        agg.add("com.example.Baz", "compute", 77, 2_000);

        AllocationProfile profile = agg.build(10);

        List<AllocationSite> sites = profile.sites();
        assertEquals(3, sites.size());
        assertEquals(5_000, sites.get(0).totalBytes(), "Bar should be first (most bytes)");
        assertEquals(2_000, sites.get(1).totalBytes(), "Baz should be second");
        assertEquals(1_000, sites.get(2).totalBytes(), "Foo should be last");
    }

    @Test
    void topNLimitsResults() {
        AllocationAggregator agg = new AllocationAggregator(10.0);
        for (int i = 0; i < 20; i++) {
            agg.add("com.example.Class" + i, "method", i, (long) (i + 1) * 1000);
        }

        AllocationProfile profile = agg.build(5);

        assertEquals(5, profile.sites().size(), "Should limit to top 5");
    }

    @Test
    void percentageCalculation() {
        AllocationAggregator agg = new AllocationAggregator(10.0);
        agg.add("com.example.Hot", "alloc", 1, 700);
        agg.add("com.example.Cold", "alloc", 2, 300);

        AllocationProfile profile = agg.build(10);

        assertEquals(1000, profile.totalBytes());
        AllocationSite top = profile.sites().getFirst();
        assertEquals(700, top.totalBytes());

        double pct = (double) top.totalBytes() / profile.totalBytes() * 100;
        assertEquals(70.0, pct, 0.001, "Hot site should be 70% of total");
    }

    @Test
    void bytesPerSecCalculation() {
        double duration = 5.0;
        AllocationAggregator agg = new AllocationAggregator(duration);
        agg.add("com.example.Foo", "bar", 10, 1_000_000); // 1MB in 5s = 200KB/s

        AllocationProfile profile = agg.build(10);

        AllocationSite site = profile.sites().getFirst();
        assertEquals(200_000.0, site.bytesPerSec(), 1.0, "Rate should be 200KB/s");
    }

    @Test
    void aggregatesSameSiteMultipleTimes() {
        AllocationAggregator agg = new AllocationAggregator(1.0);
        agg.add("com.example.Foo", "bar", 42, 100);
        agg.add("com.example.Foo", "bar", 42, 200);
        agg.add("com.example.Foo", "bar", 42, 300);

        AllocationProfile profile = agg.build(10);

        assertEquals(1, profile.sites().size(), "Same site should be aggregated into one");
        assertEquals(600, profile.sites().getFirst().totalBytes());
        assertEquals(3, profile.sites().getFirst().allocationCount());
    }

    @Test
    void totalBytesAndAllocationsAccumulate() {
        AllocationAggregator agg = new AllocationAggregator(1.0);
        agg.add("com.example.A", "m", 1, 100);
        agg.add("com.example.B", "m", 2, 200);
        agg.add("com.example.C", "m", 3, 300);

        AllocationProfile profile = agg.build(10);

        assertEquals(600, profile.totalBytes());
        assertEquals(3, profile.totalAllocations());
    }

    @Test
    void emptyAggregatorProducesEmptyProfile() {
        AllocationAggregator agg = new AllocationAggregator(30.0);
        AllocationProfile profile = agg.build(10);

        assertTrue(profile.sites().isEmpty());
        assertEquals(0, profile.totalBytes());
        assertEquals(0, profile.totalAllocations());
    }

    @Test
    void topNLargerThanResultsReturnsAll() {
        AllocationAggregator agg = new AllocationAggregator(1.0);
        agg.add("com.example.A", "x", 1, 100);
        agg.add("com.example.B", "x", 2, 200);

        AllocationProfile profile = agg.build(100);

        assertEquals(2, profile.sites().size());
    }

    @Test
    void allocationSiteRecordFields() {
        AllocationAggregator agg = new AllocationAggregator(2.0);
        agg.add("com.example.Service", "process", 88, 400);

        AllocationProfile profile = agg.build(10);
        AllocationSite site = profile.sites().getFirst();

        assertEquals("com.example.Service", site.className());
        assertEquals("process", site.methodName());
        assertEquals(88, site.lineNumber());
        assertEquals(400, site.totalBytes());
        assertEquals(1, site.allocationCount());
        assertEquals(200.0, site.bytesPerSec(), 0.01);
    }
}
