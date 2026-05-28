package io.argus.instrument;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MonitorAggregatorTest {

    private static final long MS = 1_000_000L; // nanos per millisecond

    @Test
    void snapshot_emptyWindowHasZeroCounts() {
        MonitorAggregator agg = new MonitorAggregator();
        CaptureEvent e = agg.snapshotAndReset(1L, "com.acme.Foo", "bar");
        String json = e.toJson();
        assertEquals(CaptureEvent.Kind.MONITOR, e.kind());
        assertTrue(json.contains("\"count\":0"), json);
        assertTrue(json.contains("\"success\":0"), json);
        assertTrue(json.contains("\"failure\":0"), json);
        assertTrue(json.contains("\"avgMs\":0.000"), json);
        assertTrue(json.contains("\"maxMs\":0.000"), json);
        assertTrue(json.contains("\"clazz\":\"com.acme.Foo\""), json);
        assertTrue(json.contains("\"method\":\"bar\""), json);
    }

    @Test
    void snapshot_countsSuccessAndFailureSeparately() {
        MonitorAggregator agg = new MonitorAggregator();
        agg.record(true, 2 * MS);
        agg.record(true, 4 * MS);
        agg.record(false, 6 * MS);

        CaptureEvent e = agg.snapshotAndReset(10L, "C", "m");
        String json = e.toJson();
        assertTrue(json.contains("\"count\":3"), json);
        assertTrue(json.contains("\"success\":2"), json);
        assertTrue(json.contains("\"failure\":1"), json);
        // avg = (2+4+6)/3 = 4ms
        assertTrue(json.contains("\"avgMs\":4.000"), json);
        // max = 6ms
        assertTrue(json.contains("\"maxMs\":6.000"), json);
    }

    @Test
    void snapshot_resetsAfterEachWindow() {
        MonitorAggregator agg = new MonitorAggregator();
        agg.record(true, 5 * MS);
        agg.record(false, 7 * MS);

        CaptureEvent first = agg.snapshotAndReset(1L, "C", "m");
        assertTrue(first.toJson().contains("\"count\":2"), first.toJson());

        // After a snapshot the counters are zeroed: the next snapshot is an empty window.
        CaptureEvent second = agg.snapshotAndReset(2L, "C", "m");
        String json = second.toJson();
        assertTrue(json.contains("\"count\":0"), json);
        assertTrue(json.contains("\"success\":0"), json);
        assertTrue(json.contains("\"failure\":0"), json);
        assertTrue(json.contains("\"maxMs\":0.000"), json);
    }

    @Test
    void record_negativeNanosClampedToZero() {
        MonitorAggregator agg = new MonitorAggregator();
        agg.record(true, -100L);
        CaptureEvent e = agg.snapshotAndReset(1L, "C", "m");
        String json = e.toJson();
        assertTrue(json.contains("\"count\":1"), json);
        assertTrue(json.contains("\"avgMs\":0.000"), json);
        assertTrue(json.contains("\"maxMs\":0.000"), json);
    }

    @Test
    void snapshot_maxMsIsTheLargestRecorded() {
        MonitorAggregator agg = new MonitorAggregator();
        agg.record(true, 1 * MS);
        agg.record(true, 100 * MS);
        agg.record(true, 50 * MS);
        CaptureEvent e = agg.snapshotAndReset(1L, "C", "m");
        assertTrue(e.toJson().contains("\"maxMs\":100.000"), e.toJson());
    }
}
