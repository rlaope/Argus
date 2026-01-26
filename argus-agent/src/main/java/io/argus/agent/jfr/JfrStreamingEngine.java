package io.argus.agent.jfr;

import io.argus.core.buffer.RingBuffer;
import io.argus.core.event.CPUEvent;
import io.argus.core.event.GCEvent;
import io.argus.core.event.VirtualThreadEvent;

import jdk.jfr.consumer.RecordedEvent;
import jdk.jfr.consumer.RecordingStream;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
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
 *   <li>{@code jdk.VirtualThreadStart} - Thread creation</li>
 *   <li>{@code jdk.VirtualThreadEnd} - Thread termination</li>
 *   <li>{@code jdk.VirtualThreadPinned} - Pinning detection (critical for Loom)</li>
 *   <li>{@code jdk.VirtualThreadSubmitFailed} - Submit failures</li>
 *   <li>{@code jdk.GarbageCollection} - GC pause events</li>
 *   <li>{@code jdk.GCHeapSummary} - Heap usage summary</li>
 *   <li>{@code jdk.CPULoad} - CPU load metrics</li>
 * </ul>
 *
 * @see JfrEventExtractor
 */
public final class JfrStreamingEngine {

    // Virtual Thread events
    private static final String EVENT_VIRTUAL_THREAD_START = "jdk.VirtualThreadStart";
    private static final String EVENT_VIRTUAL_THREAD_END = "jdk.VirtualThreadEnd";
    private static final String EVENT_VIRTUAL_THREAD_PINNED = "jdk.VirtualThreadPinned";
    private static final String EVENT_VIRTUAL_THREAD_SUBMIT_FAILED = "jdk.VirtualThreadSubmitFailed";

    // GC events
    private static final String EVENT_GC = "jdk.GarbageCollection";
    private static final String EVENT_GC_HEAP = "jdk.GCHeapSummary";

    // CPU events
    private static final String EVENT_CPU_LOAD = "jdk.CPULoad";

    private final RingBuffer<VirtualThreadEvent> eventBuffer;
    private final RingBuffer<GCEvent> gcEventBuffer;
    private final RingBuffer<CPUEvent> cpuEventBuffer;
    private final JfrEventExtractor extractor;
    private final GCEventExtractor gcExtractor;
    private final CPUEventExtractor cpuExtractor;
    private final AtomicBoolean running = new AtomicBoolean(false);
    private final AtomicLong eventsProcessed = new AtomicLong(0);
    private final AtomicLong gcEventsProcessed = new AtomicLong(0);
    private final AtomicLong cpuEventsProcessed = new AtomicLong(0);
    private final CountDownLatch startedLatch = new CountDownLatch(1);

    // Configuration
    private final boolean gcEnabled;
    private final boolean cpuEnabled;
    private final int cpuIntervalMs;

    // Track thread start times for duration calculation
    private final Map<Long, Instant> threadStartTimes = new ConcurrentHashMap<>();

    private volatile RecordingStream recordingStream;
    private volatile Thread streamThread;

    /**
     * Creates a new JFR streaming engine with only virtual thread event capture.
     *
     * @param eventBuffer the ring buffer to write events to
     */
    public JfrStreamingEngine(RingBuffer<VirtualThreadEvent> eventBuffer) {
        this(eventBuffer, null, null, false, false, 1000);
    }

