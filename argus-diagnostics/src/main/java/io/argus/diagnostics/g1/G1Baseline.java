package io.argus.diagnostics.g1;

import io.argus.diagnostics.format.DiagnosticsFormat;

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
 * Persistent G1GC snapshot for trend / diff analysis.
 *
 * <p>Serialised as a plain-text properties file (key=value, one per line) so
 * no JSON library is required. Mirrors {@link io.argus.diagnostics.zgc.ZgcBaseline}.
 *
 * <p>File format:
 * <pre>
 * version=1
 * capturedAt=2026-05-27T12:00:00Z
 * pid=12345
 * regionSizeMb=4
 * targetPauseMs=200
 * ihopPercent=45
 * adaptiveIhop=true
 * heapCommittedBytes=4294967296
 * maxHeapBytes=8589934592
 * totalRegions=2048
 * edenRegions=400
 * survivorRegions=20
 * oldRegions=900
 * humongousRegions=8
 * youngCycles=120
 * mixedCycles=8
 * concurrentCycles=2
 * fullGcCycles=0
 * avgYoungPauseMs=42.10
 * avgMixedPauseMs=85.50
 * maxPauseMs=120.40
 * bytesCopiedYoung=2147483648
 * bytesCopiedOld=536870912
 * evacuationFailures=0
 * avgMmuPercent=95.20
 * minMmuPercent=88.10
 * predictedIhopPercent=45.00
 * actualIhopPercent=46.50
 * humongousAllocationCycles=0
 * fullGcSeen=false
 * evacuationFailureSeen=false
 * mixedStarvation=false
 * ihopMistimed=false
 * topHumongousFrames=com.example.Foo.bar(Foo.java:42)|3|16777216;com.example.Bar.baz(Bar.java:10)|1|33554432
 * </pre>
 */
public final class G1Baseline {

    public final Instant capturedAt;
    public final int     pid;
    public final int     regionSizeMb;
    public final int     targetPauseMs;
    public final int     ihopPercent;
    public final boolean adaptiveIhop;
    public final long    heapCommittedBytes;
    public final long    maxHeapBytes;
    public final int     totalRegions;
    public final int     edenRegions;
    public final int     survivorRegions;
    public final int     oldRegions;
    public final int     humongousRegions;
    public final int     youngCycles;
    public final int     mixedCycles;
    public final int     concurrentCycles;
    public final int     fullGcCycles;
    public final double  avgYoungPauseMs;
    public final double  avgMixedPauseMs;
    public final double  maxPauseMs;
    public final long    bytesCopiedYoung;
    public final long    bytesCopiedOld;
    public final int     evacuationFailures;
    public final double  avgMmuPercent;
    public final double  minMmuPercent;
    public final double  predictedIhopPercent;
    public final double  actualIhopPercent;
    public final int     humongousAllocationCycles;
    public final boolean fullGcSeen;
    public final boolean evacuationFailureSeen;
    public final boolean mixedStarvation;
    public final boolean ihopMistimed;

    /** Each entry is {@code "frame|count|maxBytes"}, semicolon-separated in the file. */
    public final List<String> topHumongousFrames;

