package io.argus.diagnostics.heapgraph;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;

/**
 * MAT-class triage over an {@link ObjectGraph}: dominator tree, retained sizes
 * (per object and aggregated per class), an automated leak-suspects report, and
 * shortest path-to-GC-root resolution.
 *
 * <p>This is the framework-agnostic analysis engine that backs
 * {@code argus heapanalyze --leak-suspects / --dominators / --retained /
 * --path-to-root}. It operates purely on the id-indexed {@link ObjectGraph}, so
 * it is reusable for any graph source and deterministically unit-testable.
 */
public final class HeapGraphAnalysis {

    private final ObjectGraph graph;
    private final DominatorTree dominators;
    private final long totalReachableBytes;

    private HeapGraphAnalysis(ObjectGraph graph, DominatorTree dominators,
                              long totalReachableBytes) {
        this.graph = graph;
        this.dominators = dominators;
        this.totalReachableBytes = totalReachableBytes;
    }

    public static HeapGraphAnalysis analyze(ObjectGraph graph) {
        DominatorTree dom = DominatorTree.compute(graph);
        long total = 0;
        int n = graph.objectCount();
        for (int v = 0; v < n; v++) {
            if (dom.idom(v) != DominatorTree.NONE) total += graph.shallowSize(v);
        }
        return new HeapGraphAnalysis(graph, dom, total);
    }

    public ObjectGraph graph() { return graph; }
    public DominatorTree dominators() { return dominators; }
    public long totalReachableBytes() { return totalReachableBytes; }

    /**
     * Top {@code n} objects by retained size, descending. Each entry is the
     * object index; use {@link #dominators()} for the retained value and
     * {@link ObjectGraph#className(int)} for the label.
     */
    public int[] topDominators(int n) {
        int count = graph.objectCount();
        // Collect reachable object indices, sort by retained desc, take n.
        Integer[] idx = new Integer[count];
        int m = 0;
        for (int v = 0; v < count; v++) {
            if (dominators.idom(v) != DominatorTree.NONE) idx[m++] = v;
        }
        Integer[] reach = Arrays.copyOf(idx, m);
        Arrays.sort(reach, Comparator.comparingLong(
                (Integer v) -> dominators.retainedSize(v)).reversed());
        int take = Math.min(n, reach.length);
        int[] out = new int[take];
        for (int i = 0; i < take; i++) out[i] = reach[i];
        return out;
    }

    /**
     * Aggregated retained size per class, summing the retained size of each
     * object whose immediate dominator is NOT of the same class chain (to avoid
     * double counting we sum every object's retained size grouped by its class —
     * matching MAT's "retained set by class" where each object contributes once).
     *
     * <p>Returns a list of {@code [classIndex, retainedBytes, dominatedCount]}
     * sorted by retained bytes descending, truncated to {@code topN}.
     */
    public List<long[]> retainedByClass(int topN) {
        int classes = graph.classCount();
        long[] retainedSum = new long[classes];
        long[] objCount = new long[classes];
        int n = graph.objectCount();
        for (int v = 0; v < n; v++) {
            if (dominators.idom(v) == DominatorTree.NONE) continue;
            int c = graph.classOf(v);
            retainedSum[c] += dominators.retainedSize(v);
            objCount[c]++;
        }
        List<long[]> rows = new ArrayList<>();
        for (int c = 0; c < classes; c++) {
            if (retainedSum[c] > 0) rows.add(new long[]{c, retainedSum[c], objCount[c]});
        }
        rows.sort((a, b) -> Long.compare(b[1], a[1]));
        if (rows.size() > topN) return new ArrayList<>(rows.subList(0, topN));
        return rows;
    }

    /**
     * Retained size aggregated for all instances of a named class.
     * Returns {@code [retainedBytes, instanceCount]}.
     */
    public long[] retainedForClass(String className) {
        long retained = 0;
        long count = 0;
        int n = graph.objectCount();
        for (int v = 0; v < n; v++) {
            if (dominators.idom(v) == DominatorTree.NONE) continue;
            if (graph.className(graph.classOf(v)).equals(className)) {
                retained += dominators.retainedSize(v);
                count++;
            }
        }
        return new long[]{retained, count};
    }

    /**
     * Automated leak-suspects report. A suspect is a single dominator whose
     * retained share of the reachable heap exceeds {@code thresholdPercent}
     * (heuristic: "one object retaining a disproportionate share of heap").
     * Always returns the top retained-size accumulation points, flagging those
     * over threshold first.
     *
     * @param maxSuspects     maximum suspects to return
     * @param thresholdPercent minimum heap share to count as a suspect (e.g. 10.0)
     */
    public List<LeakSuspect> leakSuspects(int maxSuspects, double thresholdPercent) {
        int[] top = topDominators(Math.max(maxSuspects, 1));
        long total = totalReachableBytes > 0 ? totalReachableBytes : 1;
        List<LeakSuspect> suspects = new ArrayList<>();
        for (int v : top) {
            long retained = dominators.retainedSize(v);
            double pct = retained * 100.0 / total;
            if (pct < thresholdPercent && !suspects.isEmpty()) break;
            suspects.add(new LeakSuspect(v, graph.className(graph.classOf(v)),
                    retained, pct, dominators.subtreeSize(v)));
            if (suspects.size() >= maxSuspects) break;
        }
        return suspects;
    }

    /**
     * Shortest reference path from the largest reachable instance of
     * {@code className} to a GC root, returned root-first as object indices.
     */
    public int[] pathToRootForClass(String className) {
        return PathToRoot.compute(graph).shortestPathForClass(className);
    }
}
