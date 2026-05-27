package io.argus.diagnostics.g1;

import jdk.jfr.consumer.RecordedEvent;
import jdk.jfr.consumer.RecordedFrame;
import jdk.jfr.consumer.RecordedStackTrace;
import jdk.jfr.consumer.RecordingFile;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Reads a JFR recording produced by {@code jcmd JFR.start ... settings=profile}
 * and populates a {@link G1Diagnosis}.
 *
 * <p>Subscribed events:
 * <ul>
 *   <li>{@code jdk.G1GarbageCollection}                — G1 cycle type (Young/Mixed/Concurrent).</li>
 *   <li>{@code jdk.G1HeapSummary}                       — region counts (eden/survivor/old/humongous).</li>
 *   <li>{@code jdk.G1EvacuationYoungStatistics}         — young evacuation bytes + failure flag.</li>
 *   <li>{@code jdk.G1EvacuationOldStatistics}           — old evacuation bytes + failure flag.</li>
 *   <li>{@code jdk.G1MMU}                                — minimum mutator utilization samples.</li>
 *   <li>{@code jdk.G1AdaptiveIHOP}                       — predicted vs. actual IHOP threshold.</li>
 *   <li>{@code jdk.GarbageCollection}                    — Pause Young (Mixed/Prepare Mixed) + Full GC labels.</li>
 *   <li>{@code jdk.ObjectAllocationOutsideTLAB}          — humongous-class allocation hotspots.</li>
 * </ul>
 *
 * <p>G1-specific events fire only when G1 is the active GC; they are silently
 * absent on other collectors and the corresponding fields stay zero.
 */
public final class G1JfrCollector {

    private G1JfrCollector() {}