    public G1Baseline(
            Instant capturedAt, int pid,
            int regionSizeMb, int targetPauseMs, int ihopPercent, boolean adaptiveIhop,
            long heapCommittedBytes, long maxHeapBytes,
            int totalRegions, int edenRegions, int survivorRegions,
            int oldRegions, int humongousRegions,
            int youngCycles, int mixedCycles, int concurrentCycles, int fullGcCycles,
            double avgYoungPauseMs, double avgMixedPauseMs, double maxPauseMs,
            long bytesCopiedYoung, long bytesCopiedOld, int evacuationFailures,
            double avgMmuPercent, double minMmuPercent,
            double predictedIhopPercent, double actualIhopPercent,
            int humongousAllocationCycles,
            boolean fullGcSeen, boolean evacuationFailureSeen,
            boolean mixedStarvation, boolean ihopMistimed,
            List<String> topHumongousFrames) {
        this.capturedAt              = capturedAt;
        this.pid                     = pid;
        this.regionSizeMb            = regionSizeMb;
        this.targetPauseMs           = targetPauseMs;
        this.ihopPercent             = ihopPercent;
        this.adaptiveIhop            = adaptiveIhop;
        this.heapCommittedBytes      = heapCommittedBytes;
        this.maxHeapBytes            = maxHeapBytes;
        this.totalRegions            = totalRegions;
        this.edenRegions             = edenRegions;
        this.survivorRegions         = survivorRegions;
        this.oldRegions              = oldRegions;
        this.humongousRegions        = humongousRegions;
        this.youngCycles             = youngCycles;
        this.mixedCycles             = mixedCycles;
        this.concurrentCycles        = concurrentCycles;
        this.fullGcCycles            = fullGcCycles;
        this.avgYoungPauseMs         = avgYoungPauseMs;
        this.avgMixedPauseMs         = avgMixedPauseMs;
        this.maxPauseMs              = maxPauseMs;
        this.bytesCopiedYoung        = bytesCopiedYoung;
        this.bytesCopiedOld          = bytesCopiedOld;
        this.evacuationFailures      = evacuationFailures;
        this.avgMmuPercent           = avgMmuPercent;
        this.minMmuPercent           = minMmuPercent;
        this.predictedIhopPercent    = predictedIhopPercent;
        this.actualIhopPercent       = actualIhopPercent;
        this.humongousAllocationCycles = humongousAllocationCycles;
        this.fullGcSeen              = fullGcSeen;
        this.evacuationFailureSeen   = evacuationFailureSeen;
        this.mixedStarvation         = mixedStarvation;
        this.ihopMistimed            = ihopMistimed;
        this.topHumongousFrames      = topHumongousFrames == null ? List.of() : List.copyOf(topHumongousFrames);
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
    }

    // ── save ─────────────────────────────────────────────────────────────────

    public static void save(Path file, G1Diagnosis d, int pid) throws IOException {
        StringBuilder humSb = new StringBuilder();
        for (int i = 0; i < d.humongousHotspots.size(); i++) {
            G1Diagnosis.HumongousHotspot h = d.humongousHotspots.get(i);
            if (i > 0) humSb.append(';');
            humSb.append(escapeField(h.frame())).append('|')
                    .append(h.count()).append('|').append(h.maxBytes());
        }

        StringBuilder sb = new StringBuilder();
        sb.append("version=1\n");
        sb.append("capturedAt=").append(Instant.now()).append('\n');
        sb.append("pid=").append(pid).append('\n');
        sb.append("regionSizeMb=").append(d.regionSizeMb).append('\n');
        sb.append("targetPauseMs=").append(d.targetPauseMs).append('\n');
        sb.append("ihopPercent=").append(d.ihopPercent).append('\n');
        sb.append("adaptiveIhop=").append(d.adaptiveIhop).append('\n');
        sb.append("heapCommittedBytes=").append(d.heapCommittedBytes).append('\n');
        sb.append("maxHeapBytes=").append(d.maxHeapBytes).append('\n');
        sb.append("totalRegions=").append(d.totalRegions).append('\n');
        sb.append("edenRegions=").append(d.edenRegions).append('\n');
        sb.append("survivorRegions=").append(d.survivorRegions).append('\n');
        sb.append("oldRegions=").append(d.oldRegions).append('\n');
        sb.append("humongousRegions=").append(d.humongousRegions).append('\n');
        sb.append("youngCycles=").append(d.youngCycles).append('\n');
        sb.append("mixedCycles=").append(d.mixedCycles).append('\n');
        sb.append("concurrentCycles=").append(d.concurrentCycles).append('\n');
        sb.append("fullGcCycles=").append(d.fullGcCycles).append('\n');
        sb.append("avgYoungPauseMs=").append(String.format("%.6f", d.avgYoungPauseMs)).append('\n');
        sb.append("avgMixedPauseMs=").append(String.format("%.6f", d.avgMixedPauseMs)).append('\n');
        sb.append("maxPauseMs=").append(String.format("%.6f", d.maxPauseMs)).append('\n');
        sb.append("bytesCopiedYoung=").append(d.bytesCopiedYoung).append('\n');
        sb.append("bytesCopiedOld=").append(d.bytesCopiedOld).append('\n');
        sb.append("evacuationFailures=").append(d.evacuationFailures).append('\n');
        sb.append("avgMmuPercent=").append(String.format("%.6f", d.avgMmuPercent)).append('\n');
        sb.append("minMmuPercent=").append(String.format("%.6f", d.minMmuPercent)).append('\n');
        sb.append("predictedIhopPercent=").append(String.format("%.6f", d.predictedIhopPercent)).append('\n');
        sb.append("actualIhopPercent=").append(String.format("%.6f", d.actualIhopPercent)).append('\n');
        sb.append("humongousAllocationCycles=").append(d.humongousAllocationCycles).append('\n');
        sb.append("fullGcSeen=").append(d.fullGcSeen).append('\n');
        sb.append("evacuationFailureSeen=").append(d.evacuationFailureSeen).append('\n');
        sb.append("mixedStarvation=").append(d.mixedStarvation).append('\n');
        sb.append("ihopMistimed=").append(d.ihopMistimed).append('\n');
        sb.append("topHumongousFrames=").append(humSb).append('\n');

        Files.writeString(file, sb.toString());
    }