    /**
     * Creates a new JFR streaming engine with full event capture support.
     *
     * @param eventBuffer    the ring buffer for virtual thread events
     * @param gcEventBuffer  the ring buffer for GC events (can be null if gcEnabled is false)
     * @param cpuEventBuffer the ring buffer for CPU events (can be null if cpuEnabled is false)
     * @param gcEnabled      whether to capture GC events
     * @param cpuEnabled     whether to capture CPU events
     * @param cpuIntervalMs  CPU sampling interval in milliseconds
     */
    public JfrStreamingEngine(RingBuffer<VirtualThreadEvent> eventBuffer,
                              RingBuffer<GCEvent> gcEventBuffer,
                              RingBuffer<CPUEvent> cpuEventBuffer,
                              boolean gcEnabled,
                              boolean cpuEnabled,
                              int cpuIntervalMs) {
        this.eventBuffer = eventBuffer;
        this.gcEventBuffer = gcEventBuffer;
        this.cpuEventBuffer = cpuEventBuffer;
        this.gcEnabled = gcEnabled;
        this.cpuEnabled = cpuEnabled;
        this.cpuIntervalMs = cpuIntervalMs;
        this.extractor = new JfrEventExtractor();
        this.gcExtractor = gcEnabled ? new GCEventExtractor() : null;
        this.cpuExtractor = cpuEnabled ? new CPUEventExtractor() : null;
    }

