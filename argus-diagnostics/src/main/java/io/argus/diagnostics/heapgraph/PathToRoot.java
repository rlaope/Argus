package io.argus.diagnostics.heapgraph;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Computes the shortest reference path from an object back to a GC root.
 *
 * <p>A single breadth-first sweep from all GC roots over the forward reference
 * graph records, for every reachable object, the predecessor on a shortest path
 * from a root ({@code parent[]}). Tracing {@code parent[]} back from any object
 * yields its shortest path-to-root. BFS guarantees the path is minimal in edge
 * count, which is the "why is this still alive?" answer an operator wants.
 *
 * <p>The sweep uses a primitive {@code int[]} queue and {@code int[] parent}
 * array — O(N) memory, no per-node objects.
 */
public final class PathToRoot {

    /** Sentinel: a GC root has no parent (it is the path origin). */
    public static final int ROOT_PARENT = -2;
    /** Sentinel: object not reached from any GC root. */
    public static final int UNREACHED = -1;

    private final ObjectGraph graph;
    private final int[] parent; // parent on shortest path from a root, by object index

    private PathToRoot(ObjectGraph graph, int[] parent) {
        this.graph = graph;
        this.parent = parent;
    }

    /** Builds BFS predecessor pointers from every GC root. */
    public static PathToRoot compute(ObjectGraph graph) {
        int n = graph.objectCount();
        int[] parent = new int[n];
        Arrays.fill(parent, UNREACHED);
        int[] edges = graph.edges();
        int[] edgeOff = graph.edgeOffsets();

        int[] queue = new int[n == 0 ? 1 : n];
        int head = 0, tail = 0;
        for (int r : graph.gcRoots()) {
            if (r >= 0 && r < n && parent[r] == UNREACHED) {
                parent[r] = ROOT_PARENT;
                queue[tail++] = r;
            }
        }
        while (head < tail) {
            int v = queue[head++];
            int from = edgeOff[v], to = edgeOff[v + 1];
            for (int e = from; e < to; e++) {
                int w = edges[e];
                if (w >= 0 && parent[w] == UNREACHED) {
                    parent[w] = v;
                    queue[tail++] = w;
                }
            }
        }
        return new PathToRoot(graph, parent);
    }

    /** True if {@code target} is reachable from any GC root. */
    public boolean isReachable(int target) {
        return target >= 0 && target < parent.length && parent[target] != UNREACHED;
    }

    /**
     * Returns the shortest path from {@code target} up to its GC root, ordered
     * root-first (index 0 is the GC root, last element is {@code target}).
     * Empty if {@code target} is unreachable.
     */
    public int[] pathFor(int target) {
        if (!isReachable(target)) return new int[0];
        List<Integer> rev = new ArrayList<>();
        int cur = target;
        // bound the walk to avoid infinite loops on a malformed parent array
        int guard = parent.length + 1;
        while (cur != ROOT_PARENT && guard-- > 0) {
            rev.add(cur);
            cur = parent[cur];
        }
        int[] path = new int[rev.size()];
        for (int i = 0; i < path.length; i++) {
            path[i] = rev.get(path.length - 1 - i);
        }
        return path;
    }

    /**
     * Finds the object of the given class that has the largest shallow size and
     * returns its shortest path-to-root. Returns an empty array if no instance
     * of {@code className} is reachable.
     */
    public int[] shortestPathForClass(String className) {
        int best = -1;
        long bestSize = -1;
        int n = graph.objectCount();
        for (int v = 0; v < n; v++) {
            if (!isReachable(v)) continue;
            if (!graph.className(graph.classOf(v)).equals(className)) continue;
            long sz = graph.shallowSize(v);
            if (sz > bestSize) { bestSize = sz; best = v; }
        }
        return best < 0 ? new int[0] : pathFor(best);
    }
}