    // ── load ─────────────────────────────────────────────────────────────────

    public static G1Baseline load(Path file) throws IOException {
        String t = Files.readString(file);
        return new G1Baseline(
                parseInstant(t, "capturedAt"),
                (int) parseLong(t, "pid", 0),
                (int) parseLong(t, "regionSizeMb", 0),
                (int) parseLong(t, "targetPauseMs", 0),
                (int) parseLong(t, "ihopPercent", 0),
                parseBool(t, "adaptiveIhop", false),
                parseLong(t, "heapCommittedBytes", 0),
                parseLong(t, "maxHeapBytes", 0),
                (int) parseLong(t, "totalRegions", 0),
                (int) parseLong(t, "edenRegions", 0),
                (int) parseLong(t, "survivorRegions", 0),
                (int) parseLong(t, "oldRegions", 0),
                (int) parseLong(t, "humongousRegions", 0),
                (int) parseLong(t, "youngCycles", 0),
                (int) parseLong(t, "mixedCycles", 0),
                (int) parseLong(t, "concurrentCycles", 0),
                (int) parseLong(t, "fullGcCycles", 0),
                parseDouble(t, "avgYoungPauseMs", 0.0),
                parseDouble(t, "avgMixedPauseMs", 0.0),
                parseDouble(t, "maxPauseMs", 0.0),
                parseLong(t, "bytesCopiedYoung", 0),
                parseLong(t, "bytesCopiedOld", 0),
                (int) parseLong(t, "evacuationFailures", 0),
                parseDouble(t, "avgMmuPercent", 0.0),
                parseDouble(t, "minMmuPercent", 0.0),
                parseDouble(t, "predictedIhopPercent", 0.0),
                parseDouble(t, "actualIhopPercent", 0.0),
                (int) parseLong(t, "humongousAllocationCycles", 0),
                parseBool(t, "fullGcSeen", false),
                parseBool(t, "evacuationFailureSeen", false),
                parseBool(t, "mixedStarvation", false),
                parseBool(t, "ihopMistimed", false),
                parseHumongousFrames(t));
    }

    // ── diff ─────────────────────────────────────────────────────────────────

