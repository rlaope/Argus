package io.argus.diagnostics.heapgraph;

import java.util.Arrays;

/**
 * Open-addressing (linear-probing) primitive {@code long → int} map used to map
 * HPROF object ids to dense object indices.
 *
 * <p>Boxed {@code HashMap<Long,Integer>} would cost ~48 bytes per entry; on a
 * heap with tens of millions of objects that alone blows the analyzer budget.
 * This map stores keys in a {@code long[]} and values in an {@code int[]} — 12
 * bytes per slot at a 0.6 load factor — so id indexing for a 4 GB dump stays
 * within the 2 GB analyzer heap.
 *
 * <p>Keys must be non-zero (zero is the empty sentinel, matching HPROF where
 * object id 0 means {@code null}). {@link #get(long)} returns {@code -1} for a
 * missing key.
 */
public final class LongIntMap {

    private static final long EMPTY = 0L;
    private static final float LOAD_FACTOR = 0.6f;

    private long[] keys;
    private int[] values;
    private int mask;
    private int size;
    private int threshold;
    /** Fibonacci-hash right-shift = 64 - log2(cap); keeps the top log2(cap) bits so the whole table is used. */
    private int shift;

    public LongIntMap(int expected) {
        int cap = tableSizeFor(Math.max(16, (int) (expected / LOAD_FACTOR) + 1));
        keys = new long[cap];
        values = new int[cap];
        mask = cap - 1;
        threshold = (int) (cap * LOAD_FACTOR);
        shift = 64 - Integer.numberOfTrailingZeros(cap);
    }

    public int size() { return size; }

    /** Maps {@code key → value}. {@code key} must be non-zero. */
    public void put(long key, int value) {
        if (key == EMPTY) throw new IllegalArgumentException("key 0 is reserved");
        if (size >= threshold) resize();
        int i = index(key);
        while (keys[i] != EMPTY) {
            if (keys[i] == key) { values[i] = value; return; }
            i = (i + 1) & mask;
        }
        keys[i] = key;
        values[i] = value;
        size++;
    }

    /** Returns the value for {@code key}, or {@code -1} if absent. */
    public int get(long key) {
        if (key == EMPTY) return -1;
        int i = index(key);
        while (keys[i] != EMPTY) {
            if (keys[i] == key) return values[i];
            i = (i + 1) & mask;
        }
        return -1;
    }

    private int index(long key) {
        long h = key * 0x9E3779B97F4A7C15L; // Fibonacci hashing
        // Take the top log2(cap) bits so the full table is addressed even when
        // cap exceeds 2^24 (tens of millions of objects — the multi-GB use case).
        return (int) (h >>> shift);
    }

    private void resize() {
        long[] oldKeys = keys;
        int[] oldVals = values;
        int newCap = keys.length << 1;
        keys = new long[newCap];
        values = new int[newCap];
        mask = newCap - 1;
        threshold = (int) (newCap * LOAD_FACTOR);
        shift = 64 - Integer.numberOfTrailingZeros(newCap);
        Arrays.fill(keys, EMPTY);
        for (int j = 0; j < oldKeys.length; j++) {
            long k = oldKeys[j];
            if (k != EMPTY) {
                int i = index(k);
                while (keys[i] != EMPTY) i = (i + 1) & mask;
                keys[i] = k;
                values[i] = oldVals[j];
            }
        }
    }

    private static int tableSizeFor(int cap) {
        int n = Integer.highestOneBit(Math.max(1, cap - 1)) << 1;
        return Math.max(16, n);
    }
}
