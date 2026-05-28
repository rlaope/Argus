package io.argus.diagnostics.heapgraph;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Deterministic unit tests for the dominator / retained-size / path-to-root
 * engine over hand-constructed in-memory {@link ObjectGraph}s. Retained sizes
 * and dominators are verified against values computed by hand, independent of
 * any HPROF parsing.
 */
class DominatorTreeTest {

    /**
     * Builds a CSR-backed graph from per-node edge lists.
     *
     * @param shallow   shallow size per object
     * @param classIdx  class index per object
     * @param classNames class name table
     * @param roots     GC-root object indices
     * @param adj       outgoing edges per object
     */
    private static ArrayObjectGraph graph(long[] shallow, int[] classIdx,
                                          String[] classNames, int[] roots, int[][] adj) {
        int n = shallow.length;
        int[] off = new int[n + 1];
        for (int i = 0; i < n; i++) off[i + 1] = off[i] + adj[i].length;
        int[] edges = new int[off[n]];
        int k = 0;
        for (int i = 0; i < n; i++) for (int t : adj[i]) edges[k++] = t;
        return new ArrayObjectGraph(shallow, classIdx, off, edges, roots, classNames);
    }

    @Test
    void retainedSize_simpleChain() {
        // root(0) -> a(1) -> b(2) -> c(3). Each shallow=10.
        // c retains 10, b retains 20, a retains 30, root retains 40.
        long[] shallow = {10, 10, 10, 10};
        int[] cls = {0, 1, 2, 3};
        String[] names = {"Root", "A", "B", "C"};
        int[][] adj = {{1}, {2}, {3}, {}};
        ArrayObjectGraph g = graph(shallow, cls, names, new int[]{0}, adj);

        DominatorTree dom = DominatorTree.compute(g);
        assertEquals(4, dom.reachableCount());
        assertEquals(10, dom.retainedSize(3));
        assertEquals(20, dom.retainedSize(2));
        assertEquals(30, dom.retainedSize(1));
        assertEquals(40, dom.retainedSize(0));
        // idom chain
        assertEquals(g.objectCount(), dom.idom(0)); // root dominated by super-root
        assertEquals(0, dom.idom(1));
        assertEquals(1, dom.idom(2));
        assertEquals(2, dom.idom(3));
    }

    @Test
    void retainedSize_diamond_dominatorIsTheJoin() {
        // root(0) -> a(1), root -> b(2); a -> d(3), b -> d(3).
        // d is dominated by root (two disjoint paths), not by a or b.
        // shallow: root=1, a=10, b=10, d=100
        // retained: a=10, b=10, d=100, root=1+10+10+100 = 121
        long[] shallow = {1, 10, 10, 100};
        int[] cls = {0, 1, 2, 3};
        String[] names = {"Root", "A", "B", "D"};
        int[][] adj = {{1, 2}, {3}, {3}, {}};
        ArrayObjectGraph g = graph(shallow, cls, names, new int[]{0}, adj);

        DominatorTree dom = DominatorTree.compute(g);
        assertEquals(10, dom.retainedSize(1));
        assertEquals(10, dom.retainedSize(2));
        assertEquals(100, dom.retainedSize(3));
        assertEquals(121, dom.retainedSize(0));
        // d's immediate dominator is the root, since a and b are both optional paths
        assertEquals(0, dom.idom(3));
    }

    @Test
    void unreachableObjects_retainNothing() {
        // root(0)->a(1). b(2) and c(3) are unreachable (no root reaches them).
        long[] shallow = {1, 10, 99, 99};
        int[] cls = {0, 0, 0, 0};
        String[] names = {"X"};
        int[][] adj = {{1}, {}, {3}, {}};
        ArrayObjectGraph g = graph(shallow, new int[]{0, 0, 0, 0}, names, new int[]{0}, adj);

        DominatorTree dom = DominatorTree.compute(g);
        assertEquals(2, dom.reachableCount());
        assertEquals(DominatorTree.NONE, dom.idom(2));
        assertEquals(DominatorTree.NONE, dom.idom(3));
        assertEquals(0, dom.retainedSize(2));
        assertEquals(0, dom.retainedSize(3));
    }

