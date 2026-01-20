package io.argus.server.state;

import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedDeque;

/**
 * Buffer for storing recent events to send to newly connected clients.
 *
 * <p>This class maintains a bounded queue of recent event JSON strings,
 * allowing new WebSocket clients to receive historical events upon connection.
 */
public final class RecentEventsBuffer {

    private final Deque<String> recentEvents = new ConcurrentLinkedDeque<>();
    private final int maxSize;

    /**
     * Creates a buffer with default capacity of 100 events.
     */
    public RecentEventsBuffer() {
        this(100);
    }

    /**
     * Creates a buffer with specified capacity.
     *
     * @param maxSize maximum number of events to retain
     */
    public RecentEventsBuffer(int maxSize) {
        this.maxSize = maxSize;
    }

    /**
     * Adds an event JSON to the buffer.
     * If the buffer is full, the oldest event is removed.
     *
     * @param eventJson JSON representation of the event
     */
    public void add(String eventJson) {
        recentEvents.addLast(eventJson);
        while (recentEvents.size() > maxSize) {
            recentEvents.removeFirst();
        }
    }

    /**
     * Returns all recent events in order (oldest to newest).
     *
     * @return list of event JSON strings
     */
    public List<String> getAll() {
        return new ArrayList<>(recentEvents);
    }

    /**
     * Returns the number of events in the buffer.
     *
     * @return event count
     */
    public int size() {
        return recentEvents.size();
    }

    /**
     * Clears all events from the buffer.
     */
    public void clear() {
        recentEvents.clear();
    }

    /**
     * Returns the maximum capacity of this buffer.
     *
     * @return maximum size
     */
    public int getMaxSize() {
        return maxSize;
    }
}
