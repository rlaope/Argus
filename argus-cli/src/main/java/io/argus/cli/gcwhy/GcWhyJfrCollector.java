package io.argus.cli.gcwhy;

import io.argus.diagnostics.gclog.GcEvent;
import jdk.jfr.consumer.RecordedEvent;
import jdk.jfr.consumer.RecordedObject;
import jdk.jfr.consumer.RecordingFile;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Reads a JFR recording file and extracts GC events as {@link GcEvent} objects
 * suitable for analysis by {@link GcWhyAnalyzer}.
 *
 * <p>Subscribes to:
 * <ul>
 *   <li>{@code jdk.GarbageCollection} — pause type, cause, duration, gcId</li>
 *   <li>{@code jdk.GCHeapSummary} — heap used/committed before and after each GC</li>
 * </ul>
 *
 * <p>Uses Java 11+ {@code jdk.jfr.consumer.RecordingFile} API (same approach as
 * {@code AllocationProfiler} and {@code JfrAnalyzer} in this codebase).
 */
public final class GcWhyJfrCollector {

    private GcWhyJfrCollector() {}

    /**
     * Reads {@code jfrFile} and returns a list of {@link GcEvent} objects ordered
     * by timestamp ascending. Events with no duration (concurrent phases) are
     * included so the analyzer can filter them via {@link GcEvent#isConcurrent()}.
     *
     * @param jfrFile path to the .jfr recording produced by jcmd JFR.dump
     * @return list of GcEvents, possibly empty
     * @throws IOException if the file cannot be read
     */
    public static List<GcEvent> collect(Path jfrFile) throws IOException {

        // gcId -> GcCollectionInfo (populated from jdk.GarbageCollection)
        Map<Long, GcCollectionInfo> collections = new HashMap<>();

        // gcId -> [heapBeforeKB, heapAfterKB, heapTotalKB]
        // Values are filled by jdk.GCHeapSummary before/after events.
        Map<Long, long[]> heapBefore = new HashMap<>();  // [usedKB, committedKB]
        Map<Long, long[]> heapAfter  = new HashMap<>();

        Instant recordingStart = null;

        try (RecordingFile rf = new RecordingFile(jfrFile)) {
            while (rf.hasMoreEvents()) {
                RecordedEvent event = rf.readEvent();
                String typeName = event.getEventType().getName();

                // Track the earliest event time to derive relative timestamps.
                Instant startTime = event.getStartTime();
                if (recordingStart == null || startTime.isBefore(recordingStart)) {
                    recordingStart = startTime;
                }

                switch (typeName) {
                    case "jdk.GarbageCollection": {
                        long gcId = safeGetLong(event, "gcId");
                        String name  = safeGetString(event, "name");   // e.g. "G1Young", "G1Full"
                        String cause = safeGetString(event, "cause");  // e.g. "G1 Evacuation Pause"
                        long durationNs = event.getDuration().toNanos();
                        collections.put(gcId, new GcCollectionInfo(gcId, name, cause, startTime, durationNs));
                        break;
                    }
                    case "jdk.GCHeapSummary": {
                        long gcId = safeGetLong(event, "gcId");
                        String when = safeGetString(event, "when"); // GCWhen enum: "Before GC" or "After GC"
                        long heapUsed = safeGetLong(event, "heapUsed"); // bytes
                        long heapCommitted = 0;
                        try {
                            RecordedObject heapSpace = event.getValue("heapSpace");
                            if (heapSpace != null) {
                                heapCommitted = heapSpace.getLong("committedSize");
                            }
                        } catch (Exception ignored) {}
                        long usedKB = heapUsed / 1024;
                        long committedKB = heapCommitted / 1024;
                        // JFR GCWhen enum labels are "Before GC" and "After GC".
                        if (when != null && when.toLowerCase().contains("before")) {
                            heapBefore.put(gcId, new long[]{usedKB, committedKB});
                        } else if (when != null && when.toLowerCase().contains("after")) {
                            heapAfter.put(gcId, new long[]{usedKB, committedKB});
                        }
                        break;
                    }
                    default:
                        break;
                }
            }
        }

        if (collections.isEmpty()) {
            return List.of();
        }

        Instant epoch = recordingStart != null ? recordingStart : Instant.EPOCH;
        List<GcEvent> events = new ArrayList<>(collections.size());

        for (GcCollectionInfo info : collections.values()) {
            double tsSec = (info.startTime().toEpochMilli() - epoch.toEpochMilli()) / 1000.0;
            double pauseMs = info.durationNs() / 1_000_000.0;

            long[] before = heapBefore.getOrDefault(info.gcId(), new long[]{0, 0});
            long[] after  = heapAfter.getOrDefault(info.gcId(), new long[]{0, 0});

            // heapTotalKB: prefer committed-after (reflects post-GC committed), fall back to before.
            long heapBeforeKB = before[0];
            long heapAfterKB  = after[0];
            long heapTotalKB  = after[1] > 0 ? after[1] : before[1];

            // Derive a type label consistent with the log-based parser conventions:
            // "Young", "Mixed", "Full", or "Concurrent <name>"
            String type = deriveType(info.name());

            events.add(new GcEvent(tsSec, type, info.cause(), pauseMs,
                    heapBeforeKB, heapAfterKB, heapTotalKB));
        }

        events.sort((a, b) -> Double.compare(a.timestampSec(), b.timestampSec()));
        return events;
    }

    // ── Type normalization ───────────────────────────────────────────────────

    /**
     * Maps a JFR GC collector name to the type label used by {@link GcEvent}
     * and expected by {@link GcWhyAnalyzer}.
     *
     * <p>JFR names (non-exhaustive): G1Young, G1Old, G1Full, G1Concurrent,
     * ParallelScavenge, ParallelMarkSweep, SerialOld, ZGC, Shenandoah.
     */
    private static String deriveType(String name) {
        if (name == null) return "Young";
        String lower = name.toLowerCase();
        if (lower.contains("concurrent") || lower.contains("zgc") || lower.contains("shenandoah")) {
            return "Concurrent " + name;
        }
        if (lower.contains("full") || lower.contains("old") || lower.contains("serial")) {
            return "Full";
        }
        if (lower.contains("mixed")) {
            return "Mixed";
        }
        // G1Young, ParallelScavenge, etc.
        return "Young";
    }

    // ── Safe field accessors ─────────────────────────────────────────────────

    private static long safeGetLong(RecordedEvent event, String field) {
        try { return event.getLong(field); } catch (Exception e) { return 0L; }
    }

    private static String safeGetString(RecordedEvent event, String field) {
        try { return event.getString(field); } catch (Exception e) { return ""; }
    }

    // ── Internal value type ──────────────────────────────────────────────────

    private static final class GcCollectionInfo {
        final long gcId;
        final String name;
        final String cause;
        final Instant startTime;
        final long durationNs;
        GcCollectionInfo(long gcId, String name, String cause, Instant startTime, long durationNs) {
            this.gcId = gcId;
            this.name = name;
            this.cause = cause;
            this.startTime = startTime;
            this.durationNs = durationNs;
        }
        long gcId() { return gcId; }
        String name() { return name; }
        String cause() { return cause; }
        Instant startTime() { return startTime; }
        long durationNs() { return durationNs; }
    }
}
