package io.argus.agent;

import io.argus.core.buffer.RingBuffer;
import io.argus.core.event.VirtualThreadEvent;

import jdk.jfr.consumer.RecordedEvent;
import jdk.jfr.consumer.RecordedStackTrace;
import jdk.jfr.consumer.RecordedThread;
import jdk.jfr.consumer.RecordingStream;

import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
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
    private final CountDownLatch startedLatch = new CountDownLatch(1);

    private volatile RecordingStream recordingStream;
    private volatile Thread streamThread;

    public JfrStreamingEngine(RingBuffer<VirtualThreadEvent> eventBuffer) {
        this.eventBuffer = eventBuffer;
    }

    /**
     * Starts the JFR streaming engine and waits for it to be ready.
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

        // Wait for JFR streaming to actually start before returning
        try {
            if (!startedLatch.await(5, TimeUnit.SECONDS)) {
                System.err.println("[Argus] Warning: JFR streaming startup timed out");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private void runStream() {
        try (RecordingStream rs = new RecordingStream()) {
            this.recordingStream = rs;

            // Configure virtual thread events with zero threshold for immediate recording
            rs.enable(EVENT_VIRTUAL_THREAD_START).withoutThreshold();
            rs.enable(EVENT_VIRTUAL_THREAD_END).withoutThreshold();
            rs.enable(EVENT_VIRTUAL_THREAD_PINNED).withStackTrace().withoutThreshold();
            rs.enable(EVENT_VIRTUAL_THREAD_SUBMIT_FAILED).withoutThreshold();

            // Set streaming interval for low latency
            rs.setMaxAge(Duration.ofSeconds(10));
            rs.setMaxSize(10 * 1024 * 1024); // 10 MB

            // Register event handlers
            rs.onEvent(EVENT_VIRTUAL_THREAD_START, this::handleVirtualThreadStart);
            rs.onEvent(EVENT_VIRTUAL_THREAD_END, this::handleVirtualThreadEnd);
            rs.onEvent(EVENT_VIRTUAL_THREAD_PINNED, this::handleVirtualThreadPinned);
            rs.onEvent(EVENT_VIRTUAL_THREAD_SUBMIT_FAILED, this::handleVirtualThreadSubmitFailed);

            System.out.println("[Argus] JFR streaming started");
            rs.startAsync();

            // Signal that JFR streaming is ready
            startedLatch.countDown();

            // Keep running while not stopped
            while (running.get()) {
                rs.awaitTermination(Duration.ofMillis(100));
            }

        } catch (Exception e) {
            System.err.println("[Argus] JFR streaming error: " + e.getMessage());
            e.printStackTrace();
        } finally {
            running.set(false);
        }
    }

    private void handleVirtualThreadStart(RecordedEvent event) {
        long threadId = getThreadId(event);
        String threadName = getThreadName(event);
        Instant timestamp = event.getStartTime();

        VirtualThreadEvent vtEvent = VirtualThreadEvent.start(threadId, threadName, timestamp);
        eventBuffer.offer(vtEvent);
        eventsProcessed.incrementAndGet();
    }

    private void handleVirtualThreadEnd(RecordedEvent event) {
        long threadId = getThreadId(event);
        String threadName = getThreadName(event);
        Instant timestamp = event.getStartTime();

        VirtualThreadEvent vtEvent = VirtualThreadEvent.end(threadId, threadName, timestamp);
        eventBuffer.offer(vtEvent);
        eventsProcessed.incrementAndGet();
    }

    private void handleVirtualThreadPinned(RecordedEvent event) {
        long threadId = getThreadId(event);
        String threadName = getThreadName(event);
        long carrierThread = getCarrierThreadId(event);
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
        long threadId = getThreadId(event);
        String threadName = getThreadName(event);
        Instant timestamp = event.getStartTime();

        VirtualThreadEvent vtEvent = VirtualThreadEvent.submitFailed(threadId, threadName, timestamp);
        eventBuffer.offer(vtEvent);
        eventsProcessed.incrementAndGet();

        System.out.printf("[Argus] SUBMIT_FAILED: thread=%d%n", threadId);
    }

    private long getThreadId(RecordedEvent event) {
        // Try different field names for compatibility
        try {
            return event.getLong("javaThreadId");
        } catch (Exception e1) {
            try {
                RecordedThread thread = event.getValue("eventThread");
                if (thread != null) {
                    return thread.getJavaThreadId();
                }
            } catch (Exception e2) {
                // Fallback: try thread field
                try {
                    RecordedThread thread = event.getValue("thread");
                    if (thread != null) {
                        return thread.getJavaThreadId();
                    }
                } catch (Exception e3) {
                    // ignore
                }
            }
        }
        return -1;
    }

    private String getThreadName(RecordedEvent event) {
        try {
            RecordedThread thread = event.getValue("eventThread");
            if (thread != null) {
                return thread.getJavaName();
            }
        } catch (Exception e1) {
            try {
                RecordedThread thread = event.getValue("thread");
                if (thread != null) {
                    return thread.getJavaName();
                }
            } catch (Exception e2) {
                // ignore
            }
        }
        return null;
    }

    private long getCarrierThreadId(RecordedEvent event) {
        try {
            RecordedThread carrier = event.getValue("carrierThread");
            if (carrier != null) {
                return carrier.getJavaThreadId();
            }
        } catch (Exception e) {
            // ignore
        }
        return -1;
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
