package io.argus.cli.zgc;

import io.argus.cli.render.RichRenderer;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Persistent ZGC snapshot for trend / diff analysis.
 *
 * <p>Serialised as a plain-text properties file (key=value, one per line) so
 * no JSON library is required. Mirrors the approach used by NmtBaseline but
 * stores ZGC-specific fields from {@link ZgcDiagnosis}.
 *
 * <p>File format:
 * <pre>
 * version=1
 * capturedAt=2026-05-08T12:00:00Z
 * pid=12345
 * generational=true
 * heapCommittedBytes=4294967296
 * softMaxHeapBytes=4294967296
 * maxHeapBytes=8589934592
 * minorCycles=2400
 * majorCycles=200
 * avgCycleIntervalSec=0.750
 * avgCycleDurationSec=0.100
 * pauseMarkStartMs=0.10
 * pauseMarkEndMs=0.30
 * pauseRelocateStartMs=0.12
 * stallCount=0
 * stallTotalMs=0.00
 * stallMaxMs=0.00
 * stallMaxThread=
 * softMaxBreached=false
 * cycleOverlap=false
 * topAllocFrames=com.example.Foo.bar(Foo.java:42)|35.2;com.example.Bar.baz(Bar.java:10)|20.1
 * </pre>
 */
public final class ZgcBaseline {

    // ── Fields ───────────────────────────────────────────────────────────────

    public final Instant capturedAt;
    public final int     pid;
    public final boolean generational;
    public final long    heapCommittedBytes;
    public final long    softMaxHeapBytes;
    public final long    maxHeapBytes;
    public final int     minorCycles;
    public final int     majorCycles;
    public final double  avgCycleIntervalSec;
    public final double  avgCycleDurationSec;
    public final double  pauseMarkStartMs;
    public final double  pauseMarkEndMs;
    public final double  pauseRelocateStartMs;
    public final int     stallCount;
    public final double  stallTotalMs;
    public final double  stallMaxMs;
    public final String  stallMaxThread;
    public final boolean softMaxBreached;
    public final boolean cycleOverlap;

    /** Each entry is {@code "frame|pct"}, semicolon-separated in the file. */
    public final List<String> topAllocFrames;

    public ZgcBaseline(
            Instant capturedAt, int pid, boolean generational,
            long heapCommittedBytes, long softMaxHeapBytes, long maxHeapBytes,
            int minorCycles, int majorCycles,
            double avgCycleIntervalSec, double avgCycleDurationSec,
            double pauseMarkStartMs, double pauseMarkEndMs, double pauseRelocateStartMs,
            int stallCount, double stallTotalMs, double stallMaxMs, String stallMaxThread,
            boolean softMaxBreached, boolean cycleOverlap,
            List<String> topAllocFrames) {
        this.capturedAt          = capturedAt;
        this.pid                 = pid;
        this.generational        = generational;
        this.heapCommittedBytes  = heapCommittedBytes;
        this.softMaxHeapBytes    = softMaxHeapBytes;
        this.maxHeapBytes        = maxHeapBytes;
        this.minorCycles         = minorCycles;
        this.majorCycles         = majorCycles;
        this.avgCycleIntervalSec = avgCycleIntervalSec;
        this.avgCycleDurationSec = avgCycleDurationSec;
        this.pauseMarkStartMs    = pauseMarkStartMs;
        this.pauseMarkEndMs      = pauseMarkEndMs;
        this.pauseRelocateStartMs= pauseRelocateStartMs;
        this.stallCount          = stallCount;
        this.stallTotalMs        = stallTotalMs;
        this.stallMaxMs          = stallMaxMs;
        this.stallMaxThread      = stallMaxThread == null ? "" : stallMaxThread;
        this.softMaxBreached     = softMaxBreached;
        this.cycleOverlap        = cycleOverlap;
        this.topAllocFrames      = topAllocFrames == null ? List.of() : List.copyOf(topAllocFrames);
    }

    // ── Severity ─────────────────────────────────────────────────────────────

    public enum Severity { INFO, WARN, REGRESSION }

    public static final class DiffRow {
        private final String label;
        private final String baselineValue;
        private final String currentValue;
        private final String delta;
        private final Severity severity;

        public DiffRow(String label, String baselineValue, String currentValue,
                       String delta, Severity severity) {
            this.label = label;
            this.baselineValue = baselineValue;
            this.currentValue = currentValue;
            this.delta = delta;
            this.severity = severity;
        }