    /**
     * Starts the JFR streaming engine and waits for it to be ready.
     *
     * <p>This method blocks until the JFR stream is actually started,
     * ensuring events are captured from the moment the agent returns.
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

            // Configure events
            configureEvents(rs);

            // Register handlers
            registerEventHandlers(rs);

            System.out.println("[Argus] JFR streaming started");
            rs.startAsync();

            // Signal that streaming is ready
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

    private void configureEvents(RecordingStream rs) {
        // Enable virtual thread events with zero threshold for immediate recording
        rs.enable(EVENT_VIRTUAL_THREAD_START).withoutThreshold();
        rs.enable(EVENT_VIRTUAL_THREAD_END).withoutThreshold();
        rs.enable(EVENT_VIRTUAL_THREAD_PINNED).withStackTrace().withoutThreshold();
        rs.enable(EVENT_VIRTUAL_THREAD_SUBMIT_FAILED).withoutThreshold();

        // Enable GC events if configured
        if (gcEnabled) {
            rs.enable(EVENT_GC).withoutThreshold();
            rs.enable(EVENT_GC_HEAP).withoutThreshold();
            System.out.println("[Argus] GC monitoring enabled");
        }

        // Enable CPU events if configured
        if (cpuEnabled) {
            rs.enable(EVENT_CPU_LOAD).withPeriod(Duration.ofMillis(cpuIntervalMs));
            System.out.printf("[Argus] CPU monitoring enabled (interval: %dms)%n", cpuIntervalMs);
        }

        // Set buffer settings
        rs.setMaxAge(Duration.ofSeconds(10));
        rs.setMaxSize(10 * 1024 * 1024); // 10 MB
    }

    private void registerEventHandlers(RecordingStream rs) {
        // Virtual thread event handlers
        rs.onEvent(EVENT_VIRTUAL_THREAD_START, this::handleStart);
        rs.onEvent(EVENT_VIRTUAL_THREAD_END, this::handleEnd);
        rs.onEvent(EVENT_VIRTUAL_THREAD_PINNED, this::handlePinned);
        rs.onEvent(EVENT_VIRTUAL_THREAD_SUBMIT_FAILED, this::handleSubmitFailed);

        // GC event handlers
        if (gcEnabled) {
            rs.onEvent(EVENT_GC, this::handleGC);
            rs.onEvent(EVENT_GC_HEAP, this::handleGCHeapSummary);
        }

        // CPU event handlers
        if (cpuEnabled) {
            rs.onEvent(EVENT_CPU_LOAD, this::handleCPULoad);
        }
    }

    private void handleStart(RecordedEvent event) {
        long threadId = extractor.extractThreadId(event);
        String threadName = extractor.extractThreadName(event);
        Instant timestamp = event.getStartTime();

        // Track start time for duration calculation
        threadStartTimes.put(threadId, timestamp);

        VirtualThreadEvent vtEvent = VirtualThreadEvent.start(threadId, threadName, timestamp);
        eventBuffer.offer(vtEvent);
        eventsProcessed.incrementAndGet();
    }

    private void handleEnd(RecordedEvent event) {
        long threadId = extractor.extractThreadId(event);
        String threadName = extractor.extractThreadName(event);
        Instant timestamp = event.getStartTime();

        // Calculate duration from tracked start time
        long durationNanos = 0;
        Instant startTime = threadStartTimes.remove(threadId);
        if (startTime != null) {
            durationNanos = Duration.between(startTime, timestamp).toNanos();
        }

        VirtualThreadEvent vtEvent = VirtualThreadEvent.end(threadId, threadName, timestamp, durationNanos);
        eventBuffer.offer(vtEvent);
        eventsProcessed.incrementAndGet();
    }

    private void handlePinned(RecordedEvent event) {
        long threadId = extractor.extractThreadId(event);
        String threadName = extractor.extractThreadName(event);
        // Note: JDK 21's jdk.VirtualThreadPinned event does not include carrier thread info
        long carrierThread = extractor.extractCarrierThreadId(event);
        Instant timestamp = event.getStartTime();
        long duration = event.getDuration().toNanos();
        String stackTrace = extractor.formatStackTrace(event.getStackTrace());

        VirtualThreadEvent vtEvent = VirtualThreadEvent.pinned(
                threadId, threadName, carrierThread, timestamp, duration, stackTrace);
        eventBuffer.offer(vtEvent);
        eventsProcessed.incrementAndGet();

        // Log pinning events as they are critical for performance
        System.out.printf("[Argus] PINNED: thread=%d (%s), duration=%dms%n",
                threadId, threadName != null ? threadName : "unnamed", duration / 1_000_000);
    }

    private void handleSubmitFailed(RecordedEvent event) {
        long threadId = extractor.extractThreadId(event);
        String threadName = extractor.extractThreadName(event);
        Instant timestamp = event.getStartTime();

        VirtualThreadEvent vtEvent = VirtualThreadEvent.submitFailed(threadId, threadName, timestamp);
        eventBuffer.offer(vtEvent);
        eventsProcessed.incrementAndGet();

        System.out.printf("[Argus] SUBMIT_FAILED: thread=%d%n", threadId);
    }

    private void handleGC(RecordedEvent event) {
        if (gcEventBuffer == null || gcExtractor == null) return;

        GCEvent gcEvent = gcExtractor.extractGarbageCollection(event);
        gcEventBuffer.offer(gcEvent);
        gcEventsProcessed.incrementAndGet();
    }

    private void handleGCHeapSummary(RecordedEvent event) {
        if (gcEventBuffer == null || gcExtractor == null) return;

        GCEvent gcEvent = gcExtractor.extractHeapSummary(event);
        gcEventBuffer.offer(gcEvent);
        gcEventsProcessed.incrementAndGet();
    }

    private void handleCPULoad(RecordedEvent event) {
        if (cpuEventBuffer == null || cpuExtractor == null) return;

        CPUEvent cpuEvent = cpuExtractor.extractCPULoad(event);
        cpuEventBuffer.offer(cpuEvent);
        cpuEventsProcessed.incrementAndGet();
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
     *
     * @return true if running
     */
    public boolean isRunning() {
        return running.get();
    }

    /**
     * Returns the total number of virtual thread events processed.
     *
     * @return event count
     */
    public long getEventsProcessed() {
        return eventsProcessed.get();
    }

    /**
     * Returns the total number of GC events processed.
     *
     * @return GC event count
     */
    public long getGcEventsProcessed() {
        return gcEventsProcessed.get();
    }

    /**
     * Returns the total number of CPU events processed.
     *
     * @return CPU event count
     */
    public long getCpuEventsProcessed() {
        return cpuEventsProcessed.get();
    }

    /**
     * Returns whether GC monitoring is enabled.
     *
     * @return true if GC monitoring is enabled
     */
    public boolean isGcEnabled() {
        return gcEnabled;
    }

    /**
     * Returns whether CPU monitoring is enabled.
     *
     * @return true if CPU monitoring is enabled
     */
    public boolean isCpuEnabled() {
        return cpuEnabled;
    }
}
