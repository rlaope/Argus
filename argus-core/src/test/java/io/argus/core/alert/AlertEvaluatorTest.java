package io.argus.core.alert;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class AlertEvaluatorTest {

    @Test
    void firstFireOnInitialBreach() {
        AlertRule rule = new AlertRule("gc", "gc_overhead_ratio", 0.10, ">",
                "warning", null);
        AlertEvaluator e = new AlertEvaluator(List.of(rule));
        var outcomes = e.evaluate(Map.of("gc_overhead_ratio", 0.20));
        assertEquals(1, outcomes.size());
        assertTrue(outcomes.get(0).breached());
        assertTrue(outcomes.get(0).firstFire());
    }

    @Test
    void ongoingBreachDoesNotFirstFireAgain() {
        AlertRule rule = new AlertRule("gc", "gc_overhead_ratio", 0.10, ">",
                "warning", null);
        AlertEvaluator e = new AlertEvaluator(List.of(rule));
        e.evaluate(Map.of("gc_overhead_ratio", 0.20));
        var second = e.evaluate(Map.of("gc_overhead_ratio", 0.25));
        assertTrue(second.get(0).breached());
        assertFalse(second.get(0).firstFire());
    }

    @Test
    void resolvedBreachClearsState() {
        AlertRule rule = new AlertRule("gc", "gc_overhead_ratio", 0.10, ">",
                "warning", null);
        AlertEvaluator e = new AlertEvaluator(List.of(rule));
        e.evaluate(Map.of("gc_overhead_ratio", 0.20));
        var resolved = e.evaluate(Map.of("gc_overhead_ratio", 0.05));
        assertFalse(resolved.get(0).breached());
        var refire = e.evaluate(Map.of("gc_overhead_ratio", 0.20));
        assertTrue(refire.get(0).breached());
        assertTrue(refire.get(0).firstFire());
    }

    @Test
    void missingMetricTreatedAsZero() {
        AlertRule rule = new AlertRule("heap", "heap_used_percent", 80.0, ">",
                "warning", null);
        AlertEvaluator e = new AlertEvaluator(List.of(rule));
        var out = e.evaluate(Map.of());
        assertFalse(out.get(0).breached());
        assertEquals(0.0, out.get(0).value(), 0.0001);
    }

    @Test
    void comparatorBranches() {
        AlertEvaluator gt   = new AlertEvaluator(List.of(new AlertRule("a","m",1,">", "info", null)));
        AlertEvaluator gte  = new AlertEvaluator(List.of(new AlertRule("a","m",1,">=","info", null)));
        AlertEvaluator lt   = new AlertEvaluator(List.of(new AlertRule("a","m",1,"<", "info", null)));
        AlertEvaluator lte  = new AlertEvaluator(List.of(new AlertRule("a","m",1,"<=","info", null)));
        assertTrue(gt.evaluate(Map.of("m", 2.0)).get(0).breached());
        assertFalse(gt.evaluate(Map.of("m", 1.0)).get(0).breached());
        assertTrue(gte.evaluate(Map.of("m", 1.0)).get(0).breached());
        assertTrue(lt.evaluate(Map.of("m", 0.5)).get(0).breached());
        assertTrue(lte.evaluate(Map.of("m", 1.0)).get(0).breached());
    }
}