        public String label() { return label; }
        public String baselineValue() { return baselineValue; }
        public String currentValue() { return currentValue; }
        public String delta() { return delta; }
        public Severity severity() { return severity; }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (!(o instanceof DiffRow)) return false;
            DiffRow that = (DiffRow) o;
            return java.util.Objects.equals(label, that.label)
                    && java.util.Objects.equals(baselineValue, that.baselineValue)
                    && java.util.Objects.equals(currentValue, that.currentValue)
                    && java.util.Objects.equals(delta, that.delta)
                    && severity == that.severity;
        }

        @Override
        public int hashCode() {
            return java.util.Objects.hash(label, baselineValue, currentValue, delta, severity);
        }

        @Override
        public String toString() {
            return "DiffRow[label=" + label + ", baselineValue=" + baselineValue
                    + ", currentValue=" + currentValue + ", delta=" + delta
                    + ", severity=" + severity + "]";
        }
    }

    // ── save ─────────────────────────────────────────────────────────────────

    public static void save(Path file, ZgcDiagnosis d, int pid) throws IOException {
        // Compute stall aggregates from diagnosis
        int stallCount = d.stalls.size();
        double stallTotalMs = d.stalls.stream()
                .mapToDouble(ZgcDiagnosis.Stall::durationMs).sum();
        double stallMaxMs = d.stalls.stream()
                .mapToDouble(ZgcDiagnosis.Stall::durationMs).max().orElse(0.0);
        String stallMaxThread = d.stalls.stream()
                .max(java.util.Comparator.comparingDouble(ZgcDiagnosis.Stall::durationMs))
                .map(ZgcDiagnosis.Stall::thread).orElse("");

        // Build topAllocFrames: "frame|pct" semicolon-joined
        StringBuilder allocSb = new StringBuilder();
        for (int i = 0; i < d.stallAllocHotspots.size(); i++) {
            ZgcDiagnosis.AllocHotspot h = d.stallAllocHotspots.get(i);
            if (i > 0) allocSb.append(';');
            allocSb.append(escapeField(h.frame())).append('|')
                   .append(String.format("%.2f", h.pct()));
        }

        StringBuilder sb = new StringBuilder();
        sb.append("version=1\n");
        sb.append("capturedAt=").append(Instant.now()).append('\n');
        sb.append("pid=").append(pid).append('\n');
        sb.append("generational=").append(d.generational).append('\n');
        sb.append("heapCommittedBytes=").append(d.heapCommittedBytes).append('\n');
        sb.append("softMaxHeapBytes=").append(d.softMaxHeapBytes).append('\n');
        sb.append("maxHeapBytes=").append(d.maxHeapBytes).append('\n');
        sb.append("minorCycles=").append(d.minorCycles).append('\n');
        sb.append("majorCycles=").append(d.majorCycles).append('\n');
        sb.append("avgCycleIntervalSec=").append(String.format("%.6f", d.avgCycleIntervalSec)).append('\n');
        sb.append("avgCycleDurationSec=").append(String.format("%.6f", d.avgCycleDurationSec)).append('\n');
        sb.append("pauseMarkStartMs=").append(String.format("%.6f", d.pauseMarkStartMs)).append('\n');
        sb.append("pauseMarkEndMs=").append(String.format("%.6f", d.pauseMarkEndMs)).append('\n');
        sb.append("pauseRelocateStartMs=").append(String.format("%.6f", d.pauseRelocateStartMs)).append('\n');
        sb.append("stallCount=").append(stallCount).append('\n');
        sb.append("stallTotalMs=").append(String.format("%.6f", stallTotalMs)).append('\n');
        sb.append("stallMaxMs=").append(String.format("%.6f", stallMaxMs)).append('\n');
        sb.append("stallMaxThread=").append(escapeField(stallMaxThread)).append('\n');
        sb.append("softMaxBreached=").append(d.softMaxBreached).append('\n');
        sb.append("cycleOverlap=").append(d.cycleOverlap).append('\n');
        sb.append("topAllocFrames=").append(allocSb).append('\n');

        Files.writeString(file, sb.toString());
    }

    // ── load ─────────────────────────────────────────────────────────────────

    public static ZgcBaseline load(Path file) throws IOException {
        String text = Files.readString(file);

        Instant capturedAt = parseInstant(text, "capturedAt");
        int     pid                  = (int) parseLong(text, "pid", 0);
        boolean generational         = parseBool(text, "generational", false);
        long    heapCommittedBytes   = parseLong(text, "heapCommittedBytes", 0);
        long    softMaxHeapBytes     = parseLong(text, "softMaxHeapBytes", -1);
        long    maxHeapBytes         = parseLong(text, "maxHeapBytes", 0);
        int     minorCycles          = (int) parseLong(text, "minorCycles", 0);
        int     majorCycles          = (int) parseLong(text, "majorCycles", 0);
        double  avgCycleIntervalSec  = parseDouble(text, "avgCycleIntervalSec", 0.0);
        double  avgCycleDurationSec  = parseDouble(text, "avgCycleDurationSec", 0.0);
        double  pauseMarkStartMs     = parseDouble(text, "pauseMarkStartMs", 0.0);
        double  pauseMarkEndMs       = parseDouble(text, "pauseMarkEndMs", 0.0);
        double  pauseRelocateStartMs = parseDouble(text, "pauseRelocateStartMs", 0.0);
        int     stallCount           = (int) parseLong(text, "stallCount", 0);
        double  stallTotalMs         = parseDouble(text, "stallTotalMs", 0.0);
        double  stallMaxMs           = parseDouble(text, "stallMaxMs", 0.0);
        String  stallMaxThread       = parseString(text, "stallMaxThread", "");
        boolean softMaxBreached      = parseBool(text, "softMaxBreached", false);
        boolean cycleOverlap         = parseBool(text, "cycleOverlap", false);
        List<String> topAllocFrames  = parseAllocFrames(text);

        return new ZgcBaseline(
                capturedAt, pid, generational,
                heapCommittedBytes, softMaxHeapBytes, maxHeapBytes,
                minorCycles, majorCycles,
                avgCycleIntervalSec, avgCycleDurationSec,
                pauseMarkStartMs, pauseMarkEndMs, pauseRelocateStartMs,
                stallCount, stallTotalMs, stallMaxMs, stallMaxThread,
                softMaxBreached, cycleOverlap,
                topAllocFrames);
    }

    // ── diff ─────────────────────────────────────────────────────────────────

    public static List<DiffRow> diff(ZgcBaseline baseline, ZgcDiagnosis current) {
        List<DiffRow> rows = new ArrayList<>();

        // Heap committed
        long baseCommit = baseline.heapCommittedBytes;
        long curCommit  = current.heapCommittedBytes;
        long commitDelta = curCommit - baseCommit;
        Severity commitSeverity = INFO;
        if (current.softMaxHeapBytes > 0 && curCommit > current.softMaxHeapBytes
                && (baseCommit <= 0 || baseCommit <= current.softMaxHeapBytes)) {
            commitSeverity = REGRESSION;
        } else if (commitDelta > 0) {
            commitSeverity = WARN;
        }
        rows.add(new DiffRow("heapCommitted",
                RichRenderer.formatBytes(baseCommit), RichRenderer.formatBytes(curCommit),
                formatBytesDelta(commitDelta), commitSeverity));

        // Minor cycles
        int baseMinor = baseline.minorCycles;
        int curMinor  = current.minorCycles;
        int minorDelta = curMinor - baseMinor;
        rows.add(new DiffRow("minorCycles",
                String.valueOf(baseMinor), String.valueOf(curMinor),
                (minorDelta >= 0 ? "+" : "") + minorDelta, INFO));

        // Major cycles — check minor:major ratio worsening >50%
        int baseMajor = baseline.majorCycles;
        int curMajor  = current.majorCycles;
        int majorDelta = curMajor - baseMajor;
        Severity majorSeverity = INFO;
        if (baseMajor > 0 && curMajor > 0 && baseMinor > 0 && curMinor > 0) {
            double baseRatio = (double) baseMinor / baseMajor;
            double curRatio  = (double) curMinor  / curMajor;
            // ratio worsened = minor:major dropped by >50% (fewer minor per major = worse)
            if (baseRatio > 0 && (baseRatio - curRatio) / baseRatio > 0.50) {
                majorSeverity = REGRESSION;
            }
        }
        rows.add(new DiffRow("majorCycles",
                String.valueOf(baseMajor), String.valueOf(curMajor),
                (majorDelta >= 0 ? "+" : "") + majorDelta, majorSeverity));

        // Allocation stalls
        int baseStalls = baseline.stallCount;
        int curStalls  = current.stalls.size();
        int stallDelta = curStalls - baseStalls;
        Severity stallSeverity = INFO;
        if (baseStalls == 0 && curStalls > 0) {
            stallSeverity = REGRESSION;
        } else if (stallDelta > 0) {
            stallSeverity = WARN;
        }
        rows.add(new DiffRow("stallCount",
                String.valueOf(baseStalls), String.valueOf(curStalls),
                (stallDelta >= 0 ? "+" : "") + stallDelta, stallSeverity));

        // Pause Mark End
        double baseMarkEnd = baseline.pauseMarkEndMs;
        double curMarkEnd  = current.pauseMarkEndMs;
        double markEndDelta = curMarkEnd - baseMarkEnd;
        Severity markEndSeverity = INFO;
        if (baseMarkEnd > 0 && markEndDelta / baseMarkEnd > 0.50) {
            markEndSeverity = REGRESSION;
        } else if (markEndDelta > 0) {
            markEndSeverity = WARN;
        }
        rows.add(new DiffRow("pauseMarkEnd",
                String.format("%.2fms", baseMarkEnd),
                String.format("%.2fms", curMarkEnd),
                String.format("%+.2fms", markEndDelta),
                markEndSeverity));

        // SoftMax breach
        boolean baseBreach = baseline.softMaxBreached;
        boolean curBreach  = current.softMaxBreached;
        Severity breachSeverity = INFO;
        if (!baseBreach && curBreach) breachSeverity = REGRESSION;
        else if (curBreach)           breachSeverity = WARN;
        rows.add(new DiffRow("softMaxBreached",
                baseBreach ? "yes" : "no",
                curBreach  ? "yes" : "no",
                (!baseBreach && curBreach) ? "NEW" : (curBreach ? "persists" : "none"),
                breachSeverity));

        return rows;
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    private static final Severity INFO       = Severity.INFO;
    private static final Severity WARN       = Severity.WARN;
    private static final Severity REGRESSION = Severity.REGRESSION;

    public static String formatBytesDelta(long delta) {
        if (delta == 0) return "0";
        String sign = delta > 0 ? "+" : "-";
        return sign + RichRenderer.formatBytes(Math.abs(delta));
    }

    private static String escapeField(String s) {
        if (s == null) return "";
        // Escape semicolons and pipes used as delimiters, and newlines
        return s.replace("\\", "\\\\")
                .replace(";", "\\;")
                .replace("|", "\\|")
                .replace("\n", " ")
                .replace("\r", " ");
    }

    private static long parseLong(String text, String key, long defaultValue) {
        Matcher m = Pattern.compile("(?m)^" + Pattern.quote(key) + "=(-?\\d+)").matcher(text);
        return m.find() ? Long.parseLong(m.group(1)) : defaultValue;
    }

    private static double parseDouble(String text, String key, double defaultValue) {
        Matcher m = Pattern.compile("(?m)^" + Pattern.quote(key) + "=(-?[\\d.]+(?:[Ee][+-]?\\d+)?)")
                .matcher(text);
        try { return m.find() ? Double.parseDouble(m.group(1)) : defaultValue; }
        catch (NumberFormatException e) { return defaultValue; }
    }

    private static boolean parseBool(String text, String key, boolean defaultValue) {
        Matcher m = Pattern.compile("(?m)^" + Pattern.quote(key) + "=(true|false)").matcher(text);
        return m.find() ? Boolean.parseBoolean(m.group(1)) : defaultValue;
    }

    private static String parseString(String text, String key, String defaultValue) {
        Matcher m = Pattern.compile("(?m)^" + Pattern.quote(key) + "=(.*)$").matcher(text);
        return m.find() ? m.group(1).trim() : defaultValue;
    }

    private static Instant parseInstant(String text, String key) {
        String raw = parseString(text, key, "");
        if (raw.isEmpty()) return Instant.EPOCH;
        try { return Instant.parse(raw); }
        catch (DateTimeParseException e) { return Instant.EPOCH; }
    }

    private static List<String> parseAllocFrames(String text) {
        String raw = parseString(text, "topAllocFrames", "");
        List<String> out = new ArrayList<>();
        if (raw.isEmpty()) return out;
        // Split on semicolons not preceded by backslash
        String[] parts = raw.split("(?<!\\\\);");
        for (String part : parts) {
            String unescaped = part.replace("\\;", ";").replace("\\|", "|").replace("\\\\", "\\");
            if (!unescaped.isBlank()) out.add(unescaped);
        }
        return out;
    }
}
