package io.argus.diagnostics.heapgraph;

/**
 * Primitive-array backed {@link ObjectGraph} in Compressed-Sparse-Row form.
 *
 * <p>Memory footprint per object is roughly {@code 16 bytes}
 * ({@code long shallow} + {@code int class} + the CSR offset slot) plus
 * {@code 4 bytes} per reference edge — no per-object Java objects. A heap with
 * 50M objects and 100M edges fits in well under 2 GB, which is the analyzer
 * budget required by the W2 acceptance criteria.
 */
public final class ArrayObjectGraph implements ObjectGraph {

    private final long[] shallow;     // shallow size by object index
    private final int[] classOf;      // class index by object index
    private final int[] edgeOffsets;  // CSR offsets, length objectCount+1
    private final int[] edges;        // CSR edge targets
    private final int[] gcRoots;      // object indices that are GC roots
    private final String[] classNames;

    public ArrayObjectGraph(long[] shallow, int[] classOf, int[] edgeOffsets,
                            int[] edges, int[] gcRoots, String[] classNames) {
        if (edgeOffsets.length != shallow.length + 1) {
            throw new IllegalArgumentException(
                    "edgeOffsets length must be objectCount+1");
        }
        this.shallow = shallow;
        this.classOf = classOf;
        this.edgeOffsets = edgeOffsets;
        this.edges = edges;
        this.gcRoots = gcRoots;
        this.classNames = classNames;
    }

    @Override public int objectCount() { return shallow.length; }
    @Override public long shallowSize(int index) { return shallow[index]; }
    @Override public int classOf(int index) { return classOf[index]; }
    @Override public int classCount() { return classNames.length; }
    @Override public String className(int classIndex) {
        return classIndex >= 0 && classIndex < classNames.length
                ? classNames[classIndex] : "<unknown>";
    }
    @Override public int[] gcRoots() { return gcRoots; }
    @Override public int[] edges() { return edges; }
    @Override public int[] edgeOffsets() { return edgeOffsets; }

    @Override public int outDegree(int index) {
        return edgeOffsets[index + 1] - edgeOffsets[index];
    }

    @Override public int[] outRefs(int index) {
        // Returns the shared backing array; callers honor the CSR slice contract.
        return edges;
    }

    /** Total number of reference edges. */
    public int edgeCount() { return edges.length; }
}
