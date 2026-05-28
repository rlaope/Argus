package io.argus.diagnostics.heapgraph;

import java.util.Arrays;

/**
 * Dominator tree + retained sizes for an {@link ObjectGraph}, computed with the
 * Lengauer–Tarjan algorithm — the same flow-based approach Eclipse MAT uses.
 *
 * <p>A synthetic super-root node (index {@code N == objectCount()}) is added
 * with an edge to every GC root, so the flow graph has a single entry.
 * Lengauer–Tarjan then yields the immediate dominator {@code idom[v]} of every
 * reachable node. The <em>retained size</em> of an object is the sum of shallow
 * sizes of all nodes it dominates (the heap that would be freed if that object
 * became unreachable). Retained sizes are accumulated by walking nodes in
 * reverse DFS order and adding each node's running retained total into its
 * immediate dominator — an O(N) post-order over the dominator tree.
 *
 * <p>All state is primitive {@code int[]}/{@code long[]} keyed by object index;
 * no per-node Java objects are allocated, so a multi-GB heap stays within the
 * analyzer's 2 GB budget. Objects not reachable from any GC root have
 * {@code idom == NONE} and a retained size of 0.
 */
public final class DominatorTree {

    /** Sentinel for "no node" / unreachable. */
    public static final int NONE = -1;

    private final int n;            // real object count
    private final int root;         // synthetic super-root index == n
    private final int[] idom;       // immediate dominator (object-index space), length n
    private final long[] retained;  // retained size by object index, length n
    private final int[] subtree;    // dominator-subtree size by object index, length n
    private final int reachableCount;

    private DominatorTree(int n, int[] idom, long[] retained, int[] subtree,
                          int reachableCount) {
        this.n = n;
        this.root = n;
        this.idom = idom;
        this.retained = retained;
        this.subtree = subtree;
        this.reachableCount = reachableCount;
    }

    /**
     * Immediate dominator of object {@code v} in object-index space, or
     * {@link #NONE} if {@code v} is unreachable from any GC root. A value equal
     * to {@code objectCount()} means "dominated directly by the synthetic
     * super-root" — i.e. a top-level retention point.
     */
    public int idom(int v) { return idom[v]; }

    /** Retained size (bytes) of object {@code v}. */
    public long retainedSize(int v) { return retained[v]; }

    /** Number of objects in {@code v}'s dominator subtree (including {@code v}). */
    public int subtreeSize(int v) { return subtree[v]; }

    /** Number of objects reachable from any GC root. */
    public int reachableCount() { return reachableCount; }

    /** The synthetic super-root index ({@code == objectCount()}). */
    public int superRoot() { return root; }

    /** True if {@code v} is directly dominated by the synthetic super-root. */
    public boolean isTopLevel(int v) { return idom[v] == root; }