    /**
     * Reads the JFR file and fills the supplied diagnosis. The {@code usingG1},
     * {@code jvmVersion}, {@code regionSizeMb}, {@code targetPauseMs},
     * {@code ihopPercent}, {@code adaptiveIhop}, {@code heapCommittedBytes},
     * and {@code maxHeapBytes} fields are expected to be set by the caller
     * before invocation; this method only updates the JFR-derived fields.
     */
    public static void collect(Path jfrFile, G1Diagnosis d) throws IOException {
        long youngPauseNs = 0; int youngPauseCount = 0;
        long mixedPauseNs = 0; int mixedPauseCount = 0;
        long maxPauseNs   = 0;

        double mmuSum = 0; int mmuCount = 0; double mmuMin = Double.POSITIVE_INFINITY;

        boolean concurrentCycleFinished = false;
        boolean mixedPauseSeenAfterConcurrent = false;

        long humongousMaxBytes = 0;
        long totalHumongousEvents = 0;
        Map<String, long[]> humongousByFrame = new HashMap<>(); // [count, maxBytes]

        long regionSizeBytes = d.regionSizeMb > 0 ? (long) d.regionSizeMb * 1024L * 1024L : 0;

        try (RecordingFile rf = new RecordingFile(jfrFile)) {
            while (rf.hasMoreEvents()) {
                RecordedEvent event = rf.readEvent();
                String typeName = event.getEventType().getName();

                switch (typeName) {
                    case "jdk.G1GarbageCollection": {
                        String type = safeGetString(event, "type");
                        long durNs = event.getDuration().toNanos();
                        if (durNs > maxPauseNs) maxPauseNs = durNs;
                        if (type != null && type.toLowerCase().contains("mixed")) {
                            mixedPauseNs += durNs;
                            mixedPauseCount++;
                            d.mixedCycles++;
                            if (concurrentCycleFinished) {
                                mixedPauseSeenAfterConcurrent = true;
                            }
                        } else if (type != null && (type.toLowerCase().contains("concurrent")
                                || type.toLowerCase().contains("normal start"))) {
                            d.concurrentCycles++;
                            concurrentCycleFinished = true;
                        } else {
                            // Default: treat as a young pause cycle.
                            youngPauseNs += durNs;
                            youngPauseCount++;
                            d.youngCycles++;
                        }
                        break;
                    }
                    case "jdk.G1HeapSummary": {
                        // Last sample wins — we want the most recent snapshot.
                        int eden = safeGetInt(event, "edenUsedRegions");
                        int sur  = safeGetInt(event, "survivorUsedRegions");
                        int old  = safeGetInt(event, "oldUsedRegions");
                        int hum  = safeGetInt(event, "humongousUsedRegions");
                        int tot  = safeGetInt(event, "numberOfRegions");
                        if (eden > 0 || sur > 0 || old > 0 || hum > 0) {
                            d.edenRegions      = eden;
                            d.survivorRegions  = sur;
                            d.oldRegions       = old;
                            d.humongousRegions = hum;
                        }
                        if (tot > 0) d.totalRegions = tot;
                        break;
                    }
                    case "jdk.G1EvacuationYoungStatistics": {
                        long bytes = safeGetLong(event, "totalBytes");
                        if (bytes > 0) d.bytesCopiedYoung += bytes;
                        if (safeGetBool(event, "failed")) {
                            d.evacuationFailures++;
                            d.evacuationFailureSeen = true;
                        }
                        break;
                    }
                    case "jdk.G1EvacuationOldStatistics": {
                        long bytes = safeGetLong(event, "totalBytes");
                        if (bytes > 0) d.bytesCopiedOld += bytes;
                        if (safeGetBool(event, "failed")) {
                            d.evacuationFailures++;
                            d.evacuationFailureSeen = true;
                        }
                        break;
                    }
                    case "jdk.G1MMU": {
                        // jdk.G1MMU reports a percent (0..100) of mutator availability.
                        double pct = safeGetDouble(event, "mutatorUtilization");
                        if (pct <= 0) pct = safeGetDouble(event, "utilization");
                        if (pct > 0) {
                            mmuSum += pct;
                            mmuCount++;
                            if (pct < mmuMin) mmuMin = pct;
                        }
                        break;
                    }
                    case "jdk.G1AdaptiveIHOP": {
                        double predicted = safeGetDouble(event, "thresholdPercentage");
                        if (predicted <= 0) predicted = safeGetDouble(event, "predictedThreshold");
                        double actual = safeGetDouble(event, "currentOccupancyPercentage");
                        if (actual <= 0) actual = safeGetDouble(event, "actualOccupancyPercentage");
                        if (predicted > 0) d.predictedIhopPercent = predicted;
                        if (actual > 0)    d.actualIhopPercent    = actual;
                        break;
                    }
                    case "jdk.GarbageCollection": {
                        // Catch Full GC + label-based fallback for older JDKs where
                        // jdk.G1GarbageCollection is unavailable.
                        String name = safeGetString(event, "name");
                        String cause = safeGetString(event, "cause");
                        long durNs = event.getDuration().toNanos();
                        if (durNs > maxPauseNs) maxPauseNs = durNs;
                        if (name == null) break;
                        if (name.contains("Full") || name.contains("System.gc")) {
                            d.fullGcCycles++;
                            d.fullGcSeen = true;
                        }
                        if (cause != null && cause.contains("Humongous")) {
                            d.humongousAllocationCycles++;
                        }
                        break;
                    }
                    case "jdk.ObjectAllocationOutsideTLAB": {
                        long bytes = safeGetLong(event, "allocationSize");
                        if (regionSizeBytes > 0 && bytes >= regionSizeBytes / 2) {
                            totalHumongousEvents++;
                            if (bytes > humongousMaxBytes) humongousMaxBytes = bytes;
                            String topFrame = extractTopUserFrame(event);
                            if (topFrame != null) {
                                long[] agg = humongousByFrame.computeIfAbsent(
                                        topFrame, k -> new long[2]);
                                agg[0]++;
                                if (bytes > agg[1]) agg[1] = bytes;
                            }
                        }
                        break;
                    }
                    default:
                        break;
                }
            }
        }

        // ── Aggregates ───────────────────────────────────────────────────────
        if (youngPauseCount > 0) d.avgYoungPauseMs = (youngPauseNs / 1_000_000.0) / youngPauseCount;
        if (mixedPauseCount > 0) d.avgMixedPauseMs = (mixedPauseNs / 1_000_000.0) / mixedPauseCount;
        d.maxPauseMs = maxPauseNs / 1_000_000.0;

        if (mmuCount > 0) {
            d.avgMmuPercent = mmuSum / mmuCount;
            d.minMmuPercent = mmuMin == Double.POSITIVE_INFINITY ? 0 : mmuMin;
        }

        // Mixed starvation: concurrent cycle finished but no Mixed pause seen after.
        if (concurrentCycleFinished && !mixedPauseSeenAfterConcurrent) {
            d.mixedStarvation = true;
        }

        // IHOP mistiming: > 15pp delta AND a concurrent cycle was observed.
        if (d.predictedIhopPercent > 0 && d.actualIhopPercent > 0
                && Math.abs(d.predictedIhopPercent - d.actualIhopPercent) > 15.0
                && d.concurrentCycles > 0) {
            d.ihopMistimed = true;
        }

        // Build humongous hotspots (top-5 by count).
        d.totalHumongousAllocEvents = totalHumongousEvents;
        if (!humongousByFrame.isEmpty()) {
            final int TOP_K = 5;
            List<Map.Entry<String, long[]>> sorted = new ArrayList<>(humongousByFrame.entrySet());
            sorted.sort((a, b) -> Long.compare(b.getValue()[0], a.getValue()[0]));
            for (int i = 0; i < Math.min(TOP_K, sorted.size()); i++) {
                Map.Entry<String, long[]> e = sorted.get(i);
                d.humongousHotspots.add(new G1Diagnosis.HumongousHotspot(
                        e.getKey(), e.getValue()[0], e.getValue()[1]));
            }
        }
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    private static String safeGetString(RecordedEvent event, String field) {
        try { return event.getString(field); } catch (Exception e) { return ""; }
    }

    private static long safeGetLong(RecordedEvent event, String field) {
        try { return event.getLong(field); } catch (Exception e) { return 0; }
    }

    private static int safeGetInt(RecordedEvent event, String field) {
        try { return event.getInt(field); }
        catch (Exception e) {
            try { return (int) event.getLong(field); } catch (Exception e2) { return 0; }
        }
    }

    private static double safeGetDouble(RecordedEvent event, String field) {
        try { return event.getDouble(field); } catch (Exception e) { return 0.0; }
    }

    private static boolean safeGetBool(RecordedEvent event, String field) {
        try { return event.getBoolean(field); } catch (Exception e) { return false; }
    }

    /**
     * Walks the event stack trace and returns the first non-JDK frame formatted as
     * {@code ClassName.methodName(FileName:line)}. Falls back to the first frame
     * when all frames belong to JDK-internal packages. Returns {@code null} when
     * no stack trace is present.
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
        return fallback != null ? formatFrame(fallback) : null;
    }

    private static String formatFrame(RecordedFrame frame) {
        String cls  = frame.getMethod().getType().getName();
        String mth  = frame.getMethod().getName();
        int    line = frame.getLineNumber();
        String simpleClass = cls.contains(".") ? cls.substring(cls.lastIndexOf('.') + 1) : cls;
        String fileName = simpleClass.contains("$")
                ? simpleClass.substring(0, simpleClass.indexOf('$')) + ".java"
                : simpleClass + ".java";
        return cls + "." + mth + "(" + fileName + ":" + line + ")";
    }
}