    public static List<DiffRow> diff(G1Baseline baseline, G1Diagnosis current) {
        List<DiffRow> rows = new ArrayList<>();

        // Heap committed
        long baseCommit = baseline.heapCommittedBytes;
        long curCommit  = current.heapCommittedBytes;
        long commitDelta = curCommit - baseCommit;
        Severity commitSeverity = commitDelta > 0 ? WARN : INFO;
        rows.add(new DiffRow("heapCommitted",
                DiagnosticsFormat.formatBytes(baseCommit),
                DiagnosticsFormat.formatBytes(curCommit),
                formatBytesDelta(commitDelta), commitSeverity));

        // Full GC
        boolean baseFull = baseline.fullGcSeen;
        boolean curFull  = current.fullGcSeen;
        Severity fullSeverity = INFO;
        if (!baseFull && curFull) fullSeverity = REGRESSION;
        else if (curFull)         fullSeverity = WARN;
        rows.add(new DiffRow("fullGcSeen",
                baseFull ? "yes" : "no",
                curFull  ? "yes" : "no",
                (!baseFull && curFull) ? "NEW" : (curFull ? "persists" : "none"),
                fullSeverity));

        // Evacuation failure
        boolean baseEvac = baseline.evacuationFailureSeen;
        boolean curEvac  = current.evacuationFailureSeen;
        Severity evacSeverity = INFO;
        if (!baseEvac && curEvac) evacSeverity = REGRESSION;
        else if (curEvac)         evacSeverity = WARN;
        rows.add(new DiffRow("evacuationFailure",
                baseEvac ? "yes" : "no",
                curEvac  ? "yes" : "no",
                (!baseEvac && curEvac) ? "NEW" : (curEvac ? "persists" : "none"),
                evacSeverity));

        // Mixed starvation
        boolean baseMix = baseline.mixedStarvation;
        boolean curMix  = current.mixedStarvation;
        Severity mixSeverity = INFO;
        if (!baseMix && curMix) mixSeverity = REGRESSION;
        else if (curMix)        mixSeverity = WARN;
        rows.add(new DiffRow("mixedStarvation",
                baseMix ? "yes" : "no",
                curMix  ? "yes" : "no",
                (!baseMix && curMix) ? "NEW" : (curMix ? "persists" : "none"),
                mixSeverity));

        // Humongous cycle count
        int baseHum = baseline.humongousAllocationCycles;
        int curHum  = current.humongousAllocationCycles;
        int humDelta = curHum - baseHum;
        Severity humSeverity = INFO;
        if (baseHum == 0 && curHum > 0) humSeverity = REGRESSION;
        else if (baseHum > 0 && humDelta > baseHum * 0.5) humSeverity = WARN;
        else if (humDelta > 0) humSeverity = WARN;
        rows.add(new DiffRow("humongousCycles",
                String.valueOf(baseHum),
                String.valueOf(curHum),
                (humDelta >= 0 ? "+" : "") + humDelta,
                humSeverity));

        // Max pause
        double baseMax = baseline.maxPauseMs;
        double curMax  = current.maxPauseMs;
        double maxDelta = curMax - baseMax;
        Severity pauseSeverity = INFO;
        if (baseMax > 0 && maxDelta / baseMax > 0.50) pauseSeverity = REGRESSION;
        else if (maxDelta > 0)                         pauseSeverity = WARN;
        rows.add(new DiffRow("maxPause",
                String.format("%.2fms", baseMax),
                String.format("%.2fms", curMax),
                String.format("%+.2fms", maxDelta),
                pauseSeverity));

        // Min MMU (drop > 20% = regression)
        double baseMmu = baseline.minMmuPercent;
        double curMmu  = current.minMmuPercent;
        double mmuDelta = curMmu - baseMmu;
        Severity mmuSeverity = INFO;
        if (baseMmu > 0 && (baseMmu - curMmu) / baseMmu > 0.20) mmuSeverity = REGRESSION;
        else if (mmuDelta < 0) mmuSeverity = WARN;
        rows.add(new DiffRow("minMmu",
                String.format("%.1f%%", baseMmu),
                String.format("%.1f%%", curMmu),
                String.format("%+.1f%%", mmuDelta),
                mmuSeverity));

        return rows;
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    private static final Severity INFO       = Severity.INFO;
    private static final Severity WARN       = Severity.WARN;
    private static final Severity REGRESSION = Severity.REGRESSION;

    public static String formatBytesDelta(long delta) {
        if (delta == 0) return "0";
        String sign = delta > 0 ? "+" : "-";
        return sign + DiagnosticsFormat.formatBytes(Math.abs(delta));
    }

    private static String escapeField(String s) {
        if (s == null) return "";
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
        Matcher m = Pattern.compile("(?m)^" + Pattern.quote(key) + "=(-?[\\d.]+(?:[Ee][+-]?\\d+)?)").matcher(text);
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

    private static List<String> parseHumongousFrames(String text) {
        String raw = parseString(text, "topHumongousFrames", "");
        List<String> out = new ArrayList<>();
        if (raw.isEmpty()) return out;
        String[] parts = raw.split("(?<!\\\\);");
        for (String part : parts) {
            String unescaped = part.replace("\\;", ";").replace("\\|", "|").replace("\\\\", "\\");
            if (!unescaped.isBlank()) out.add(unescaped);
        }
        return out;
    }
}
