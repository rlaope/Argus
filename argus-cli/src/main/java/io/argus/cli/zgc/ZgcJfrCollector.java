package io.argus.cli.zgc;

import jdk.jfr.consumer.RecordedEvent;
import jdk.jfr.consumer.RecordedObject;
import jdk.jfr.consumer.RecordingFile;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Instant;

/**
 * Reads a JFR recording produced by {@code jcmd JFR.start ... settings=profile}
 * and populates a {@link ZgcDiagnosis}.
 *
 * <p>Subscribed events:
 * <ul>
 *   <li>{@code jdk.ZAllocationStall}            — per-thread allocation stalls.</li>
 *   <li>{@code jdk.ZYoungGarbageCollection}     — generational marker + minor cycle count.</li>
 *   <li>{@code jdk.ZOldGarbageCollection}       — generational marker + major cycle count.</li>
 *   <li>{@code jdk.ZGarbageCollection}          — non-generational ZGC cycles.</li>
 *   <li>{@code jdk.GarbageCollection}           — STW phase samples (Pause Mark Start / End / Relocate Start).</li>
 *   <li>{@code jdk.GCHeapSummary}               — committed-heap samples for soft-max breach detection.</li>
 * </ul>
 *
 * <p>The 5 ZGC-specific events only fire when ZGC is the active GC; they are
 * silently absent on other collectors and the corresponding fields stay zero.
 */
public final class ZgcJfrCollector {

    private ZgcJfrCollector() {}

    /**
     * Reads the JFR file and fills the supplied diagnosis. The {@code usingZgc},
     * {@code generational}, {@code maxHeapBytes}, and {@code softMaxHeapBytes}
     * fields are expected to be set by the caller before invocation; this method
     * only updates the JFR-derived fields.
     */
    public static void collect(Path jfrFile, ZgcDiagnosis d) throws IOException {
        // Cycle accumulators (non-generational ZGC).
        long zgcDurationNs = 0;
        int  zgcCycles     = 0;
        Instant firstZgcStart = null;
        Instant lastZgcStart  = null;

        // Cycle overlap detection across Z*GarbageCollection events
        // (use a single ordered scan: if any next.start < previous.end, overlap=true).
        Instant prevZEnd = null;

        // STW phase accumulators (jdk.GarbageCollection with name "Pause Mark Start" etc.)
        long markStartNs = 0; int markStartCount = 0;
        long markEndNs   = 0; int markEndCount   = 0;
        long relocStartNs = 0; int relocStartCount = 0;

        long maxCommittedSeen = 0;

        try (RecordingFile rf = new RecordingFile(jfrFile)) {
            while (rf.hasMoreEvents()) {
                RecordedEvent event = rf.readEvent();
                String typeName = event.getEventType().getName();

                switch (typeName) {
                    case "jdk.ZAllocationStall" -> {
                        String threadName = "";
                        try {
                            RecordedObject thread = event.getValue("thread");
                            if (thread != null) {
                                threadName = thread.getString("javaName");
                                if (threadName == null || threadName.isEmpty()) {
                                    threadName = thread.getString("osName");
                                }
                            }
                        } catch (Exception ignored) {}
                        double durMs = event.getDuration().toNanos() / 1_000_000.0;
                        d.stalls.add(new ZgcDiagnosis.Stall(
                                threadName == null ? "" : threadName, durMs));
                    }

                    case "jdk.ZYoungGarbageCollection" -> {
                        d.generational = true;
                        d.minorCycles++;
                        Instant start = event.getStartTime();
                        Instant end = event.getEndTime();
                        if (firstZgcStart == null) firstZgcStart = start;
                        lastZgcStart = start;
                        zgcDurationNs += event.getDuration().toNanos();
                        zgcCycles++;
                        if (prevZEnd != null && start.isBefore(prevZEnd)) {
                            d.cycleOverlap = true;
                        }
                        prevZEnd = end;
                    }

                    case "jdk.ZOldGarbageCollection" -> {
                        d.generational = true;
                        d.majorCycles++;
                        Instant start = event.getStartTime();
                        Instant end = event.getEndTime();
                        if (firstZgcStart == null) firstZgcStart = start;
                        lastZgcStart = start;
                        zgcDurationNs += event.getDuration().toNanos();
                        zgcCycles++;
                        if (prevZEnd != null && start.isBefore(prevZEnd)) {
                            d.cycleOverlap = true;
                        }
                        prevZEnd = end;
                    }

                    case "jdk.ZGarbageCollection" -> {
                        // Non-generational cycle: counts toward majorCycles only when
                        // generational events haven't been observed; treat as cycle-count
                        // input so we still report something.
                        Instant start = event.getStartTime();
                        Instant end = event.getEndTime();
                        if (firstZgcStart == null) firstZgcStart = start;
                        lastZgcStart = start;
                        zgcDurationNs += event.getDuration().toNanos();
                        zgcCycles++;
                        if (!d.generational) {
                            d.majorCycles++;
                        }
                        if (prevZEnd != null && start.isBefore(prevZEnd)) {
                            d.cycleOverlap = true;
                        }
                        prevZEnd = end;
                    }

                    case "jdk.GarbageCollection" -> {
                        // The "name" field carries the pause label for ZGC STW phases.
                        String name = safeGetString(event, "name");
                        long durNs = event.getDuration().toNanos();
                        if (name == null) break;
                        if (name.contains("Pause Mark Start")) {
                            markStartNs += durNs; markStartCount++;
                        } else if (name.contains("Pause Mark End")) {
                            markEndNs += durNs; markEndCount++;
                        } else if (name.contains("Pause Relocate Start")) {
                            relocStartNs += durNs; relocStartCount++;
                        }
                    }

                    case "jdk.GCHeapSummary" -> {
                        try {
                            RecordedObject heapSpace = event.getValue("heapSpace");
                            if (heapSpace != null) {
                                long committed = heapSpace.getLong("committedSize");
                                if (committed > maxCommittedSeen) {
                                    maxCommittedSeen = committed;
                                }
                                if (d.softMaxHeapBytes > 0 && committed > d.softMaxHeapBytes) {
                                    d.softMaxBreached = true;
                                }
                            }
                        } catch (Exception ignored) {}
                    }
                }
            }
        }

        if (maxCommittedSeen > d.heapCommittedBytes) {
            d.heapCommittedBytes = maxCommittedSeen;
        }

        if (zgcCycles > 0) {
            d.avgCycleDurationSec = (zgcDurationNs / 1_000_000_000.0) / zgcCycles;
            if (firstZgcStart != null && lastZgcStart != null && zgcCycles > 1) {
                double spanSec = (lastZgcStart.toEpochMilli() - firstZgcStart.toEpochMilli()) / 1000.0;
                d.avgCycleIntervalSec = spanSec / (zgcCycles - 1);
            }
        }

        if (markStartCount  > 0) d.pauseMarkStartMs    = (markStartNs  / 1_000_000.0) / markStartCount;
        if (markEndCount    > 0) d.pauseMarkEndMs      = (markEndNs    / 1_000_000.0) / markEndCount;
        if (relocStartCount > 0) d.pauseRelocateStartMs = (relocStartNs / 1_000_000.0) / relocStartCount;
    }

    private static String safeGetString(RecordedEvent event, String field) {
        try { return event.getString(field); } catch (Exception e) { return ""; }
    }
}
