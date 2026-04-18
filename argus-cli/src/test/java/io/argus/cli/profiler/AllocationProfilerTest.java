package io.argus.cli.profiler;

import io.argus.cli.profiler.AllocationProfiler.AllocatedType;
import io.argus.cli.profiler.AllocationProfiler.AllocationByClass;
import io.argus.cli.profiler.AllocationProfiler.AllocationProfile;
import io.argus.cli.profiler.AllocationProfiler.AllocationSite;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
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

    // -------------------------------------------------------------------------
    // --by=class aggregation — mirrors AllocationProfiler.analyzeByClass() logic
    // -------------------------------------------------------------------------

    static final class ClassAggregator {
        private final Map<String, long[]> agg = new HashMap<>();
        private long totalBytes = 0;
        private final double durationSec;

        ClassAggregator(double durationSec) { this.durationSec = durationSec; }

        void add(String className, long bytes) {
            agg.computeIfAbsent(className, k -> new long[2]);
            agg.get(className)[0] += bytes;
            agg.get(className)[1]++;
            totalBytes += bytes;
        }

        AllocationByClass build() {
            double dur = durationSec > 0 ? durationSec : 1.0;
            List<AllocatedType> rows = new ArrayList<>();
            for (Map.Entry<String, long[]> e : agg.entrySet()) {
                long bytes = e.getValue()[0];
                long count = e.getValue()[1];
                rows.add(new AllocatedType(e.getKey(), bytes, count, bytes / dur));
            }
            rows.sort((a, b) -> Long.compare(b.totalBytes(), a.totalBytes()));
            return new AllocationByClass(rows, totalBytes, dur);
        }
    }

    @Test
    void byClassSortsByTotalBytesDescending() {
        ClassAggregator agg = new ClassAggregator(5.0);
        agg.add("byte[]", 1_000_000);
        agg.add("java.util.HashMap$Node", 500_000);
        agg.add("java.lang.String", 250_000);
        agg.add("byte[]", 200_000); // accumulates with first byte[]

        AllocationByClass result = agg.build();

        assertEquals(3, result.sites().size(), "byte[] entries coalesce");
        assertEquals("byte[]", result.sites().get(0).className());
        assertEquals(1_200_000, result.sites().get(0).totalBytes());
        assertEquals(2, result.sites().get(0).allocationCount());
        assertEquals(1_950_000, result.totalBytes());
    }

    @Test
    void byClassRateCalculation() {
        ClassAggregator agg = new ClassAggregator(2.0);
        agg.add("java.lang.String", 400_000); // 200 KB/s

        AllocationByClass result = agg.build();
        assertEquals(200_000, result.sites().getFirst().bytesPerSec(), 1);
    }

    @Test
    void byClassEmpty() {
        AllocationByClass r = new ClassAggregator(10.0).build();
        assertTrue(r.sites().isEmpty());
        assertEquals(0, r.totalBytes());
    }

    // -------------------------------------------------------------------------
    // Folded stack aggregation — mirrors AllocationProfiler.analyzeFoldedStacks()
    // -------------------------------------------------------------------------

    /**
     * Mirror of the folded-stack building in {@link AllocationProfiler#analyzeFoldedStacks}.
     * Each "event" is a list of frames ordered leaf-first (matching JFR), with an
     * allocated byte count.
     */
    static Map<String, Long> foldStacks(List<Object[]> events) {
        Map<String, Long> folded = new LinkedHashMap<>();
        for (Object[] ev : events) {
            @SuppressWarnings("unchecked")
            List<String> leafFirstFrames = (List<String>) ev[0];
            long size = (long) ev[1];
            if (size <= 0 || leafFirstFrames.isEmpty()) continue;
            StringBuilder sb = new StringBuilder();
            for (int i = leafFirstFrames.size() - 1; i >= 0; i--) {
                if (sb.length() > 0) sb.append(';');
                sb.append(leafFirstFrames.get(i));
            }
            folded.merge(sb.toString(), size, Long::sum);
        }
        return folded.entrySet().stream()
                .sorted(Map.Entry.<String, Long>comparingByValue(Comparator.reverseOrder()))
                .collect(LinkedHashMap::new, (m, e) -> m.put(e.getKey(), e.getValue()), LinkedHashMap::putAll);
    }

    @Test
    void foldedStacksReorderLeafLast() {
        List<Object[]> events = new ArrayList<>();
        events.add(new Object[]{List.of("Leaf.alloc", "Mid.call", "Root.main"), 1000L});

        Map<String, Long> folded = foldStacks(events);

        assertEquals(1, folded.size());
        assertTrue(folded.containsKey("Root.main;Mid.call;Leaf.alloc"),
                "expected root-first leaf-last stack, got " + folded.keySet());
    }

    @Test
    void foldedStacksMergeIdenticalStacks() {
        List<Object[]> events = new ArrayList<>();
        events.add(new Object[]{List.of("byte[].<init>", "Loader.load", "App.main"), 500L});
        events.add(new Object[]{List.of("byte[].<init>", "Loader.load", "App.main"), 300L});

        Map<String, Long> folded = foldStacks(events);

        assertEquals(1, folded.size());
        assertEquals(800L, folded.values().iterator().next());
    }

    @Test
    void foldedStacksSortedByBytesDesc() {
        List<Object[]> events = new ArrayList<>();
        events.add(new Object[]{List.of("A.a"), 100L});
        events.add(new Object[]{List.of("B.b"), 500L});
        events.add(new Object[]{List.of("C.c"), 250L});

        Map<String, Long> folded = foldStacks(events);

        List<Long> values = new ArrayList<>(folded.values());
        assertEquals(500L, values.get(0));
        assertEquals(250L, values.get(1));
        assertEquals(100L, values.get(2));
    }

    @Test
    void foldedStacksSkipEmptyOrZero() {
        List<Object[]> events = new ArrayList<>();
        events.add(new Object[]{List.<String>of(), 1000L});        // no frames → skip
        events.add(new Object[]{List.of("A.a"), 0L});              // zero bytes → skip
        events.add(new Object[]{List.of("B.b"), 500L});            // keep

        Map<String, Long> folded = foldStacks(events);

        assertEquals(1, folded.size());
        assertEquals(500L, folded.get("B.b"));
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
