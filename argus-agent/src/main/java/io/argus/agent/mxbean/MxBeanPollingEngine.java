package io.argus.agent.mxbean;

import io.argus.core.buffer.RingBuffer;
import io.argus.core.event.CPUEvent;
import io.argus.core.event.GCEvent;
import io.argus.core.event.VirtualThreadEvent;

import java.lang.management.GarbageCollectorMXBean;
import java.lang.management.ManagementFactory;
import java.lang.management.MemoryMXBean;
import java.lang.management.OperatingSystemMXBean;
import java.lang.management.ThreadMXBean;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * MXBean-based polling engine for Java 17+ environments where JFR streaming
 * or virtual thread events are not available.
 *
 * <p>Provides GC, CPU, and memory metrics by polling JMX MXBeans at configurable
 * intervals. This is a fallback for JVMs that don't support JFR RecordingStream
 * or virtual threads (pre-Java 21).
 *
 * <p>Writes the same event types to the same RingBuffers as JfrStreamingEngine,
 * so the server and dashboard work identically regardless of which engine is active.
 */
public final class MxBeanPollingEngine {

    private final RingBuffer<VirtualThreadEvent> eventBuffer;
    private final RingBuffer<GCEvent> gcEventBuffer;
    private final RingBuffer<CPUEvent> cpuEventBuffer;

    private final boolean gcEnabled;
    private final boolean cpuEnabled;
    private final int cpuIntervalMs;

    private final AtomicBoolean running = new AtomicBoolean(false);
    private final AtomicLong eventsProcessed = new AtomicLong(0);

    private ScheduledExecutorService scheduler;

    // Track GC state for delta calculation
    private final Map<String, Long> lastGcCounts = new HashMap<>();
    private final Map<String, Long> lastGcTimes = new HashMap<>();

    public MxBeanPollingEngine(RingBuffer<VirtualThreadEvent> eventBuffer,
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
    }

    public void start() {
        if (!running.compareAndSet(false, true)) {
            System.err.println("[Argus] MXBean polling engine already running");
            return;
        }

        scheduler = Executors.newScheduledThreadPool(2, r -> {
            Thread t = new Thread(r, "argus-mxbean-poller");
            t.setDaemon(true);
            return t;
        });

        if (gcEnabled && gcEventBuffer != null) {
            // Initialize baseline GC counts
            initGcBaseline();
            scheduler.scheduleAtFixedRate(this::pollGC, 1000, 2000, TimeUnit.MILLISECONDS);
        }

        if (cpuEnabled && cpuEventBuffer != null) {
            scheduler.scheduleAtFixedRate(this::pollCPU, 500, cpuIntervalMs, TimeUnit.MILLISECONDS);
        }

        System.out.println("[Argus] MXBean polling engine started (Java " +
                Runtime.version().feature() + " compatibility mode)");
    }

    public void stop() {
        if (!running.compareAndSet(true, false)) return;
        if (scheduler != null) {
            scheduler.shutdownNow();
            try {
                scheduler.awaitTermination(2, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        System.out.println("[Argus] MXBean polling stopped. Events processed: " + eventsProcessed.get());
    }

    public boolean isRunning() {
        return running.get();
    }

    public long getEventsProcessed() {
        return eventsProcessed.get();
    }

    private void initGcBaseline() {
        List<GarbageCollectorMXBean> gcs = ManagementFactory.getGarbageCollectorMXBeans();
        for (GarbageCollectorMXBean gc : gcs) {
            lastGcCounts.put(gc.getName(), gc.getCollectionCount());
            lastGcTimes.put(gc.getName(), gc.getCollectionTime());
        }
    }

    private void pollGC() {
        if (!running.get()) return;
        try {
            List<GarbageCollectorMXBean> gcs = ManagementFactory.getGarbageCollectorMXBeans();
            MemoryMXBean mem = ManagementFactory.getMemoryMXBean();
            long heapUsed = mem.getHeapMemoryUsage().getUsed();
            long heapCommitted = mem.getHeapMemoryUsage().getCommitted();

            for (GarbageCollectorMXBean gc : gcs) {
                long currentCount = gc.getCollectionCount();
                long currentTime = gc.getCollectionTime();
                long prevCount = lastGcCounts.getOrDefault(gc.getName(), 0L);
                long prevTime = lastGcTimes.getOrDefault(gc.getName(), 0L);

                if (currentCount > prevCount) {
                    long newCollections = currentCount - prevCount;
                    long newTimeMs = currentTime - prevTime;
                    long avgPauseMs = newCollections > 0 ? newTimeMs / newCollections : 0;

                    for (long i = 0; i < newCollections; i++) {
                        GCEvent event = GCEvent.combined(
                                Instant.now(),
                                avgPauseMs * 1_000_000L,  // convert ms to nanos
                                gc.getName(),
                                "MXBean Poll",
                                heapUsed,
                                heapUsed,
                                heapCommitted
                        );
                        gcEventBuffer.offer(event);
                        eventsProcessed.incrementAndGet();
                    }

                    lastGcCounts.put(gc.getName(), currentCount);
                    lastGcTimes.put(gc.getName(), currentTime);
                }
            }
        } catch (Exception e) {
            // Silently continue on polling errors
        }
    }

    private void pollCPU() {
        if (!running.get()) return;
        try {
            OperatingSystemMXBean os = ManagementFactory.getOperatingSystemMXBean();
            double systemLoad = os.getSystemLoadAverage();

            // Try to get process CPU via com.sun.management if available
            double processCpu = -1;
            if (os instanceof com.sun.management.OperatingSystemMXBean sunOs) {
                processCpu = sunOs.getProcessCpuLoad();
                systemLoad = sunOs.getSystemCpuLoad();
            }

            CPUEvent event = CPUEvent.of(
                    Instant.now(),
                    Math.max(0, processCpu),
                    0.0,
                    Math.max(0, systemLoad)
            );
            cpuEventBuffer.offer(event);
            eventsProcessed.incrementAndGet();
        } catch (Exception e) {
            // Silently continue
        }
    }
}
