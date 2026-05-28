package io.argus.instrument;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class CaptureCapsTest {

    // --- defaults ---

    @Test
    void defaults_haveDocumentedValues() {
        CaptureCaps d = CaptureCaps.defaults();
        assertEquals(100, d.maxHits());
        assertEquals(60_000L, d.timeoutMs());
        assertEquals(256, d.maxValueLen());
        assertEquals(16, d.maxArgs());
        assertEquals(20, d.maxDepth());
        assertEquals(500, d.maxEventsPerSecond());
    }

    @Test
    void withSetters_areImmutableCopies() {
        CaptureCaps d = CaptureCaps.defaults();
        CaptureCaps copy = d.withMaxHits(7);
        assertEquals(100, d.maxHits(), "original must be unchanged");
        assertEquals(7, copy.maxHits());
        // unrelated fields are preserved across a copy.
        assertEquals(d.timeoutMs(), copy.timeoutMs());
        assertEquals(d.maxValueLen(), copy.maxValueLen());
    }

    // --- maxHits clamp: <0 -> 0 (unlimited), >1_000_000 -> 1_000_000 ---

    @Test
    void maxHits_negativeClampsToZero() {
        assertEquals(0, CaptureCaps.defaults().withMaxHits(-1).maxHits());
        assertEquals(0, CaptureCaps.defaults().withMaxHits(Integer.MIN_VALUE).maxHits());
    }

    @Test
    void maxHits_zeroStaysZero() {
        assertEquals(0, CaptureCaps.defaults().withMaxHits(0).maxHits());
    }

    @Test
    void maxHits_withinRangeUnchanged() {
        assertEquals(1, CaptureCaps.defaults().withMaxHits(1).maxHits());
        assertEquals(1_000_000, CaptureCaps.defaults().withMaxHits(1_000_000).maxHits());
    }

    @Test
    void maxHits_aboveMaxClampsToMax() {
        assertEquals(1_000_000, CaptureCaps.defaults().withMaxHits(1_000_001).maxHits());
        assertEquals(1_000_000, CaptureCaps.defaults().withMaxHits(Integer.MAX_VALUE).maxHits());
    }

    // --- timeoutMs clamp: <=0 -> 60000, >3_600_000 -> 3_600_000 ---

    @Test
    void timeoutMs_zeroOrNegativeResetsToDefault() {
        assertEquals(60_000L, CaptureCaps.defaults().withTimeoutMs(0).timeoutMs());
        assertEquals(60_000L, CaptureCaps.defaults().withTimeoutMs(-5).timeoutMs());
        assertEquals(60_000L, CaptureCaps.defaults().withTimeoutMs(Long.MIN_VALUE).timeoutMs());
    }

    @Test
    void timeoutMs_withinRangeUnchanged() {
        assertEquals(1L, CaptureCaps.defaults().withTimeoutMs(1).timeoutMs());
        assertEquals(3_600_000L, CaptureCaps.defaults().withTimeoutMs(3_600_000L).timeoutMs());
    }

    @Test
    void timeoutMs_aboveMaxClampsToMax() {
        assertEquals(3_600_000L, CaptureCaps.defaults().withTimeoutMs(3_600_001L).timeoutMs());
        assertEquals(3_600_000L, CaptureCaps.defaults().withTimeoutMs(Long.MAX_VALUE).timeoutMs());
    }

    // --- maxValueLen clamp: <=0 -> 256, >8192 -> 8192 ---

    @Test
    void maxValueLen_zeroOrNegativeResetsToDefault() {
        assertEquals(256, CaptureCaps.defaults().withMaxValueLen(0).maxValueLen());
        assertEquals(256, CaptureCaps.defaults().withMaxValueLen(-1).maxValueLen());
    }

    @Test
    void maxValueLen_withinRangeUnchanged() {
        assertEquals(1, CaptureCaps.defaults().withMaxValueLen(1).maxValueLen());
        assertEquals(8_192, CaptureCaps.defaults().withMaxValueLen(8_192).maxValueLen());
    }

    @Test
    void maxValueLen_aboveMaxClampsToMax() {
        assertEquals(8_192, CaptureCaps.defaults().withMaxValueLen(8_193).maxValueLen());
        assertEquals(8_192, CaptureCaps.defaults().withMaxValueLen(Integer.MAX_VALUE).maxValueLen());
    }

    // --- maxArgs clamp: <0 -> 0, >255 -> 255 ---

    @Test
    void maxArgs_negativeClampsToZero() {
        assertEquals(0, CaptureCaps.defaults().withMaxArgs(-1).maxArgs());
    }

    @Test
    void maxArgs_zeroStaysZero() {
        assertEquals(0, CaptureCaps.defaults().withMaxArgs(0).maxArgs());
    }

    @Test
    void maxArgs_withinRangeUnchanged() {
        assertEquals(1, CaptureCaps.defaults().withMaxArgs(1).maxArgs());
        assertEquals(255, CaptureCaps.defaults().withMaxArgs(255).maxArgs());
    }

    @Test
    void maxArgs_aboveMaxClampsToMax() {
        assertEquals(255, CaptureCaps.defaults().withMaxArgs(256).maxArgs());
        assertEquals(255, CaptureCaps.defaults().withMaxArgs(Integer.MAX_VALUE).maxArgs());
    }

    // --- maxDepth clamp: <=0 -> 1, >256 -> 256 ---

    @Test
    void maxDepth_zeroOrNegativeResetsToOne() {
        assertEquals(1, CaptureCaps.defaults().withMaxDepth(0).maxDepth());
        assertEquals(1, CaptureCaps.defaults().withMaxDepth(-3).maxDepth());
    }

    @Test
    void maxDepth_withinRangeUnchanged() {
        assertEquals(1, CaptureCaps.defaults().withMaxDepth(1).maxDepth());
        assertEquals(256, CaptureCaps.defaults().withMaxDepth(256).maxDepth());
    }

    @Test
    void maxDepth_aboveMaxClampsToMax() {
        assertEquals(256, CaptureCaps.defaults().withMaxDepth(257).maxDepth());
        assertEquals(256, CaptureCaps.defaults().withMaxDepth(Integer.MAX_VALUE).maxDepth());
    }

    // --- maxEventsPerSecond clamp: <=0 -> 0, >100000 -> 100000 ---

    @Test
    void maxEventsPerSecond_zeroOrNegativeBecomesZero() {
        assertEquals(0, CaptureCaps.defaults().withMaxEventsPerSecond(0).maxEventsPerSecond());
        assertEquals(0, CaptureCaps.defaults().withMaxEventsPerSecond(-1).maxEventsPerSecond());
    }

    @Test
    void maxEventsPerSecond_withinRangeUnchanged() {
        assertEquals(1, CaptureCaps.defaults().withMaxEventsPerSecond(1).maxEventsPerSecond());
        assertEquals(100_000, CaptureCaps.defaults().withMaxEventsPerSecond(100_000).maxEventsPerSecond());
    }

    @Test
    void maxEventsPerSecond_aboveMaxClampsToMax() {
        assertEquals(100_000, CaptureCaps.defaults().withMaxEventsPerSecond(100_001).maxEventsPerSecond());
        assertEquals(100_000, CaptureCaps.defaults().withMaxEventsPerSecond(Integer.MAX_VALUE).maxEventsPerSecond());
    }

    // --- Builder ---

    @Test
    void builder_appliesAndClampsEachField() {
        CaptureCaps c = new CaptureCaps.Builder()
                .maxHits(-1)                 // -> 0
                .timeoutMs(0)                // -> 60000
                .maxValueLen(99_999)         // -> 8192
                .maxArgs(1000)               // -> 255
                .maxDepth(0)                 // -> 1
                .maxEventsPerSecond(-5)      // -> 0
                .build();
        assertEquals(0, c.maxHits());
        assertEquals(60_000L, c.timeoutMs());
        assertEquals(8_192, c.maxValueLen());
        assertEquals(255, c.maxArgs());
        assertEquals(1, c.maxDepth());
        assertEquals(0, c.maxEventsPerSecond());
    }

    @Test
    void builder_emptyReturnsDefaults() {
        CaptureCaps c = new CaptureCaps.Builder().build();
        CaptureCaps d = CaptureCaps.defaults();
        assertEquals(d.maxHits(), c.maxHits());
        assertEquals(d.timeoutMs(), c.timeoutMs());
        assertEquals(d.maxValueLen(), c.maxValueLen());
        assertEquals(d.maxArgs(), c.maxArgs());
        assertEquals(d.maxDepth(), c.maxDepth());
        assertEquals(d.maxEventsPerSecond(), c.maxEventsPerSecond());
    }
}