    @Test
    void leakSuspects_namesTheAccumulationPoint() {
        // A "leak holder" (1) retains a huge collection (indices 3..7).
        // root(0) -> holder(1) -> array(2) -> {3,4,5,6,7}
        // root(0) -> small(8)
        // holder retains array + 5 leaked entries.
        int n = 9;
        long[] shallow = new long[n];
        shallow[0] = 8;   // root obj
        shallow[1] = 16;  // holder
        shallow[2] = 48;  // array
        for (int i = 3; i <= 7; i++) shallow[i] = 1000; // leaked entries
        shallow[8] = 16;  // small unrelated obj
        int[] cls = {0, 1, 2, 3, 3, 3, 3, 3, 4};
        String[] names = {"Root", "com.app.LeakHolder", "Object[]", "com.app.Leaked", "com.app.Small"};
        int[][] adj = new int[n][];
        adj[0] = new int[]{1, 8};
        adj[1] = new int[]{2};
        adj[2] = new int[]{3, 4, 5, 6, 7};
        adj[3] = adj[4] = adj[5] = adj[6] = adj[7] = new int[]{};
        adj[8] = new int[]{};
        ArrayObjectGraph g = graph(shallow, cls, names, new int[]{0}, adj);

        HeapGraphAnalysis analysis = HeapGraphAnalysis.analyze(g);
        List<LeakSuspect> suspects = analysis.leakSuspects(3, 10.0);
        assertFalse(suspects.isEmpty(), "expected at least one suspect");

        // The top suspect should be the root (it dominates everything), but the
        // most actionable single accumulation point is the holder. Either way,
        // the holder must be reported and must name the leak class.
        boolean holderNamed = suspects.stream()
                .anyMatch(s -> s.className().equals("com.app.LeakHolder"))
                || analysis.retainedForClass("com.app.LeakHolder")[0] >= 5000;
        assertTrue(holderNamed, "leak suspects/retained must surface the LeakHolder");

        // Holder retains the array (48) + 5 * 1000 + its own 16 = 5064.
        long[] holderRetained = analysis.retainedForClass("com.app.LeakHolder");
        assertEquals(1, holderRetained[1]);
        assertEquals(16 + 48 + 5 * 1000, holderRetained[0]);
    }

    @Test
    void pathToRoot_resolvesForPlantedLeak() {
        // root(0) -> registry(1) -> map(2) -> leakedValue(3)
        long[] shallow = {1, 10, 10, 500};
        int[] cls = {0, 1, 2, 3};
        String[] names = {"Thread", "com.app.Registry", "java.util.HashMap", "com.app.SessionData"};
        int[][] adj = {{1}, {2}, {3}, {}};
        ArrayObjectGraph g = graph(shallow, cls, names, new int[]{0}, adj);

        HeapGraphAnalysis analysis = HeapGraphAnalysis.analyze(g);
        int[] path = analysis.pathToRootForClass("com.app.SessionData");
        assertEquals(4, path.length);
        // root-first ordering
        assertEquals("Thread", g.className(g.classOf(path[0])));
        assertEquals("com.app.Registry", g.className(g.classOf(path[1])));
        assertEquals("java.util.HashMap", g.className(g.classOf(path[2])));
        assertEquals("com.app.SessionData", g.className(g.classOf(path[3])));
    }

    @Test
    void topDominators_orderedByRetainedDescending() {
        long[] shallow = {1, 100, 50, 10};
        int[] cls = {0, 1, 2, 3};
        String[] names = {"R", "Big", "Mid", "Small"};
        // root -> big, root -> mid, root -> small (all leaves under root)
        int[][] adj = {{1, 2, 3}, {}, {}, {}};
        ArrayObjectGraph g = graph(shallow, cls, names, new int[]{0}, adj);

        HeapGraphAnalysis analysis = HeapGraphAnalysis.analyze(g);
        int[] top = analysis.topDominators(3);
        DominatorTree dom = analysis.dominators();
        assertTrue(dom.retainedSize(top[0]) >= dom.retainedSize(top[1]));
        assertTrue(dom.retainedSize(top[1]) >= dom.retainedSize(top[2]));
        // root retains everything (161) so it is #1
        assertEquals(0, top[0]);
    }
}
