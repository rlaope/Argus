package io.argus.server.state;

import io.argus.core.event.VirtualThreadEvent;

import java.util.Collection;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Registry for tracking active virtual threads.
 *
 * <p>This class maintains a thread-safe map of currently running virtual threads,
 * allowing the server to track thread lifecycle and provide accurate active thread counts.
 */
public final class ActiveThreadsRegistry {

    private final Map<Long, VirtualThreadEvent> activeThreads = new ConcurrentHashMap<>();

    /**
     * Registers a thread as active (started).
     *
     * @param threadId the thread ID
     * @param event    the start event
     */
    public void register(long threadId, VirtualThreadEvent event) {
        activeThreads.put(threadId, event);
    }

    /**
     * Unregisters a thread (ended).
     *
     * @param threadId the thread ID to remove
     */
    public void unregister(long threadId) {
        activeThreads.remove(threadId);
    }

    /**
     * Returns the number of currently active threads.
     *
     * @return active thread count
     */
    public int size() {
        return activeThreads.size();
    }

    /**
     * Checks if a thread is currently active.
     *
     * @param threadId the thread ID to check
     * @return true if the thread is active
     */
    public boolean isActive(long threadId) {
        return activeThreads.containsKey(threadId);
    }

    /**
     * Gets the event for an active thread.
     *
     * @param threadId the thread ID
     * @return the thread's start event, or null if not active
     */
    public VirtualThreadEvent get(long threadId) {
        return activeThreads.get(threadId);
    }

    /**
     * Returns all currently active threads.
     *
     * @return collection of active thread events
     */
    public Collection<VirtualThreadEvent> getAll() {
        return activeThreads.values();
    }

    /**
     * Clears all tracked threads.
     */
    public void clear() {
        activeThreads.clear();
    }
}