    /**
     * Builds the dominator tree and retained sizes for the given graph.
     */
    public static DominatorTree compute(ObjectGraph graph) {
        int n = graph.objectCount();
        int root = n;
        int size = n + 1;            // include synthetic super-root at index n

        int[] edges = graph.edges();
        int[] edgeOff = graph.edgeOffsets();
        int[] gcRoots = graph.gcRoots();

        // --- Build predecessor adjacency (flow graph incl. super-root) as CSR ---
        // Edge count = real edges + one edge super-root -> each GC root.
        int[] predOff = new int[size + 1];
        for (int v = 0; v < n; v++) {
            int from = edgeOff[v], to = edgeOff[v + 1];
            for (int e = from; e < to; e++) {
                int t = edges[e];
                if (t >= 0) predOff[t + 1]++;
            }
        }
        for (int r : gcRoots) predOff[r + 1]++;        // super-root -> r
        for (int i = 0; i < size; i++) predOff[i + 1] += predOff[i];
        int[] predFrom = new int[predOff[size]];
        int[] cursor = Arrays.copyOf(predOff, size);
        for (int v = 0; v < n; v++) {
            int from = edgeOff[v], to = edgeOff[v + 1];
            for (int e = from; e < to; e++) {
                int t = edges[e];
                if (t >= 0) predFrom[cursor[t]++] = v;
            }
        }
        for (int r : gcRoots) predFrom[cursor[r]++] = root;

        // --- Lengauer-Tarjan working arrays over [0, size) ---
        int[] dfnum = new int[size];      // DFS number, 0 = unvisited
        int[] vertex = new int[size + 1]; // vertex[i] = node with dfnum i (1-based)
        int[] parent = new int[size];     // DFS-tree parent
        int[] semi = new int[size];       // semidominator dfnum
        int[] ancestor = new int[size];   // forest ancestor (path compression)
        int[] label = new int[size];      // label for EVAL
        int[] dom = new int[size];        // immediate dominator
        Arrays.fill(ancestor, NONE);
        Arrays.fill(dom, NONE);
        int[] bucketHead = new int[size];
        int[] bucketNext = new int[size];
        Arrays.fill(bucketHead, NONE);
        Arrays.fill(bucketNext, NONE);

        // --- Iterative DFS from super-root assigns dfnum/parent/semi/label ---
        int dfCounter = 0;
        int[] stackNode = new int[size];
        int[] stackCursor = new int[size];
        int sp = 0;
        stackNode[sp] = root;
        stackCursor[sp] = -1;
        sp++;
        while (sp > 0) {
            int top = sp - 1;
            int v = stackNode[top];
            if (stackCursor[top] == -1) {
                dfCounter++;
                dfnum[v] = dfCounter;
                vertex[dfCounter] = v;
                semi[v] = dfCounter;
                label[v] = v;
                stackCursor[top] = succStart(v, root, edgeOff);
            }
            int cur = stackCursor[top];
            int end = succEnd(v, root, gcRoots, edgeOff);
            boolean descended = false;
            while (cur < end) {
                int w = succAt(v, root, gcRoots, edges, cur);
                cur++;
                if (w < 0) continue;
                if (dfnum[w] == 0) {
                    parent[w] = v;
                    stackCursor[top] = cur;
                    stackNode[sp] = w;
                    stackCursor[sp] = -1;
                    sp++;
                    descended = true;
                    break;
                }
            }
            if (!descended) {
                stackCursor[top] = cur;
                if (cur >= end) sp--;
            }
        }
        int reachable = dfCounter; // including super-root

        int[] compressPath = new int[64];

        // --- Steps 2 & 3 ---
        for (int i = dfCounter; i >= 2; i--) {
            int w = vertex[i];
            for (int p = predOff[w]; p < predOff[w + 1]; p++) {
                int vPred = predFrom[p];
                if (dfnum[vPred] == 0) continue; // predecessor unreachable
                int u = eval(vPred, ancestor, label, semi, compressPath);
                if (semi[u] < semi[w]) semi[w] = semi[u];
            }
            int s = vertex[semi[w]];
            bucketNext[w] = bucketHead[s];
            bucketHead[s] = w;
            ancestor[w] = parent[w];     // LINK(parent[w], w)

            int pw = parent[w];
            for (int v = bucketHead[pw]; v != NONE; ) {
                int next = bucketNext[v];
                int u = eval(v, ancestor, label, semi, compressPath);
                dom[v] = (semi[u] < semi[v]) ? u : pw;
                v = next;
            }
            bucketHead[pw] = NONE;
        }

        // --- Step 4: finalize idom in DFS order ---
        for (int i = 2; i <= dfCounter; i++) {
            int w = vertex[i];
            if (dom[w] != vertex[semi[w]]) dom[w] = dom[dom[w]];
        }
        if (dfCounter >= 1) dom[root] = NONE;

        // --- Project into object-index space, capture DFS order of objects ---
        int[] idom = new int[n];
        Arrays.fill(idom, NONE);
        int[] order = new int[Math.max(0, reachable - 1)];
        int oi = 0;
        for (int i = 2; i <= dfCounter; i++) {
            int w = vertex[i];
            if (w == root) continue;
            order[oi++] = w;
            idom[w] = dom[w]; // may equal root (== n) for top-level objects
        }

        // --- Retained sizes + subtree sizes: reverse-DFS post-order accumulation ---
        long[] retained = new long[n];
        int[] subtree = new int[n];
        for (int v = 0; v < n; v++) {
            if (idom[v] != NONE) {
                retained[v] = graph.shallowSize(v);
                subtree[v] = 1;
            }
        }
        for (int k = oi - 1; k >= 0; k--) {
            int v = order[k];
            int d = idom[v];
            if (d != NONE && d != root) {
                retained[d] += retained[v];
                subtree[d] += subtree[v];
            }
        }

        return new DominatorTree(n, idom, retained, subtree, oi);
    }

    // ----- successor iteration helpers (super-root expands to gcRoots) -----

    private static int succStart(int v, int root, int[] edgeOff) {
        return v == root ? 0 : edgeOff[v];
    }

    private static int succEnd(int v, int root, int[] gcRoots, int[] edgeOff) {
        return v == root ? gcRoots.length : edgeOff[v + 1];
    }

    private static int succAt(int v, int root, int[] gcRoots, int[] edges, int cursor) {
        return v == root ? gcRoots[cursor] : edges[cursor];
    }

    private static int eval(int v, int[] ancestor, int[] label, int[] semi, int[] path) {
        if (ancestor[v] == NONE) return label[v];
        compress(v, ancestor, label, semi, path);
        return label[v];
    }

    /** Iterative path compression (avoids deep recursion on long ancestor chains). */
    private static void compress(int v, int[] ancestor, int[] label, int[] semi, int[] path) {
        int p = v;
        int top = 0;
        while (ancestor[ancestor[p]] != NONE) {
            if (top == path.length) path = Arrays.copyOf(path, path.length * 2);
            path[top++] = p;
            p = ancestor[p];
        }
        for (int i = top - 1; i >= 0; i--) {
            int node = path[i];
            int anc = ancestor[node];
            if (semi[label[anc]] < semi[label[node]]) label[node] = label[anc];
            ancestor[node] = ancestor[anc];
        }
        int anc = ancestor[v];
        if (anc != NONE && semi[label[anc]] < semi[label[v]]) label[v] = label[anc];
    }
}
