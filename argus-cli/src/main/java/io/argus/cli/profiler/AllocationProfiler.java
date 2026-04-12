package io.argus.cli.profiler;

import jdk.jfr.consumer.RecordedEvent;
import jdk.jfr.consumer.RecordedFrame;
import jdk.jfr.consumer.RecordedStackTrace;
import jdk.jfr.consumer.RecordingFile;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Parses a JFR recording file and aggregates allocation events by stack trace top frame.
 * Supports {@code jdk.ObjectAllocationInNewTLAB} and {@code jdk.ObjectAllocationOutsideTLAB}.
 */
public final class AllocationProfiler {

    private AllocationProfiler() {}

    /**
     * Analyzes a JFR file for allocation hotspots.
     *
     * @param jfrFile path to the .jfr recording file
     * @return aggregated allocation profile sorted by total bytes descending
     * @throws IOException if the file cannot be read
     */
    public static AllocationProfile analyze(Path jfrFile) throws IOException {
        // key: "className.methodName:line" -> [totalBytes, count]
        Map<String, long[]> sites = new HashMap<>();
        Map<String, String[]> siteNames = new HashMap<>(); // key -> [className, methodName, line]

        long totalBytes = 0;
        long totalAllocations = 0;
        Instant earliest = null;
        Instant latest = null;

        try (RecordingFile rf = new RecordingFile(jfrFile)) {
            while (rf.hasMoreEvents()) {
                RecordedEvent event = rf.readEvent();

                String eventType = event.getEventType().getName();
                if (!eventType.equals("jdk.ObjectAllocationInNewTLAB")
                        && !eventType.equals("jdk.ObjectAllocationOutsideTLAB")) {
                    continue;
                }

                Instant startTime = event.getStartTime();
                if (earliest == null || startTime.isBefore(earliest)) earliest = startTime;
                Instant endTime = event.getEndTime();
                if (latest == null || endTime.isAfter(latest)) latest = endTime;

                long allocationSize = 0;
                try {
                    allocationSize = event.getLong("allocationSize");
                } catch (Exception ignored) {
                    try {
                        allocationSize = event.getLong("tlabSize");
                    } catch (Exception ignored2) {}
                }

                RecordedStackTrace stack = event.getStackTrace();
                if (stack == null || stack.getFrames().isEmpty()) {
                    continue;
                }

                RecordedFrame top = stack.getFrames().getFirst();
                String className = top.getMethod().getType().getName();
                String methodName = top.getMethod().getName();
                int lineNumber = top.getLineNumber();
                String key = className + "." + methodName + ":" + lineNumber;

                sites.computeIfAbsent(key, k -> new long[2]);
                sites.get(key)[0] += allocationSize;
                sites.get(key)[1]++;
                siteNames.put(key, new String[]{className, methodName, String.valueOf(lineNumber)});

                totalBytes += allocationSize;
                totalAllocations++;
            }
        }

        double durationSec = (earliest != null && latest != null)
                ? Duration.between(earliest, latest).toNanos() / 1_000_000_000.0 : 0.0;
        if (durationSec <= 0.0) durationSec = 1.0; // avoid division by zero

        final double dur = durationSec;
        List<AllocationSite> result = new ArrayList<>();
        for (Map.Entry<String, long[]> entry : sites.entrySet()) {
            String key = entry.getKey();
            long[] agg = entry.getValue();
            String[] names = siteNames.get(key);
            int line = 0;
            try { line = Integer.parseInt(names[2]); } catch (NumberFormatException ignored) {}
            double bytesPerSec = agg[0] / dur;
            result.add(new AllocationSite(names[0], names[1], line, agg[0], agg[1], bytesPerSec));
        }

        result.sort((a, b) -> Long.compare(b.totalBytes(), a.totalBytes()));

        return new AllocationProfile(result, totalBytes, totalAllocations, durationSec);
    }

    // -------------------------------------------------------------------------
    // Records
    // -------------------------------------------------------------------------

    /**
     * Aggregated allocation profile from a JFR recording.
     *
     * @param sites            allocation sites sorted by total bytes descending
     * @param totalBytes       total bytes allocated across all events
     * @param totalAllocations total number of allocation events
     * @param durationSec      recording duration in seconds
     */
    public record AllocationProfile(
            List<AllocationSite> sites,
            long totalBytes,
            long totalAllocations,
            double durationSec
    ) {}

    /**
     * A single allocation hotspot aggregated from JFR stack top frames.
     *
     * @param className       fully-qualified class name
     * @param methodName      method name
     * @param lineNumber      source line number (0 if unknown)
     * @param totalBytes      total bytes allocated at this site
     * @param allocationCount number of allocation events at this site
     * @param bytesPerSec     allocation rate in bytes/second
     */
    public record AllocationSite(
            String className,
            String methodName,
            int lineNumber,
            long totalBytes,
            long allocationCount,
            double bytesPerSec
    ) {}
}
