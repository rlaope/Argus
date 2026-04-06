package io.argus.cli.jfr;

import io.argus.cli.model.JfrAnalyzeResult;
import io.argus.cli.model.JfrAnalyzeResult.AllocationSite;
import io.argus.cli.model.JfrAnalyzeResult.ContentionSite;
import io.argus.cli.model.JfrAnalyzeResult.HotMethod;

import jdk.jfr.consumer.RecordedEvent;
import jdk.jfr.consumer.RecordedFrame;
import jdk.jfr.consumer.RecordedMethod;
import jdk.jfr.consumer.RecordedStackTrace;
import jdk.jfr.consumer.RecordingFile;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Analyzes a JFR recording file and produces a comprehensive summary.
 * Uses jdk.jfr.consumer.RecordingFile API (Java 11+).
 */
public final class JfrAnalyzer {

    private static final int TOP_N = 15;

    public static JfrAnalyzeResult analyze(Path jfrFile) throws IOException {
        // GC
        int gcEventCount = 0;
        long totalGcPauseNs = 0;
        long maxGcPauseNs = 0;
        Map<String, Integer> gcCauses = new HashMap<>();

        // CPU
        List<Double> cpuLoadSamples = new ArrayList<>();
        List<Double> systemCpuSamples = new ArrayList<>();

        // Hot methods
        Map<String, Integer> methodSamples = new HashMap<>();
        int totalSamples = 0;

        // Allocations
        Map<String, long[]> allocations = new HashMap<>(); // className -> [totalBytes, count]

        // Contention
        Map<String, long[]> contentions = new HashMap<>(); // monitorClass -> [totalDurationNs, count]

        // Exceptions
        Map<String, Integer> exceptions = new HashMap<>();

        // I/O
        long fileReadCount = 0, fileWriteCount = 0;
        long socketReadCount = 0, socketWriteCount = 0;
        long totalFileReadBytes = 0, totalSocketReadBytes = 0;

        // Timing
        Instant earliest = null;
        Instant latest = null;
        long totalEvents = 0;

        try (RecordingFile rf = new RecordingFile(jfrFile)) {
            while (rf.hasMoreEvents()) {
                RecordedEvent event = rf.readEvent();
                totalEvents++;

                Instant startTime = event.getStartTime();
                if (earliest == null || startTime.isBefore(earliest)) earliest = startTime;
                Instant endTime = event.getEndTime();
                if (latest == null || endTime.isAfter(latest)) latest = endTime;

                String eventType = event.getEventType().getName();

                switch (eventType) {
                    case "jdk.GarbageCollection" -> {
                        gcEventCount++;
                        long durationNs = event.getDuration().toNanos();
                        totalGcPauseNs += durationNs;
                        if (durationNs > maxGcPauseNs) maxGcPauseNs = durationNs;
                        try {
                            String cause = event.getString("cause");
                            if (cause != null) gcCauses.merge(cause, 1, Integer::sum);
                        } catch (Exception ignored) {}
                    }
                    case "jdk.GCPhasePause", "jdk.GCPhasePauseLevel1" -> {
                        long durationNs = event.getDuration().toNanos();
                        totalGcPauseNs += durationNs;
                        if (durationNs > maxGcPauseNs) maxGcPauseNs = durationNs;
                    }
                    case "jdk.CPULoad" -> {
                        try {
                            double jvmUser = event.getDouble("jvmUser");
                            double jvmSystem = event.getDouble("jvmSystem");
                            double machineTotal = event.getDouble("machineTotal");
                            cpuLoadSamples.add(jvmUser + jvmSystem);
                            systemCpuSamples.add(machineTotal);
                        } catch (Exception ignored) {}
                    }
                    case "jdk.ExecutionSample", "jdk.NativeMethodSample" -> {
                        totalSamples++;
                        RecordedStackTrace stack = event.getStackTrace();
                        if (stack != null && !stack.getFrames().isEmpty()) {
                            RecordedFrame topFrame = stack.getFrames().getFirst();
                            RecordedMethod method = topFrame.getMethod();
                            if (method != null) {
                                String methodName = method.getType().getName() + "." + method.getName();
                                methodSamples.merge(methodName, 1, Integer::sum);
                            }
                        }
                    }
                    case "jdk.ObjectAllocationInNewTLAB", "jdk.ObjectAllocationOutsideTLAB",
                         "jdk.ObjectAllocationSample" -> {
                        try {
                            String className = "unknown";
                            try { className = event.getClass("objectClass").getName(); } catch (Exception ignored2) {}
                            long bytes = 0;
                            try { bytes = event.getLong("allocationSize"); } catch (Exception ignored2) {
                                try { bytes = event.getLong("tlabSize"); } catch (Exception ignored3) {}
                            }
                            allocations.computeIfAbsent(className, k -> new long[2]);
                            allocations.get(className)[0] += bytes;
                            allocations.get(className)[1]++;
                        } catch (Exception ignored) {}
                    }
                    case "jdk.JavaMonitorEnter" -> {
                        try {
                            String monitorClass = "unknown";
                            try { monitorClass = event.getClass("monitorClass").getName(); } catch (Exception ignored2) {}
                            long durationNs = event.getDuration().toNanos();
                            contentions.computeIfAbsent(monitorClass, k -> new long[2]);
                            contentions.get(monitorClass)[0] += durationNs;
                            contentions.get(monitorClass)[1]++;
                        } catch (Exception ignored) {}
                    }
                    case "jdk.JavaExceptionThrow" -> {
                        try {
                            String exClass = event.getClass("thrownClass").getName();
                            exceptions.merge(exClass, 1, Integer::sum);
                        } catch (Exception ignored) {}
                    }
                    case "jdk.FileRead" -> {
                        fileReadCount++;
                        try { totalFileReadBytes += event.getLong("bytesRead"); } catch (Exception ignored) {}
                    }
                    case "jdk.FileWrite" -> fileWriteCount++;
                    case "jdk.SocketRead" -> {
                        socketReadCount++;
                        try { totalSocketReadBytes += event.getLong("bytesRead"); } catch (Exception ignored) {}
                    }
                    case "jdk.SocketWrite" -> socketWriteCount++;
                }
            }
        }

        long durationMs = (earliest != null && latest != null)
                ? Duration.between(earliest, latest).toMillis() : 0;

        // Build hot methods top N
        int finalTotalSamples = totalSamples;
        List<HotMethod> hotMethods = methodSamples.entrySet().stream()
                .sorted(Map.Entry.<String, Integer>comparingByValue().reversed())
                .limit(TOP_N)
                .map(e -> new HotMethod(e.getKey(), e.getValue(),
                        finalTotalSamples > 0 ? (double) e.getValue() / finalTotalSamples * 100 : 0))
                .toList();

        // Build top allocations
        List<AllocationSite> topAllocations = allocations.entrySet().stream()
                .sorted((a, b) -> Long.compare(b.getValue()[0], a.getValue()[0]))
                .limit(TOP_N)
                .map(e -> new AllocationSite(e.getKey(), e.getValue()[0], (int) e.getValue()[1]))
                .toList();

        // Build top contention
        List<ContentionSite> topContention = contentions.entrySet().stream()
                .sorted((a, b) -> Long.compare(b.getValue()[0], a.getValue()[0]))
                .limit(TOP_N)
                .map(e -> new ContentionSite(e.getKey(), e.getValue()[0] / 1_000_000, (int) e.getValue()[1]))
                .toList();

        // Sort GC causes by count descending
        Map<String, Integer> sortedGcCauses = new LinkedHashMap<>();
        gcCauses.entrySet().stream()
                .sorted(Map.Entry.<String, Integer>comparingByValue().reversed())
                .forEach(e -> sortedGcCauses.put(e.getKey(), e.getValue()));

        // Sort exceptions
        Map<String, Integer> sortedExceptions = new LinkedHashMap<>();
        exceptions.entrySet().stream()
                .sorted(Map.Entry.<String, Integer>comparingByValue().reversed())
                .limit(TOP_N)
                .forEach(e -> sortedExceptions.put(e.getKey(), e.getValue()));

        double avgCpu = cpuLoadSamples.stream().mapToDouble(d -> d).average().orElse(0);
        double maxCpu = cpuLoadSamples.stream().mapToDouble(d -> d).max().orElse(0);
        double avgSysCpu = systemCpuSamples.stream().mapToDouble(d -> d).average().orElse(0);

        return new JfrAnalyzeResult(
                jfrFile.toString(), durationMs, totalEvents,
                gcEventCount, totalGcPauseNs / 1_000_000, maxGcPauseNs / 1_000_000,
                sortedGcCauses,
                avgCpu, maxCpu, avgSysCpu,
                hotMethods, topAllocations, topContention, sortedExceptions,
                fileReadCount, fileWriteCount, socketReadCount, socketWriteCount,
                totalFileReadBytes, totalSocketReadBytes
        );
    }
}
