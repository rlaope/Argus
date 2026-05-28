package io.argus.diagnostics.heapgraph;

/**
 * A directed object reference graph keyed by dense integer object indices
 * ({@code 0..objectCount()-1}).
 *
 * <p>The graph is intentionally id-indexed and backed by primitive arrays so
 * that very large heaps (multi-GB HPROF dumps) can be analyzed without
 * materializing one Java object per heap object. Outgoing edges are stored in
 * Compressed-Sparse-Row (CSR) form: a single {@code int[]} of target indices
 * plus an {@code int[]} of per-node offsets.
 *
 * <p>This abstraction is what the {@link DominatorTree} engine consumes, which
 * keeps the algorithm fully decoupled from the HPROF binary format and makes it
 * deterministically unit-testable on synthetic in-memory graphs.
 */
public interface ObjectGraph {

    /** Total number of objects (dense index space is {@code 0..count-1}). */
    int objectCount();

    /** Shallow size (bytes) of the object at {@code index}. */
    long shallowSize(int index);

    /** Class index of the object at {@code index} (see {@link #className(int)}). */
    int classOf(int index);

    /** Human-readable class name for a class index, with {@code .} separators. */
    String className(int classIndex);

    /** Number of distinct class indices. */
    int classCount();

    /**
     * Indices of objects that are GC roots (JNI globals/locals, thread stacks,
     * sticky classes, monitors, etc.). These are the entry points of the
     * reachability/dominator analysis.
     */
    int[] gcRoots();

    /**
     * Returns the outgoing reference targets of {@code index}.
     *
     * <p>The returned array is the shared CSR backing array; callers MUST treat
     * the slice {@code [outOffset(index), outOffset(index+1))} as read-only and
     * not mutate it. Use {@link #outDegree(int)} together with this for
     * iteration, or iterate the CSR directly via {@link #edges()} and
     * {@link #edgeOffsets()}.
     */
    int[] outRefs(int index);

    /** Number of outgoing references from {@code index}. */
    int outDegree(int index);

    /** The flat CSR edge target array (length = total edge count). */
    int[] edges();

    /**
     * Per-node CSR offset array of length {@code objectCount()+1}. Node
     * {@code i}'s edges occupy {@code edges()[edgeOffsets()[i] .. edgeOffsets()[i+1])}.
     */
    int[] edgeOffsets();
}
