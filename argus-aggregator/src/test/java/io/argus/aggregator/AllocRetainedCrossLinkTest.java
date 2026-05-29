package io.argus.aggregator;

import io.argus.aggregator.profile.AllocRetainedCrossLink;
import io.argus.aggregator.profile.AllocRetainedCrossLink.AllocType;
import io.argus.aggregator.profile.AllocRetainedCrossLink.Entry;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AllocRetainedCrossLinkTest {

    @Test
    void topAllocationType_crossLinksToItsRetainedSize_sortedByRetained() {
        // Synthetic alloc profile: byte[] allocates the most, but char[] retains more.
        List<AllocType> hot = List.of(
                new AllocType("byte[]", 10_000_000L, 5_000L),
                new AllocType("char[]", 4_000_000L, 2_000L),
                new AllocType("java.lang.String", 1_000_000L, 8_000L));
        // Synthetic dominator/retained map: char[] retains the most heap.
        Map<String, Long> retained = Map.of(
                "char[]", 9_000_000L,
                "byte[]", 1_000_000L,
                "java.lang.String", 500_000L);

        List<Entry> entries = AllocRetainedCrossLink.link(hot, retained);

        assertEquals(3, entries.size());
        // Sorted by retained bytes desc -> char[] leads despite lower alloc bytes.
        Entry top = entries.get(0);
        assertEquals("char[]", top.className());
        assertEquals(9_000_000L, top.retainedBytes());
        assertEquals(4_000_000L, top.allocatedBytes());
        assertEquals(2_000L, top.allocationCount());

        assertEquals("byte[]", entries.get(1).className());
        assertEquals("java.lang.String", entries.get(2).className());
    }

    @Test
    void typeWithNoRetainedEntry_isPureChurn_zeroRetained() {
        List<AllocType> hot = List.of(new AllocType("int[]", 3_000_000L, 1_000L));
        Map<String, Long> retained = Map.of(); // int[] retains nothing reachable

        List<Entry> entries = AllocRetainedCrossLink.link(hot, retained);

        assertEquals(1, entries.size());
        Entry e = entries.get(0);
        assertEquals(0L, e.retainedBytes());
        assertEquals(0.0, e.retainedRatio());
        assertTrue(e.describe().contains("int[]"));
        assertTrue(e.describe().contains("retains 0 bytes"));
    }

    @Test
    void retainedRatio_signalsRetentionVsChurn() {
        List<AllocType> hot = List.of(new AllocType("com.x.Cache$Node", 2_000_000L, 100L));
        Map<String, Long> retained = Map.of("com.x.Cache$Node", 2_000_000L);

        Entry e = AllocRetainedCrossLink.link(hot, retained).get(0);
        assertEquals(1.0, e.retainedRatio()); // allocated bytes are fully retained
    }

    @Test
    void skipsNullAndBlankNamedTypes() {
        java.util.List<AllocType> hot = new java.util.ArrayList<>();
        hot.add(null);
        hot.add(new AllocType("  ", 1L, 1L));
        hot.add(new AllocType("Good", 5L, 1L));

        List<Entry> entries = AllocRetainedCrossLink.link(hot, Map.of("Good", 9L));

        assertEquals(1, entries.size());
        assertEquals("Good", entries.get(0).className());
        assertEquals(9L, entries.get(0).retainedBytes());
    }
}
