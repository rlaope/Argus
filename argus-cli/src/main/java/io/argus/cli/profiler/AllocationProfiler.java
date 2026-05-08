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
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
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

                RecordedFrame top = stack.getFrames().get(0);
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
     */
    public static final class AllocationProfile {
        private final List<AllocationSite> sites;
        private final long totalBytes;
        private final long totalAllocations;
        private final double durationSec;

        public AllocationProfile(List<AllocationSite> sites, long totalBytes,
                                 long totalAllocations, double durationSec) {
            this.sites = sites;
            this.totalBytes = totalBytes;
            this.totalAllocations = totalAllocations;
            this.durationSec = durationSec;
        }

        public List<AllocationSite> sites() { return sites; }
        public long totalBytes() { return totalBytes; }
        public long totalAllocations() { return totalAllocations; }
        public double durationSec() { return durationSec; }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (!(o instanceof AllocationProfile)) return false;
            AllocationProfile that = (AllocationProfile) o;
            return totalBytes == that.totalBytes
                    && totalAllocations == that.totalAllocations
                    && Double.compare(that.durationSec, durationSec) == 0
                    && java.util.Objects.equals(sites, that.sites);
        }

        @Override
        public int hashCode() {
            return java.util.Objects.hash(sites, totalBytes, totalAllocations, durationSec);
        }

        @Override
        public String toString() {
            return "AllocationProfile[sites=" + sites + ", totalBytes=" + totalBytes
                    + ", totalAllocations=" + totalAllocations + ", durationSec=" + durationSec + "]";
        }
    }

    /**
     * A single allocation hotspot aggregated from JFR stack top frames.
     */
    public static final class AllocationSite {
        private final String className;
        private final String methodName;
        private final int lineNumber;
        private final long totalBytes;
        private final long allocationCount;
        private final double bytesPerSec;

        public AllocationSite(String className, String methodName, int lineNumber,
                              long totalBytes, long allocationCount, double bytesPerSec) {
            this.className = className;
            this.methodName = methodName;
            this.lineNumber = lineNumber;
            this.totalBytes = totalBytes;
            this.allocationCount = allocationCount;
            this.bytesPerSec = bytesPerSec;
        }

        public String className() { return className; }
        public String methodName() { return methodName; }
        public int lineNumber() { return lineNumber; }
        public long totalBytes() { return totalBytes; }
        public long allocationCount() { return allocationCount; }
        public double bytesPerSec() { return bytesPerSec; }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (!(o instanceof AllocationSite)) return false;
            AllocationSite that = (AllocationSite) o;
            return lineNumber == that.lineNumber
                    && totalBytes == that.totalBytes
                    && allocationCount == that.allocationCount
                    && Double.compare(that.bytesPerSec, bytesPerSec) == 0
                    && java.util.Objects.equals(className, that.className)
                    && java.util.Objects.equals(methodName, that.methodName);
        }

        @Override
        public int hashCode() {
            return java.util.Objects.hash(className, methodName, lineNumber,
                    totalBytes, allocationCount, bytesPerSec);
        }

        @Override
        public String toString() {
            return "AllocationSite[className=" + className + ", methodName=" + methodName
                    + ", lineNumber=" + lineNumber + ", totalBytes=" + totalBytes
                    + ", allocationCount=" + allocationCount + ", bytesPerSec=" + bytesPerSec + "]";
        }
    }

    /**
     * Aggregated allocation view grouped by the <em>allocated type</em> rather than stack frame.
     * Answers "which classes are burning allocation bytes".
     */
    public static final class AllocationByClass {
        private final List<AllocatedType> sites;
        private final long totalBytes;
        private final double durationSec;

        public AllocationByClass(List<AllocatedType> sites, long totalBytes, double durationSec) {
            this.sites = sites;
            this.totalBytes = totalBytes;
            this.durationSec = durationSec;
        }

        public List<AllocatedType> sites() { return sites; }
        public long totalBytes() { return totalBytes; }
        public double durationSec() { return durationSec; }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (!(o instanceof AllocationByClass)) return false;
            AllocationByClass that = (AllocationByClass) o;
            return totalBytes == that.totalBytes
                    && Double.compare(that.durationSec, durationSec) == 0
                    && java.util.Objects.equals(sites, that.sites);
        }

        @Override
        public int hashCode() {
            return java.util.Objects.hash(sites, totalBytes, durationSec);
        }

        @Override
        public String toString() {
            return "AllocationByClass[sites=" + sites + ", totalBytes=" + totalBytes
                    + ", durationSec=" + durationSec + "]";
        }
    }

    /** A single allocated-type row: the class that was allocated, not the code that allocated it. */
    public static final class AllocatedType {
        private final String className;
        private final long totalBytes;
        private final long allocationCount;
        private final double bytesPerSec;

        public AllocatedType(String className, long totalBytes,
                             long allocationCount, double bytesPerSec) {
            this.className = className;
            this.totalBytes = totalBytes;
            this.allocationCount = allocationCount;
            this.bytesPerSec = bytesPerSec;
        }

        public String className() { return className; }
        public long totalBytes() { return totalBytes; }
        public long allocationCount() { return allocationCount; }
        public double bytesPerSec() { return bytesPerSec; }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (!(o instanceof AllocatedType)) return false;
            AllocatedType that = (AllocatedType) o;
            return totalBytes == that.totalBytes
                    && allocationCount == that.allocationCount
                    && Double.compare(that.bytesPerSec, bytesPerSec) == 0
                    && java.util.Objects.equals(className, that.className);
        }

        @Override
        public int hashCode() {
            return java.util.Objects.hash(className, totalBytes, allocationCount, bytesPerSec);
        }

        @Override
        public String toString() {
            return "AllocatedType[className=" + className + ", totalBytes=" + totalBytes
                    + ", allocationCount=" + allocationCount + ", bytesPerSec=" + bytesPerSec + "]";
        }
    }

    // -------------------------------------------------------------------------
    // New: group by allocated class
    // -------------------------------------------------------------------------

    /**
     * Analyzes a JFR file and aggregates allocation events by the <em>allocated object's class</em>
     * (the {@code objectClass} event field), producing a top-allocated-types view that complements
     * the stack-frame view produced by {@link #analyze(Path)}.
     */
    public static AllocationByClass analyzeByClass(Path jfrFile) throws IOException {
        Map<String, long[]> agg = new HashMap<>();
        long totalBytes = 0;
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

                long size = readAllocationSize(event);
                String className;
                try {
                    className = event.getClass("objectClass").getName();
                } catch (Exception ignored) {
                    className = "<unknown>";
                }

                agg.computeIfAbsent(className, k -> new long[2]);
                agg.get(className)[0] += size;
                agg.get(className)[1] += 1;
                totalBytes += size;
            }
        }

        double durationSec = (earliest != null && latest != null)
                ? Duration.between(earliest, latest).toNanos() / 1_000_000_000.0 : 0.0;
        if (durationSec <= 0.0) durationSec = 1.0;

        final double dur = durationSec;
        List<AllocatedType> rows = new ArrayList<>();
        for (Map.Entry<String, long[]> e : agg.entrySet()) {
            long bytes = e.getValue()[0];
            long count = e.getValue()[1];
            rows.add(new AllocatedType(e.getKey(), bytes, count, bytes / dur));
        }
        rows.sort((a, b) -> Long.compare(b.totalBytes(), a.totalBytes()));

        return new AllocationByClass(rows, totalBytes, durationSec);
    }

    // -------------------------------------------------------------------------
    // New: folded stacks (flamegraph.pl input format)
    // -------------------------------------------------------------------------

    /**
     * Produces a folded-stacks map keyed by semicolon-joined stack (leaf last),
     * with values equal to total allocated bytes. Suitable as input to
     * {@code flamegraph.pl} (Brendan Gregg).
     */
    public static Map<String, Long> analyzeFoldedStacks(Path jfrFile) throws IOException {
        Map<String, Long> folded = new LinkedHashMap<>();

        try (RecordingFile rf = new RecordingFile(jfrFile)) {
            while (rf.hasMoreEvents()) {
                RecordedEvent event = rf.readEvent();
                String eventType = event.getEventType().getName();
                if (!eventType.equals("jdk.ObjectAllocationInNewTLAB")
                        && !eventType.equals("jdk.ObjectAllocationOutsideTLAB")) {
                    continue;
                }

                RecordedStackTrace stack = event.getStackTrace();
                if (stack == null || stack.getFrames().isEmpty()) continue;

                long size = readAllocationSize(event);
                if (size <= 0) continue;

                // flamegraph.pl expects root-first, leaf-last; JFR frames are leaf-first.
                List<RecordedFrame> frames = stack.getFrames();
                StringBuilder sb = new StringBuilder();
                for (int i = frames.size() - 1; i >= 0; i--) {
                    RecordedFrame f = frames.get(i);
                    if (f.getMethod() == null) continue;
                    String cls = f.getMethod().getType().getName();
                    String mth = f.getMethod().getName();
                    if (sb.length() > 0) sb.append(';');
                    sb.append(cls).append('.').append(mth);
                }
                if (sb.length() == 0) continue;

                folded.merge(sb.toString(), size, Long::sum);
            }
        }

        // Sort entries deterministically so output is stable.
        return folded.entrySet().stream()
                .sorted(Map.Entry.<String, Long>comparingByValue(Comparator.reverseOrder()))
                .collect(LinkedHashMap::new, (m, e) -> m.put(e.getKey(), e.getValue()), LinkedHashMap::putAll);
    }

    private static long readAllocationSize(RecordedEvent event) {
        try { return event.getLong("allocationSize"); } catch (Exception ignored) {}
        try { return event.getLong("tlabSize"); } catch (Exception ignored) {}
        return 0;
    }
}
