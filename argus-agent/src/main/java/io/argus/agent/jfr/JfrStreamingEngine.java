package io.argus.agent.jfr;

import io.argus.core.buffer.RingBuffer;
import io.argus.core.event.AllocationEvent;
import io.argus.core.event.ContentionEvent;
import io.argus.core.event.CPUEvent;
import io.argus.core.event.ExecutionSampleEvent;
import io.argus.core.event.GCEvent;
import io.argus.core.event.MetaspaceEvent;
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
 *   <li>{@code jdk.ObjectAllocationInNewTLAB} - Object allocation events</li>
 *   <li>{@code jdk.MetaspaceSummary} - Metaspace usage</li>
 *   <li>{@code jdk.ExecutionSample} - CPU profiling samples</li>
 *   <li>{@code jdk.JavaMonitorEnter} - Lock contention</li>
 *   <li>{@code jdk.JavaMonitorWait} - Lock wait events</li>
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

    // Allocation events
    private static final String EVENT_ALLOCATION_TLAB = "jdk.ObjectAllocationInNewTLAB";
    private static final String EVENT_ALLOCATION_OUTSIDE_TLAB = "jdk.ObjectAllocationOutsideTLAB";
    private static final String EVENT_METASPACE = "jdk.MetaspaceSummary";

    // Profiling events
    private static final String EVENT_EXECUTION_SAMPLE = "jdk.ExecutionSample";

    // Contention events
    private static final String EVENT_MONITOR_ENTER = "jdk.JavaMonitorEnter";
    private static final String EVENT_MONITOR_WAIT = "jdk.JavaMonitorWait";

    private final RingBuffer<VirtualThreadEvent> eventBuffer;
    private final RingBuffer<GCEvent> gcEventBuffer;
    private final RingBuffer<CPUEvent> cpuEventBuffer;
    private final RingBuffer<AllocationEvent> allocationEventBuffer;
    private final RingBuffer<MetaspaceEvent> metaspaceEventBuffer;
    private final RingBuffer<ExecutionSampleEvent> executionSampleEventBuffer;
    private final RingBuffer<ContentionEvent> contentionEventBuffer;

    private final JfrEventExtractor extractor;
    private final GCEventExtractor gcExtractor;
    private final CPUEventExtractor cpuExtractor;
    private final AllocationEventExtractor allocationExtractor;
    private final MetaspaceEventExtractor metaspaceExtractor;
    private final ExecutionSampleExtractor executionSampleExtractor;
    private final ContentionEventExtractor contentionExtractor;

    private final AtomicBoolean running = new AtomicBoolean(false);
    private final AtomicLong eventsProcessed = new AtomicLong(0);
    private final AtomicLong gcEventsProcessed = new AtomicLong(0);
    private final AtomicLong cpuEventsProcessed = new AtomicLong(0);
    private final AtomicLong allocationEventsProcessed = new AtomicLong(0);
    private final AtomicLong metaspaceEventsProcessed = new AtomicLong(0);
    private final AtomicLong executionSampleEventsProcessed = new AtomicLong(0);
    private final AtomicLong contentionEventsProcessed = new AtomicLong(0);
    private final CountDownLatch startedLatch = new CountDownLatch(1);

    // Configuration
    private final boolean gcEnabled;
    private final boolean cpuEnabled;
    private final int cpuIntervalMs;
    private final boolean allocationEnabled;
    private final int allocationThreshold;
    private final boolean metaspaceEnabled;
    private final boolean profilingEnabled;
    private final int profilingIntervalMs;
    private final boolean contentionEnabled;
    private final int contentionThresholdMs;

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
        this(eventBuffer, null, null, null, null, null, null,
                false, false, 1000, false, 1024, false, false, 20, false, 10);
    }

    /**
     * Creates a new JFR streaming engine with basic event capture support (backward compatible).
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
        this(eventBuffer, gcEventBuffer, cpuEventBuffer, null, null, null, null,
                gcEnabled, cpuEnabled, cpuIntervalMs, false, 1024, false, false, 20, false, 10);
    }

    /**
     * Creates a new JFR streaming engine with full event capture support.
     *
     * @param eventBuffer              the ring buffer for virtual thread events
     * @param gcEventBuffer            the ring buffer for GC events
     * @param cpuEventBuffer           the ring buffer for CPU events
     * @param allocationEventBuffer    the ring buffer for allocation events
     * @param metaspaceEventBuffer     the ring buffer for metaspace events
     * @param executionSampleBuffer    the ring buffer for execution sample events
     * @param contentionEventBuffer    the ring buffer for contention events
     * @param gcEnabled                whether to capture GC events
     * @param cpuEnabled               whether to capture CPU events
     * @param cpuIntervalMs            CPU sampling interval in milliseconds
     * @param allocationEnabled        whether to capture allocation events
     * @param allocationThreshold      minimum allocation size to track
     * @param metaspaceEnabled         whether to capture metaspace events
     * @param profilingEnabled         whether to capture execution samples
     * @param profilingIntervalMs      profiling sampling interval in milliseconds
     * @param contentionEnabled        whether to capture contention events
     * @param contentionThresholdMs    minimum contention time to track in ms
     */
    public JfrStreamingEngine(RingBuffer<VirtualThreadEvent> eventBuffer,
                              RingBuffer<GCEvent> gcEventBuffer,
                              RingBuffer<CPUEvent> cpuEventBuffer,
                              RingBuffer<AllocationEvent> allocationEventBuffer,
                              RingBuffer<MetaspaceEvent> metaspaceEventBuffer,
                              RingBuffer<ExecutionSampleEvent> executionSampleBuffer,
                              RingBuffer<ContentionEvent> contentionEventBuffer,
                              boolean gcEnabled,
                              boolean cpuEnabled,
                              int cpuIntervalMs,
                              boolean allocationEnabled,
                              int allocationThreshold,
                              boolean metaspaceEnabled,
                              boolean profilingEnabled,
                              int profilingIntervalMs,
                              boolean contentionEnabled,
                              int contentionThresholdMs) {
        this.eventBuffer = eventBuffer;
        this.gcEventBuffer = gcEventBuffer;
        this.cpuEventBuffer = cpuEventBuffer;
        this.allocationEventBuffer = allocationEventBuffer;
        this.metaspaceEventBuffer = metaspaceEventBuffer;
        this.executionSampleEventBuffer = executionSampleBuffer;
        this.contentionEventBuffer = contentionEventBuffer;

        this.gcEnabled = gcEnabled;
        this.cpuEnabled = cpuEnabled;
        this.cpuIntervalMs = cpuIntervalMs;
        this.allocationEnabled = allocationEnabled;
        this.allocationThreshold = allocationThreshold;
        this.metaspaceEnabled = metaspaceEnabled;
        this.profilingEnabled = profilingEnabled;
        this.profilingIntervalMs = profilingIntervalMs;
        this.contentionEnabled = contentionEnabled;
        this.contentionThresholdMs = contentionThresholdMs;

        this.extractor = new JfrEventExtractor();
        this.gcExtractor = gcEnabled ? new GCEventExtractor() : null;
        this.cpuExtractor = cpuEnabled ? new CPUEventExtractor() : null;
        this.allocationExtractor = allocationEnabled ? new AllocationEventExtractor() : null;
        this.metaspaceExtractor = metaspaceEnabled ? new MetaspaceEventExtractor() : null;
        this.executionSampleExtractor = profilingEnabled ? new ExecutionSampleExtractor() : null;
        this.contentionExtractor = contentionEnabled ? new ContentionEventExtractor() : null;
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

        // Enable allocation events if configured
        if (allocationEnabled) {
            rs.enable(EVENT_ALLOCATION_TLAB).withoutThreshold();
            rs.enable(EVENT_ALLOCATION_OUTSIDE_TLAB).withoutThreshold();
            System.out.printf("[Argus] Allocation tracking enabled (threshold: %d bytes)%n", allocationThreshold);
        }

        // Enable metaspace events if configured
        if (metaspaceEnabled) {
            rs.enable(EVENT_METASPACE).withoutThreshold();
            System.out.println("[Argus] Metaspace monitoring enabled");
        }

        // Enable profiling events if configured
        if (profilingEnabled) {
            rs.enable(EVENT_EXECUTION_SAMPLE).withPeriod(Duration.ofMillis(profilingIntervalMs));
            System.out.printf("[Argus] Method profiling enabled (interval: %dms)%n", profilingIntervalMs);
        }

        // Enable contention events if configured
        if (contentionEnabled) {
            rs.enable(EVENT_MONITOR_ENTER).withThreshold(Duration.ofMillis(contentionThresholdMs));
            rs.enable(EVENT_MONITOR_WAIT).withThreshold(Duration.ofMillis(contentionThresholdMs));
            System.out.printf("[Argus] Contention tracking enabled (threshold: %dms)%n", contentionThresholdMs);
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

        // Allocation event handlers
        if (allocationEnabled) {
            rs.onEvent(EVENT_ALLOCATION_TLAB, this::handleAllocation);
            rs.onEvent(EVENT_ALLOCATION_OUTSIDE_TLAB, this::handleAllocation);
        }

        // Metaspace event handlers
        if (metaspaceEnabled) {
            rs.onEvent(EVENT_METASPACE, this::handleMetaspace);
        }

        // Profiling event handlers
        if (profilingEnabled) {
            rs.onEvent(EVENT_EXECUTION_SAMPLE, this::handleExecutionSample);
        }

        // Contention event handlers
        if (contentionEnabled) {
            rs.onEvent(EVENT_MONITOR_ENTER, this::handleMonitorEnter);
            rs.onEvent(EVENT_MONITOR_WAIT, this::handleMonitorWait);
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

    private void handleAllocation(RecordedEvent event) {
        if (allocationEventBuffer == null || allocationExtractor == null) return;

        AllocationEvent allocationEvent = allocationExtractor.extractAllocation(event);

        // Apply threshold filter
        if (allocationEvent.allocationSize() >= allocationThreshold) {
            allocationEventBuffer.offer(allocationEvent);
            allocationEventsProcessed.incrementAndGet();
        }
    }

    private void handleMetaspace(RecordedEvent event) {
        if (metaspaceEventBuffer == null || metaspaceExtractor == null) return;

        MetaspaceEvent metaspaceEvent = metaspaceExtractor.extractMetaspace(event);
        metaspaceEventBuffer.offer(metaspaceEvent);
        metaspaceEventsProcessed.incrementAndGet();
    }

    private void handleExecutionSample(RecordedEvent event) {
        if (executionSampleEventBuffer == null || executionSampleExtractor == null) return;

        ExecutionSampleEvent sampleEvent = executionSampleExtractor.extractExecutionSample(event);
        if (sampleEvent != null) {
            executionSampleEventBuffer.offer(sampleEvent);
            executionSampleEventsProcessed.incrementAndGet();
        }
    }

    private void handleMonitorEnter(RecordedEvent event) {
        if (contentionEventBuffer == null || contentionExtractor == null) return;

        ContentionEvent contentionEvent = contentionExtractor.extractMonitorEnter(event);
        contentionEventBuffer.offer(contentionEvent);
        contentionEventsProcessed.incrementAndGet();
    }

    private void handleMonitorWait(RecordedEvent event) {
        if (contentionEventBuffer == null || contentionExtractor == null) return;

        ContentionEvent contentionEvent = contentionExtractor.extractMonitorWait(event);
        contentionEventBuffer.offer(contentionEvent);
        contentionEventsProcessed.incrementAndGet();
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

    /**
     * Returns the total number of allocation events processed.
     *
     * @return allocation event count
     */
    public long getAllocationEventsProcessed() {
        return allocationEventsProcessed.get();
    }

    /**
     * Returns the total number of metaspace events processed.
     *
     * @return metaspace event count
     */
    public long getMetaspaceEventsProcessed() {
        return metaspaceEventsProcessed.get();
    }

    /**
     * Returns the total number of execution sample events processed.
     *
     * @return execution sample event count
     */
    public long getExecutionSampleEventsProcessed() {
        return executionSampleEventsProcessed.get();
    }

    /**
     * Returns the total number of contention events processed.
     *
     * @return contention event count
     */
    public long getContentionEventsProcessed() {
        return contentionEventsProcessed.get();
    }

    /**
     * Returns whether allocation tracking is enabled.
     *
     * @return true if allocation tracking is enabled
     */
    public boolean isAllocationEnabled() {
        return allocationEnabled;
    }

    /**
     * Returns whether metaspace monitoring is enabled.
     *
     * @return true if metaspace monitoring is enabled
     */
    public boolean isMetaspaceEnabled() {
        return metaspaceEnabled;
    }

    /**
     * Returns whether method profiling is enabled.
     *
     * @return true if method profiling is enabled
     */
    public boolean isProfilingEnabled() {
        return profilingEnabled;
    }

    /**
     * Returns whether contention tracking is enabled.
     *
     * @return true if contention tracking is enabled
     */
    public boolean isContentionEnabled() {
        return contentionEnabled;
    }
}
