package io.argus.core.buffer;

import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

/**
 * A lock-free, single-producer multiple-consumer ring buffer for high-performance
 * event collection. Uses atomic operations to ensure thread safety without locks.
 *
 * @param <T> the type of elements in the buffer
 */
public final class RingBuffer<T> {

    private static final int DEFAULT_CAPACITY = 65536;

    private final Object[] buffer;
    private final int capacity;
    private final int mask;

    private final AtomicLong writeSequence = new AtomicLong(0);
    private final AtomicLong readSequence = new AtomicLong(0);

    /**
     * Creates a ring buffer with the default capacity (65536).
     */
    public RingBuffer() {
        this(DEFAULT_CAPACITY);
    }

    /**
     * Creates a ring buffer with the specified capacity.
     * Capacity will be rounded up to the next power of 2.
     *
     * @param requestedCapacity the requested capacity
     */
    public RingBuffer(int requestedCapacity) {
        this.capacity = nextPowerOfTwo(requestedCapacity);
        this.mask = capacity - 1;
        this.buffer = new Object[capacity];
    }

    /**
     * Offers an element to the buffer. If the buffer is full, the oldest
     * element is overwritten (lossy behavior for high-throughput scenarios).
     *
     * @param element the element to add
     * @return true if successful (always returns true in lossy mode)
     */
    public boolean offer(T element) {
        long current = writeSequence.get();
        buffer[(int) (current & mask)] = element;
        writeSequence.lazySet(current + 1);
        return true;
    }

    /**
     * Polls an element from the buffer.
     *
     * @return the element, or null if the buffer is empty
     */
    @SuppressWarnings("unchecked")
    public T poll() {
        long currentRead = readSequence.get();
        long currentWrite = writeSequence.get();

        if (currentRead >= currentWrite) {
            return null;
        }

        T element = (T) buffer[(int) (currentRead & mask)];
        if (readSequence.compareAndSet(currentRead, currentRead + 1)) {
            return element;
        }

        return null;
    }

    /**
     * Drains all available elements to the consumer.
     *
     * @param consumer the consumer to receive elements
     * @return the number of elements drained
     */
    @SuppressWarnings("unchecked")
    public int drain(Consumer<T> consumer) {
        int count = 0;
        T element;
        while ((element = poll()) != null) {
            consumer.accept(element);
            count++;
        }
        return count;
    }

    /**
     * Returns the number of elements currently in the buffer.
     */
    public int size() {
        long write = writeSequence.get();
        long read = readSequence.get();
        long size = write - read;
        return size > capacity ? capacity : (int) size;
    }

    /**
     * Returns true if the buffer is empty.
     */
    public boolean isEmpty() {
        return writeSequence.get() == readSequence.get();
    }

    /**
     * Returns the capacity of the buffer.
     */
    public int capacity() {
        return capacity;
    }

    /**
     * Clears the buffer.
     */
    public void clear() {
        readSequence.set(writeSequence.get());
    }

    private static int nextPowerOfTwo(int value) {
        if (value <= 0) {
            return 1;
        }
        value--;
        value |= value >> 1;
        value |= value >> 2;
        value |= value >> 4;
        value |= value >> 8;
        value |= value >> 16;
        return value + 1;
    }
}
