package io.argus.server.state;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedDeque;

/**
 * Buffer for storing events per thread.
 *
 * <p>This class maintains a separate event history for each thread,
 * allowing retrieval of all events related to a specific thread.
 */
public final class ThreadEventsBuffer {

    private static final int MAX_EVENTS_PER_THREAD = 100;
    private static final int MAX_THREADS = 1000;

    private final Map<Long, ConcurrentLinkedDeque<String>> threadEvents = new ConcurrentHashMap<>();
    private final int maxEventsPerThread;

    /**
     * Creates a thread events buffer with default settings.
     */
    public ThreadEventsBuffer() {
        this(MAX_EVENTS_PER_THREAD);
    }

    /**
     * Creates a thread events buffer with custom max events per thread.
     *
     * @param maxEventsPerThread maximum events to store per thread
     */
    public ThreadEventsBuffer(int maxEventsPerThread) {
        this.maxEventsPerThread = maxEventsPerThread;
    }

    /**
     * Adds an event for a specific thread.
     *
     * @param threadId  the thread ID
     * @param eventJson the event JSON string
     */
    public void add(long threadId, String eventJson) {
        ConcurrentLinkedDeque<String> events = threadEvents.computeIfAbsent(
                threadId, k -> new ConcurrentLinkedDeque<>());

        events.addLast(eventJson);

        // Limit events per thread
        while (events.size() > maxEventsPerThread) {
            events.removeFirst();
        }

        // Limit total threads tracked (remove oldest if needed)
        if (threadEvents.size() > MAX_THREADS) {
            // Find and remove a thread with no recent activity
            // Simple approach: remove first entry found
            threadEvents.keySet().stream().findFirst().ifPresent(threadEvents::remove);
        }
    }

    /**
     * Gets all events for a specific thread.
     *
     * @param threadId the thread ID
     * @return list of event JSON strings, or empty list if thread not found
     */
    public List<String> getEvents(long threadId) {
        ConcurrentLinkedDeque<String> events = threadEvents.get(threadId);
        if (events == null) {
            return Collections.emptyList();
        }
        return new ArrayList<>(events);
    }

    /**
     * Checks if a thread has any recorded events.
     *
     * @param threadId the thread ID
     * @return true if events exist for this thread
     */
    public boolean hasEvents(long threadId) {
        ConcurrentLinkedDeque<String> events = threadEvents.get(threadId);
        return events != null && !events.isEmpty();
    }

    /**
     * Gets the number of events stored for a thread.
     *
     * @param threadId the thread ID
     * @return event count, or 0 if thread not found
     */
    public int getEventCount(long threadId) {
        ConcurrentLinkedDeque<String> events = threadEvents.get(threadId);
        return events != null ? events.size() : 0;
    }

    /**
     * Removes all events for a thread.
     *
     * @param threadId the thread ID
     */
    public void clear(long threadId) {
        threadEvents.remove(threadId);
    }

    /**
     * Removes all stored events.
     */
    public void clearAll() {
        threadEvents.clear();
    }

    /**
     * Gets the number of threads being tracked.
     *
     * @return thread count
     */
    public int getTrackedThreadCount() {
        return threadEvents.size();
    }

    /**
     * Gets all tracked thread IDs.
     *
     * @return list of thread IDs
     */
    public List<Long> getTrackedThreadIds() {
        return new ArrayList<>(threadEvents.keySet());
    }
}
