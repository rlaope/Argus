package io.argus.server.analysis;

import io.argus.core.event.EventType;
import io.argus.core.event.VirtualThreadEvent;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Regression coverage for the pinning hotspot eviction policy. The earlier
 * {@code removeIf(count <= 1)} eviction wiped the entire map whenever pinning was
 * spread across many distinct call sites each seen exactly once — losing all
 * hotspots precisely when the data mattered most. Eviction must instead retain the
 * highest-count sites.
 */
class PinningAnalyzerTest {

    private static VirtualThreadEvent pinned(String stack) {
        return new VirtualThreadEvent(EventType.VIRTUAL_THREAD_PINNED, 1L, "vt", 2L,
                Instant.now(), 1_000_000L, stack);
    }

    @Test
    void eviction_does_not_wipe_all_singleton_hotspots() {
        PinningAnalyzer analyzer = new PinningAnalyzer();

        // 250 distinct call sites, each pinned exactly once → all count==1. This is
        // past the 2× capacity eviction trigger (MAX_HOTSPOTS=100).
        for (int i = 0; i < 250; i++) {
            analyzer.recordPinnedEvent(pinned("at com.example.Site" + i + ".call(Site.java:" + i + ")"));
        }

        var result = analyzer.getAnalysis();
        // Every event was recorded against the total counter regardless of eviction.
        assertEquals(250, result.totalPinnedEvents(), "total pinned events must count every recorded event");
        // The map must NOT have been emptied; it stays bounded but non-empty.
        assertTrue(result.uniqueStackTraces() > 0, "eviction must not wipe all singleton hotspots");
        assertTrue(result.uniqueStackTraces() <= 200,
                "unique stack traces must stay bounded under sustained distinct pinning");
    }

    @Test
    void high_count_hotspot_survives_eviction() {
        PinningAnalyzer analyzer = new PinningAnalyzer();

        // One dominant hotspot, hit many times.
        String hot = "at com.example.HotSpot.blocking(HotSpot.java:42)";
        for (int i = 0; i < 50; i++) {
            analyzer.recordPinnedEvent(pinned(hot));
        }
        // Plus a flood of distinct singletons that triggers eviction.
        for (int i = 0; i < 250; i++) {
            analyzer.recordPinnedEvent(pinned("at com.example.Noise" + i + ".x(N.java:" + i + ")"));
        }

        var result = analyzer.getAnalysis();
        assertFalse(result.hotspots().isEmpty(), "hotspots must remain after eviction");
        // The dominant site must survive and rank first with its full count.
        var top = result.hotspots().get(0);
        assertEquals(50, top.count(), "the high-count hotspot must survive eviction with its count intact");
    }
}
