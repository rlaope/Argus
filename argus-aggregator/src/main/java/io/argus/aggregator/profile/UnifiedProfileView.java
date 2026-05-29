package io.argus.aggregator.profile;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Merges profile samples that arrive from two different JVM sources — JFR
 * (Flight Recorder) execution-sample stacks and async-profiler collapsed stacks —
 * into one unified {@code stack -> count} view, while still tracking which source
 * each stack came from.
 *
 * <p>Argus ingests both kinds of profile: JFR events (always-available, low-overhead,
 * built into the JVM) and async-profiler captures (richer native + alloc detail).
 * They describe the same threads but in two different formats and overhead profiles.
 * A unified view lets a single flame graph / query span both sources without the
 * operator having to know which collector produced a given window.
 *
 * <p>Both inputs are collapsed-stack maps ({@code "a;b;c" -> count}, leaf last) as
 * {@link FlameTree} and {@link ProfileStore} already use. The merged map sums counts
 * for identical stacks across sources; per-source totals and the set of contributing
 * sources are preserved so a caller can attribute or filter.
 *
 * <p>Pure and dependency-free, so it is unit-testable without a live JVM. The
 * resulting {@link #merged()} map feeds straight into {@link FlameTree#toJson} for a
 * single unified flame graph.
 */
public final class UnifiedProfileView {

    /** Origin of a set of collapsed stacks. */
    public enum Source { JFR, ASYNC_PROFILER }

    // Summed stack -> total count across all merged sources.
    private final Map<String, Long> merged = new LinkedHashMap<>();
    // Per-source total sample count contributed.
    private final Map<Source, Long> perSourceTotals = new LinkedHashMap<>();

    /**
     * Adds a source's collapsed stacks into the unified view, summing counts for
     * stacks already present.
     *
     * @param source   which collector produced these stacks (non-null)
     * @param collapsed {@code stack -> count} map (null treated as empty; non-positive counts skipped)
     * @return this view, for chaining
     */
    public UnifiedProfileView add(Source source, Map<String, Long> collapsed) {
        if (source == null) {
            throw new IllegalArgumentException("source must not be null");
        }
        perSourceTotals.putIfAbsent(source, 0L);
        if (collapsed == null) {
            return this;
        }
        for (Map.Entry<String, Long> e : collapsed.entrySet()) {
            String stack = e.getKey();
            long count = e.getValue() == null ? 0L : e.getValue();
            if (stack == null || stack.isBlank() || count <= 0L) {
                continue;
            }
            merged.merge(stack, count, Long::sum);
            perSourceTotals.merge(source, count, Long::sum);
        }
        return this;
    }

    /**
     * Convenience factory: merge one JFR map and one async-profiler map.
     *
     * @param jfr   JFR-sourced collapsed stacks (may be null)
     * @param async async-profiler-sourced collapsed stacks (may be null)
     * @return a unified view containing both sources
     */
    public static UnifiedProfileView of(Map<String, Long> jfr, Map<String, Long> async) {
        return new UnifiedProfileView()
                .add(Source.JFR, jfr)
                .add(Source.ASYNC_PROFILER, async);
    }

    /**
     * The unified {@code stack -> totalCount} map, summed across all added sources.
     * Suitable to hand directly to {@link FlameTree#toJson}.
     *
     * @return a copy of the merged map
     */
    public Map<String, Long> merged() {
        return new LinkedHashMap<>(merged);
    }

    /**
     * Per-source contributed sample totals. A source that was added with no
     * positive-count stacks still appears with a total of {@code 0}.
     *
     * @return a copy of the per-source totals
     */
    public Map<Source, Long> perSourceTotals() {
        return new HashMap<>(perSourceTotals);
    }

    /**
     * Whether the given source contributed to this unified view (i.e. was added,
     * regardless of whether it carried any positive-count stacks).
     *
     * @param source the source to check
     * @return true if the source was added
     */
    public boolean hasSource(Source source) {
        return perSourceTotals.containsKey(source);
    }

    /** Total sample count across all sources and stacks. */
    public long totalSamples() {
        long total = 0L;
        for (long v : merged.values()) {
            total += v;
        }
        return total;
    }
}
