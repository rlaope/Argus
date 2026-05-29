package io.argus.aggregator;

import io.argus.aggregator.profile.UnifiedProfileView;
import io.argus.aggregator.profile.UnifiedProfileView.Source;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class UnifiedProfileViewTest {

    @Test
    void mergesJfrAndAsyncProfilerStacks_bothSourcesRepresented() {
        Map<String, Long> jfr = Map.of(
                "main;com.x.A.run", 10L,
                "main;com.x.B.work", 5L);
        Map<String, Long> async = Map.of(
                "main;com.x.A.run", 3L,   // shared stack -> summed
                "main;com.x.C.alloc", 7L);

        UnifiedProfileView view = UnifiedProfileView.of(jfr, async);

        // Both sources are represented in the unified structure.
        assertTrue(view.hasSource(Source.JFR));
        assertTrue(view.hasSource(Source.ASYNC_PROFILER));

        Map<String, Long> merged = view.merged();
        assertEquals(3, merged.size());
        assertEquals(13L, merged.get("main;com.x.A.run")); // 10 + 3 summed across sources
        assertEquals(5L, merged.get("main;com.x.B.work"));
        assertEquals(7L, merged.get("main;com.x.C.alloc"));

        assertEquals(15L, view.perSourceTotals().get(Source.JFR));
        assertEquals(10L, view.perSourceTotals().get(Source.ASYNC_PROFILER));
        assertEquals(25L, view.totalSamples());
    }

    @Test
    void addedSourceWithNoStacks_stillCountsAsRepresented() {
        UnifiedProfileView view = UnifiedProfileView.of(Map.of("a;b", 4L), Map.of());

        assertTrue(view.hasSource(Source.JFR));
        assertTrue(view.hasSource(Source.ASYNC_PROFILER));
        assertEquals(0L, view.perSourceTotals().get(Source.ASYNC_PROFILER));
        assertEquals(4L, view.totalSamples());
    }

    @Test
    void unaddedSource_isNotRepresented() {
        UnifiedProfileView view = new UnifiedProfileView().add(Source.JFR, Map.of("a", 1L));
        assertTrue(view.hasSource(Source.JFR));
        assertFalse(view.hasSource(Source.ASYNC_PROFILER));
    }

    @Test
    void nullAndNonPositiveCounts_ignored() {
        java.util.HashMap<String, Long> noisy = new java.util.HashMap<>();
        noisy.put("good", 2L);
        noisy.put("zero", 0L);
        noisy.put("neg", -5L);
        noisy.put("nullcount", null);
        noisy.put("  ", 9L);

        UnifiedProfileView view = new UnifiedProfileView().add(Source.ASYNC_PROFILER, noisy);

        Map<String, Long> merged = view.merged();
        assertEquals(1, merged.size());
        assertEquals(2L, merged.get("good"));
    }
}
