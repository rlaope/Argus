package io.argus.cli.command;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class PoolAdvisorMathTest {

    @Test
    void recommendApplies1_5xScaleForTypicalPools() {
        assertEquals(45, PoolAdviseHandler.recommendSize(30));
        assertEquals(15, PoolAdviseHandler.recommendSize(10));
    }

    @Test
    void recommendAppliesFloorForTinyPools() {
        assertEquals(PoolAdviseHandler.MIN_RECOMMENDED,
                PoolAdviseHandler.recommendSize(0));
        assertEquals(PoolAdviseHandler.MIN_RECOMMENDED,
                PoolAdviseHandler.recommendSize(1));
        assertEquals(PoolAdviseHandler.MIN_RECOMMENDED,
                PoolAdviseHandler.recommendSize(2));
    }

    @Test
    void percentilePicksUpperEndOfTheDistribution() {
        List<Integer> series = List.of(1, 2, 3, 4, 5, 6, 7, 8, 9, 10);
        assertEquals(10, PoolAdviseHandler.percentile(series, 99));
        assertEquals(10, PoolAdviseHandler.percentile(series, 100));
        assertEquals(5, PoolAdviseHandler.percentile(series, 50));
    }

    @Test
    void percentileEmptyReturnsZero() {
        assertEquals(0, PoolAdviseHandler.percentile(List.of(), 99));
    }

    @Test
    void parseWindowAcceptsUnitSuffixes() {
        assertEquals(5_000L, PoolAdviseHandler.parseWindow("5s", 0L));
        assertEquals(250L, PoolAdviseHandler.parseWindow("250ms", 0L));
        assertEquals(60_000L, PoolAdviseHandler.parseWindow("1m", 0L));
    }

    @Test
    void parseWindowFallsBackOnGarbage() {
        assertEquals(123L, PoolAdviseHandler.parseWindow("not-a-window", 123L));
        assertEquals(123L, PoolAdviseHandler.parseWindow("", 123L));
        assertEquals(123L, PoolAdviseHandler.parseWindow(null, 123L));
    }

    @Test
    void prefixOfRecognisesCommonPools() {
        assertEquals("http-nio-8080", PoolAdviseHandler.prefixOf("http-nio-8080-exec-23"));
        assertEquals("ForkJoinPool.commonPool", PoolAdviseHandler.prefixOf("ForkJoinPool.commonPool-worker-3"));
        assertEquals("pool-1", PoolAdviseHandler.prefixOf("pool-1-thread-4"));
        assertEquals("scheduling", PoolAdviseHandler.prefixOf("scheduling-1"));
    }

    @Test
    void prefixOfReturnsNullForUngroupableNames() {
        assertNull(PoolAdviseHandler.prefixOf(null));
        assertNull(PoolAdviseHandler.prefixOf(""));
        assertNull(PoolAdviseHandler.prefixOf("main"));
        assertNull(PoolAdviseHandler.prefixOf("Reference Handler"));
    }

    @Test
    void prefixOfHandlesLongNumericSuffixesAndCustomFactories() {
        // System.nanoTime() suffixes overflow Integer.parseInt — must work
        assertEquals("argus-qa-worker", PoolAdviseHandler.prefixOf("argus-qa-worker-123456789012345678"));
        // hex-style identifier should NOT match the trailing-digit fallback (not all digits)
        assertNull(PoolAdviseHandler.prefixOf("custom-bridge-abc123"));
        // Empty trailing token after dash
        assertNull(PoolAdviseHandler.prefixOf("trailing-"));
    }

    @Test
    void prefixOfFiltersJdkAndContainerInternalPools() {
        assertNull(PoolAdviseHandler.prefixOf("RMI TCP Accept-0"));
        assertNull(PoolAdviseHandler.prefixOf("RMI Scheduler(0)"));
        assertNull(PoolAdviseHandler.prefixOf("GC Thread#3"));
        assertNull(PoolAdviseHandler.prefixOf("Catalina-utility-1"));
        assertNull(PoolAdviseHandler.prefixOf("G1 Conc#0"));
        assertNull(PoolAdviseHandler.prefixOf("ParGC Thread#0"));
    }
}
