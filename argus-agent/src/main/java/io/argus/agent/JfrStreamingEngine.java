package io.argus.agent;

import io.argus.core.buffer.RingBuffer;
import io.argus.core.event.VirtualThreadEvent;

import jdk.jfr.consumer.RecordedEvent;
import jdk.jfr.consumer.RecordedStackTrace;
import jdk.jfr.consumer.RecordingStream;

import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * JFR streaming engine for capturing virtual thread events in real-time.
 *
 * <p>This engine uses the JDK Flight Recorder streaming API to capture
 * virtual thread lifecycle events with minimal overhead.
 *
 * <p>Captured events:
 * <ul>
 *   <li>jdk.VirtualThreadStart - Thread creation</li>
 *   <li>jdk.VirtualThreadEnd - Thread termination</li>
 *   <li>jdk.VirtualThreadPinned - Pinning detection (critical for Loom)</li>
 *   <li>jdk.VirtualThreadSubmitFailed - Submit failures</li>
 * </ul>
 */
public final class JfrStreamingEngine {

    private static final String EVENT_VIRTUAL_THREAD_START = "jdk.VirtualThreadStart";
    private static final String EVENT_VIRTUAL_THREAD_END = "jdk.VirtualThreadEnd";
    private static final String EVENT_VIRTUAL_THREAD_PINNED = "jdk.VirtualThreadPinned";
    private static final String EVENT_VIRTUAL_THREAD_SUBMIT_FAILED = "jdk.VirtualThreadSubmitFailed";

    private final RingBuffer<VirtualThreadEvent> eventBuffer;
    private final AtomicBoolean running = new AtomicBoolean(false);
    private final AtomicLong eventsProcessed = new AtomicLong(0);

    private volatile RecordingStream recordingStream;
    private volatile Thread streamThread;

    public JfrStreamingEngine(RingBuffer<VirtualThreadEvent> eventBuffer) {
        this.eventBuffer = eventBuffer;
    }

    /**
     * Starts the JFR streaming engine.
     */
    public void start() {
        if (!running.compareAndSet(false, true)) {
            System.err.println("[Argus] JFR streaming engine already running");
            return;
        }

        streamThread = Thread.ofPlatform()
                .name("argus-jfr-stream")
                .daemon(true)
                .start(this::runStream);
    }

    private void runStream() {
        try (RecordingStream rs = new RecordingStream()) {
            this.recordingStream = rs;

            // Configure virtual thread events
            rs.enable(EVENT_VIRTUAL_THREAD_START);
            rs.enable(EVENT_VIRTUAL_THREAD_END);
            rs.enable(EVENT_VIRTUAL_THREAD_PINNED).withStackTrace();
            rs.enable(EVENT_VIRTUAL_THREAD_SUBMIT_FAILED);

            // Set streaming interval for low latency
            rs.setMaxAge(Duration.ofSeconds(10));
            rs.setMaxSize(10 * 1024 * 1024); // 10 MB

            // Register event handlers
            rs.onEvent(EVENT_VIRTUAL_THREAD_START, this::handleVirtualThreadStart);
            rs.onEvent(EVENT_VIRTUAL_THREAD_END, this::handleVirtualThreadEnd);
            rs.onEvent(EVENT_VIRTUAL_THREAD_PINNED, this::handleVirtualThreadPinned);
            rs.onEvent(EVENT_VIRTUAL_THREAD_SUBMIT_FAILED, this::handleVirtualThreadSubmitFailed);

            System.out.println("[Argus] JFR streaming started");
            rs.start();

        } catch (Exception e) {
            System.err.println("[Argus] JFR streaming error: " + e.getMessage());
            e.printStackTrace();
        } finally {
            running.set(false);
        }
    }

    private void handleVirtualThreadStart(RecordedEvent event) {
        long threadId = event.getLong("javaThreadId");
        String threadName = getThreadName(event);
        Instant timestamp = event.getStartTime();

        VirtualThreadEvent vtEvent = VirtualThreadEvent.start(threadId, threadName, timestamp);
        eventBuffer.offer(vtEvent);
        eventsProcessed.incrementAndGet();
    }

    private void handleVirtualThreadEnd(RecordedEvent event) {
        long threadId = event.getLong("javaThreadId");
        String threadName = getThreadName(event);
        Instant timestamp = event.getStartTime();

        VirtualThreadEvent vtEvent = VirtualThreadEvent.end(threadId, threadName, timestamp);
        eventBuffer.offer(vtEvent);
        eventsProcessed.incrementAndGet();
    }

    private void handleVirtualThreadPinned(RecordedEvent event) {
        long threadId = event.getLong("javaThreadId");
        String threadName = getThreadName(event);
        long carrierThread = getCarrierThread(event);
        Instant timestamp = event.getStartTime();
        long duration = event.getDuration().toNanos();
        String stackTrace = formatStackTrace(event.getStackTrace());

        VirtualThreadEvent vtEvent = VirtualThreadEvent.pinned(
                threadId, threadName, carrierThread, timestamp, duration, stackTrace);
        eventBuffer.offer(vtEvent);
        eventsProcessed.incrementAndGet();

        // Log pinning events as they are critical for performance
        System.out.printf("[Argus] PINNED: thread=%d, carrier=%d, duration=%dns%n",
                threadId, carrierThread, duration);
    }

    private void handleVirtualThreadSubmitFailed(RecordedEvent event) {
        long threadId = event.getLong("javaThreadId");
        String threadName = getThreadName(event);
        Instant timestamp = event.getStartTime();

        VirtualThreadEvent vtEvent = VirtualThreadEvent.submitFailed(threadId, threadName, timestamp);
        eventBuffer.offer(vtEvent);
        eventsProcessed.incrementAndGet();

        System.out.printf("[Argus] SUBMIT_FAILED: thread=%d%n", threadId);
    }

    private String getThreadName(RecordedEvent event) {
        try {
            return event.getString("eventThread.javaName");
        } catch (Exception e) {
            return null;
        }
    }

    private long getCarrierThread(RecordedEvent event) {
        try {
            return event.getLong("carrierThread.javaThreadId");
        } catch (Exception e) {
            return -1;
        }
    }

    private String formatStackTrace(RecordedStackTrace stackTrace) {
        if (stackTrace == null) {
            return null;
        }

        StringBuilder sb = new StringBuilder();
        stackTrace.getFrames().forEach(frame -> {
            sb.append(frame.getMethod().getType().getName())
                    .append(".")
                    .append(frame.getMethod().getName())
                    .append("(")
                    .append(frame.getLineNumber())
                    .append(")\n");
        });
        return sb.toString();
    }

    /**
     * Stops the JFR streaming engine.
     */
    public void stop() {
        if (!running.compareAndSet(true, false)) {
            return;
        }

        if (recordingStream != null) {
            recordingStream.close();
        }

        if (streamThread != null) {
            streamThread.interrupt();
            try {
                streamThread.join(5000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }

        System.out.printf("[Argus] JFR streaming stopped. Total events processed: %d%n",
                eventsProcessed.get());
    }

    /**
     * Returns true if the engine is running.
     */
    public boolean isRunning() {
        return running.get();
    }

    /**
     * Returns the total number of events processed.
     */
    public long getEventsProcessed() {
        return eventsProcessed.get();
    }
}
