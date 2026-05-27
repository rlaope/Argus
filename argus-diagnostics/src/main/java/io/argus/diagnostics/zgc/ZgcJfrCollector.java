package io.argus.diagnostics.zgc;

import jdk.jfr.consumer.RecordedEvent;
import jdk.jfr.consumer.RecordedFrame;
import jdk.jfr.consumer.RecordedObject;
import jdk.jfr.consumer.RecordedStackTrace;
import jdk.jfr.consumer.RecordingFile;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

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

        // Allocation hotspot accumulators (key = formatted top-user-frame).
        Map<String, Long> allocCounts = new HashMap<>();

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
                    case "jdk.ZAllocationStall": {
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
                        break;
                    }
                    case "jdk.ZYoungGarbageCollection": {
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
                        break;
                    }
                    case "jdk.ZOldGarbageCollection": {
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
                        break;
                    }
                    case "jdk.ZGarbageCollection": {
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
                        break;
                    }
                    case "jdk.GarbageCollection": {
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
                        } else if (name.contains("Concurrent Mark")
                                && !name.contains("Free")
                                && !name.contains("Roots")) {
                            d.concurrentMarkMs += durNs / 1_000_000.0;
                            d.concurrentMarkSamples++;
                        } else if (name.contains("Concurrent Relocate")) {
                            d.concurrentRelocateMs += durNs / 1_000_000.0;
                            d.concurrentRelocateSamples++;
                        }
                        break;
                    }
                    case "jdk.GCHeapSummary": {
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
                        break;
                    }
                    case "jdk.ObjectAllocationInNewTLAB":
                    case "jdk.ObjectAllocationOutsideTLAB": {
                        String topFrame = extractTopUserFrame(event);
                        if (topFrame != null) {
                            allocCounts.merge(topFrame, 1L, Long::sum);
                        }
                        break;
                    }
                    case "jdk.ZPageAllocation": {
                        d.zPageAllocationCount++;
                        break;
                    }
                    case "jdk.ZUncommit": {
                        d.zUncommitEvents++;
                        // ZUncommit reports `to`/`from` (committed range collapsed back); sum the delta.
                        try {
                            long from = event.getLong("from");
                            long to   = event.getLong("to");
                            if (from > to) d.zUncommittedBytes += (from - to);
                        } catch (Exception ignored) {
                            // Fallback: try `size` field on older JDKs.
                            try { d.zUncommittedBytes += event.getLong("size"); }
                            catch (Exception ignored2) {}
                        }
                        break;
                    }
                    default:
                        break;
                }
            }
        }

        // Build allocation hotspots (only when stalls were observed).
        long totalAlloc = allocCounts.values().stream().mapToLong(Long::longValue).sum();
        d.totalAllocEvents = totalAlloc;
        if (!d.stalls.isEmpty() && totalAlloc > 0) {
            final int TOP_K = 5;
            List<Map.Entry<String, Long>> sorted = new ArrayList<>(allocCounts.entrySet());
            sorted.sort(Map.Entry.<String, Long>comparingByValue(Comparator.reverseOrder()));
            for (int i = 0; i < Math.min(TOP_K, sorted.size()); i++) {
                Map.Entry<String, Long> e = sorted.get(i);
                double pct = e.getValue() * 100.0 / totalAlloc;
                d.stallAllocHotspots.add(new ZgcDiagnosis.AllocHotspot(e.getKey(), e.getValue(), pct));
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

    /**
     * Walks the event stack trace and returns the first non-JDK frame formatted as
     * {@code ClassName.methodName(FileName:line)}. Falls back to the first frame when
     * all frames belong to JDK-internal packages. Returns {@code null} when no stack
     * trace is present.
     */
    static String extractTopUserFrame(RecordedEvent event) {
        RecordedStackTrace stack = event.getStackTrace();
        if (stack == null) return null;
        List<RecordedFrame> frames = stack.getFrames();
        if (frames.isEmpty()) return null;

        RecordedFrame fallback = null;
        for (RecordedFrame frame : frames) {
            if (frame.getMethod() == null) continue;
            String cls = frame.getMethod().getType().getName();
            if (cls == null) continue;
            if (fallback == null) fallback = frame;
            if (!cls.startsWith("java.")
                    && !cls.startsWith("jdk.")
                    && !cls.startsWith("sun.")
                    && !cls.startsWith("com.sun.")) {
                return formatFrame(frame);
            }
        }
        // All frames were JDK-internal — use first frame as fallback.
        return fallback != null ? formatFrame(fallback) : null;
    }

    private static String formatFrame(RecordedFrame frame) {
        String cls  = frame.getMethod().getType().getName();
        String mth  = frame.getMethod().getName();
        int    line = frame.getLineNumber();
        // Extract simple file name from class name (ClassName → ClassName.java).
        String simpleClass = cls.contains(".") ? cls.substring(cls.lastIndexOf('.') + 1) : cls;
        // Strip inner-class suffix for the file name.
        String fileName = simpleClass.contains("$")
                ? simpleClass.substring(0, simpleClass.indexOf('$')) + ".java"
                : simpleClass + ".java";
        return cls + "." + mth + "(" + fileName + ":" + line + ")";
    }
}
