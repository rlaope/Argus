package io.argus.server.state;

import io.argus.core.event.VirtualThreadEvent;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Manages real-time state of virtual threads for dashboard mirroring.
 *
 * <p>Tracks thread states (RUNNING, PINNED, ENDED) and provides
 * state snapshots for WebSocket broadcast to connected clients.
 */
public final class ThreadStateManager {

    /**
     * Thread state enum for UI display.
     */
    public enum State {
        RUNNING,
        PINNED,
        ENDED
    }

    /**
     * Thread state record for serialization.
     */
    public record ThreadState(
            long threadId,
            String threadName,
            Long carrierThread,
            State state,
            Instant startTime,
            Instant endTime,
            boolean isPinned
    ) {}

    private static final long ENDED_RETENTION_MS = 5000; // Keep ended threads for 5 seconds

    private final Map<Long, MutableThreadState> threads = new ConcurrentHashMap<>();
    private volatile boolean stateChanged = false;

    /**
     * Records a thread start event.
     *
     * @param event the start event
     */
    public void onThreadStart(VirtualThreadEvent event) {
        threads.put(event.threadId(), new MutableThreadState(
                event.threadId(),
                event.threadName(),
                event.carrierThread() > 0 ? event.carrierThread() : null,
                event.timestamp()
        ));
        stateChanged = true;
    }

    /**
     * Records a thread end event.
     *
     * @param event the end event
     */
    public void onThreadEnd(VirtualThreadEvent event) {
        MutableThreadState thread = threads.get(event.threadId());
        if (thread != null) {
            thread.state = State.ENDED;
            thread.endTime = Instant.now();
            stateChanged = true;
        }
    }

    /**
     * Records a thread pinned event.
     *
     * @param event the pinned event
     */
    public void onThreadPinned(VirtualThreadEvent event) {
        MutableThreadState thread = threads.get(event.threadId());
        if (thread != null) {
            thread.isPinned = true;
            thread.state = State.PINNED;
            stateChanged = true;
        }
    }

    /**
     * Cleans up threads that have been ended for longer than retention period.
     */
    public void cleanup() {
        long now = System.currentTimeMillis();
        Iterator<Map.Entry<Long, MutableThreadState>> iter = threads.entrySet().iterator();
        while (iter.hasNext()) {
            MutableThreadState thread = iter.next().getValue();
            if (thread.state == State.ENDED && thread.endTime != null) {
                long endedMs = thread.endTime.toEpochMilli();
                if (now - endedMs > ENDED_RETENTION_MS) {
                    iter.remove();
                    stateChanged = true;
                }
            }
        }
    }

    /**
     * Checks if state has changed since last check and resets the flag.
     *
     * @return true if state changed
     */
    public boolean hasStateChanged() {
        if (stateChanged) {
            stateChanged = false;
            return true;
        }
        return false;
    }

    /**
     * Returns the current state snapshot.
     *
     * @return list of all thread states
     */
    public List<ThreadState> getStateSnapshot() {
        List<ThreadState> snapshot = new ArrayList<>(threads.size());
        for (MutableThreadState thread : threads.values()) {
            snapshot.add(new ThreadState(
                    thread.threadId,
                    thread.threadName,
                    thread.carrierThread,
                    thread.state,
                    thread.startTime,
                    thread.endTime,
                    thread.isPinned
            ));
        }
        return snapshot;
    }

    /**
     * Returns count of threads by state.
     *
     * @return map of state to count
     */
    public Map<State, Integer> getStateCounts() {
        int running = 0;
        int pinned = 0;
        int ended = 0;
        for (MutableThreadState thread : threads.values()) {
            switch (thread.state) {
                case RUNNING -> running++;
                case PINNED -> pinned++;
                case ENDED -> ended++;
            }
        }
        return Map.of(State.RUNNING, running, State.PINNED, pinned, State.ENDED, ended);
    }

    /**
     * Internal mutable thread state.
     */
    private static class MutableThreadState {
        final long threadId;
        final String threadName;
        final Long carrierThread;
        final Instant startTime;
        State state;
        Instant endTime;
        boolean isPinned;

        MutableThreadState(long threadId, String threadName, Long carrierThread, Instant startTime) {
            this.threadId = threadId;
            this.threadName = threadName;
            this.carrierThread = carrierThread;
            this.startTime = startTime;
            this.state = State.RUNNING;
            this.isPinned = false;
        }
    }
}
