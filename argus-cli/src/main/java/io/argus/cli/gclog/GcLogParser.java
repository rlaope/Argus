package io.argus.cli.gclog;

import java.io.BufferedReader;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;

/**
 * Parses GC log files in multiple formats using streaming (no full-file load).
 * Supports: JDK 9+ unified logging (both uptime and ISO timestamps),
 * JDK 8 legacy, ZGC, Shenandoah.
 */
public final class GcLogParser {

    /** Sentinel returned when ISO timestamp is unparseable; events still get a relative position. */
    private static final double TS_UNKNOWN = -1;

    /**
     * Result container for phase-aware parsing.
     */
    public static final class ParseResult {
        private final List<GcEvent> events;
        private final List<GcPhaseEvent> phases;

        public ParseResult(List<GcEvent> events, List<GcPhaseEvent> phases) {
            this.events = events;
            this.phases = phases;
        }

        public List<GcEvent> events() { return events; }
        public List<GcPhaseEvent> phases() { return phases; }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (!(o instanceof ParseResult)) return false;
            ParseResult that = (ParseResult) o;
            return java.util.Objects.equals(events, that.events)
                    && java.util.Objects.equals(phases, that.phases);
        }

        @Override
        public int hashCode() {
            return java.util.Objects.hash(events, phases);
        }

        @Override
        public String toString() {
            return "ParseResult[events=" + events + ", phases=" + phases + "]";
        }
    }

    /**
     * Parse GC log file and also extract phase breakdown from debug gc,phases lines.
     * Requires -Xlog:gc*=debug output. Phase lines have [gc,phases] tag.
     */
    public static ParseResult parseWithPhases(Path logFile) throws IOException {
        List<GcEvent> events = new ArrayList<>();
        List<GcPhaseEvent> phases = new ArrayList<>();
        FormatState state = new FormatState();

        try (BufferedReader reader = Files.newBufferedReader(logFile)) {
            String line;
            while ((line = reader.readLine()) != null) {
                state.detect(line);

                if (line.contains("gc,phases")) {
                    GcPhaseEvent phase = parsePhraseLine(line);
                    if (phase != null) {
                        phases.add(phase);
                        continue;
                    }
                }

                GcEvent event = state.unified ? parseUnifiedLine(line, state) : parseLegacyLine(line);
                if (event != null) events.add(event);
            }
        }
        return new ParseResult(events, phases);
    }

    private static GcPhaseEvent parsePhraseLine(String line) {
        Matcher m = GcLogPatterns.GC_PHASE.matcher(line);
        if (!m.find()) return null;
        int gcId = Integer.parseInt(m.group(1));
        String phase = m.group(2).trim();
        double durationMs = Double.parseDouble(m.group(3));
        return new GcPhaseEvent(gcId, phase, durationMs);
    }

    /**
     * Parse GC log file using streaming reader. Handles multi-GB files.
     */
    public static List<GcEvent> parse(Path logFile) throws IOException {
        List<GcEvent> events = new ArrayList<>();
        FormatState state = new FormatState();

        try (BufferedReader reader = Files.newBufferedReader(logFile)) {
            String line;
            while ((line = reader.readLine()) != null) {
                state.detect(line);
                GcEvent event = state.unified ? parseUnifiedLine(line, state) : parseLegacyLine(line);
                if (event != null) events.add(event);
            }
        }
        return events;
    }

    /** Tracks per-file format detection plus monotonic timestamp baseline for ISO logs. */
    static final class FormatState {
        boolean unified;
        boolean detected;
        // ISO baseline: first parsed ISO Instant (epoch seconds, double); used to keep timestamps monotonic
        // across midnight boundaries.
        double isoEpochBaseline = Double.NaN;

        void detect(String line) {
            if (detected || line.isBlank()) return;
            // Concrete features only — bracketed [info] or [gc,XXX] tag, or `[N.NNNs]` uptime, or `Ns]` legacy suffix.
            // Avoid the false-positive "[info]" alone (JDK 8 wrappers may include it).
            String s = line;
            boolean hasUnifiedTag = s.contains("[gc]") || s.contains("[gc,") || s.contains("[gc ");
            boolean hasUptimeBracket = GcLogPatterns.TIMESTAMP_UPTIME.matcher(s).find();
            boolean hasIsoBracket = GcLogPatterns.TIMESTAMP_ISO.matcher(s).find();
            boolean looksLegacy = s.matches(".*\\d+\\.\\d+:\\s+\\[(Full )?GC.*");
            unified = (hasUnifiedTag || hasUptimeBracket || hasIsoBracket) && !looksLegacy;
            detected = true;
        }
    }

    private static GcEvent parseUnifiedLine(String line, FormatState state) {
        double timestamp = extractTimestamp(line, state);
        if (timestamp == TS_UNKNOWN) return null;

        // Try G1/Parallel/Serial pause
        Matcher m = GcLogPatterns.UNIFIED_PAUSE.matcher(line);
        if (m.find()) {
            String type = m.group(1).trim();
            long heapBefore = toKB(Long.parseLong(m.group(2)), m.group(3));
            long heapAfter = toKB(Long.parseLong(m.group(4)), m.group(5));
            long heapTotal = toKB(Long.parseLong(m.group(6)), m.group(7));
            double pauseMs = Double.parseDouble(m.group(8));

            // The greedy `type` capture often eats embedded "(Normal)" qualifiers.
            // Pull the LAST parenthesised group before the heap delta as the cause —
            // that's the GC trigger (e.g. "G1 Evacuation Pause", "Humongous Allocation").
            String cause = extractLastCause(line, m.start(2));
            // Strip trailing parenthesised modifiers from the type once we have the cause.
            type = stripParens(type);

            return new GcEvent(timestamp, type, cause, pauseMs, heapBefore, heapAfter, heapTotal);
        }

        // ZGC pause — gated to actual ZGC logs
        Matcher zm = GcLogPatterns.ZGC_PAUSE.matcher(line);
        if (zm.find() && lineMentions(line, "ZGC")) {
            String type = "ZGC Pause " + zm.group(1);
            double pauseMs = Double.parseDouble(zm.group(2));
            return new GcEvent(timestamp, type, "ZGC", pauseMs, 0, 0, 0);
        }

        // ZGC cycle (informational, no pause)
        Matcher zcm = GcLogPatterns.ZGC_CYCLE.matcher(line);
        if (zcm.find() && lineMentions(line, "ZGC", "Garbage Collection")) {
            String cause = zcm.group(1);
            long heapBefore = toKB(Long.parseLong(zcm.group(2)), zcm.group(3));
            long heapAfter = toKB(Long.parseLong(zcm.group(4)), zcm.group(5));
            return new GcEvent(timestamp, "ZGC Cycle", cause, 0, heapBefore, heapAfter, 0);
        }

        // ZGC Allocation Stall — thread name is the "cause"
        if (line.contains("Allocation Stall")) {
            Matcher am = GcLogPatterns.ALLOCATION_STALL.matcher(line);
            if (am.find()) {
                String thread = am.group(1);
                double stallMs = Double.parseDouble(am.group(2));
                return new GcEvent(timestamp, "ZGC Allocation Stall", thread, stallMs, 0, 0, 0);
            }
        }

        // Shenandoah pause — gated; otherwise generic G1 phases like "Pause Init Mark" misclassify.
        Matcher sm = GcLogPatterns.SHENANDOAH_PAUSE.matcher(line);
        if (sm.find() && lineMentions(line, "shenandoah")) {
            String type = "Shenandoah " + sm.group(1);
            double pauseMs = Double.parseDouble(sm.group(2));
            return new GcEvent(timestamp, type, "Shenandoah", pauseMs, 0, 0, 0);
        }

        // Concurrent phase
        Matcher cm = GcLogPatterns.UNIFIED_CONCURRENT.matcher(line);
        if (cm.find()) {
            double durationMs = Double.parseDouble(cm.group(2));
            return new GcEvent(timestamp, "Concurrent " + cm.group(1), "Concurrent", durationMs, 0, 0, 0);
        }

        return null;
    }

    private static GcEvent parseLegacyLine(String line) {
        Matcher m = GcLogPatterns.LEGACY_GC.matcher(line);
        if (!m.find()) return null;

        double timestamp = Double.parseDouble(m.group(1));
        boolean full = m.group(2) != null;
        String cause = m.group(3) != null ? m.group(3).trim() : "";
        long heapBefore = Long.parseLong(m.group(4));
        long heapAfter = Long.parseLong(m.group(5));
        long heapTotal = Long.parseLong(m.group(6));
        double pauseMs = Double.parseDouble(m.group(7)) * 1000;

        return new GcEvent(timestamp, full ? "Full" : "Young", cause, pauseMs, heapBefore, heapAfter, heapTotal);
    }

    /**
     * ISO timestamps are converted to seconds since the first event in the log
     * (relative time), preventing midnight wrap. Uptime stays as-is (it's already relative).
     */
    private static double extractTimestamp(String line, FormatState state) {
        Matcher m = GcLogPatterns.TIMESTAMP_UPTIME.matcher(line);
        if (m.find()) return Double.parseDouble(m.group(1));

        Matcher im = GcLogPatterns.TIMESTAMP_ISO.matcher(line);
        if (im.find()) {
            try {
                // OffsetDateTime requires the offset's colon ("+00:00"). Many JVM logs emit
                // RFC 822-style "+0000". Normalise both forms here.
                String iso = im.group(1).replaceAll("([+-]\\d{2})(\\d{2})$", "$1:$2");
                double epochSec = OffsetDateTime.parse(iso).toInstant().toEpochMilli() / 1000.0;
                if (Double.isNaN(state.isoEpochBaseline)) {
                    state.isoEpochBaseline = epochSec;
                }
                return epochSec - state.isoEpochBaseline;
            } catch (DateTimeParseException ignored) {
                return 0; // unparseable but still ordered relative to other events
            }
        }
        return TS_UNKNOWN;
    }

    /**
     * Pull the cause group: the last parenthesised group before the byte delta.
     * Returns "" if none present.
     */
    private static String extractLastCause(String line, int beforeIndex) {
        int searchEnd = Math.min(line.length(), beforeIndex);
        int lastClose = line.lastIndexOf(')', searchEnd - 1);
        if (lastClose < 0) return "";
        int matching = line.lastIndexOf('(', lastClose - 1);
        if (matching < 0) return "";
        return line.substring(matching + 1, lastClose).trim();
    }

    private static String stripParens(String type) {
        int paren = type.indexOf('(');
        return paren > 0 ? type.substring(0, paren).trim() : type.trim();
    }

    private static boolean lineMentions(String line, String... tokens) {
        String lower = line.toLowerCase();
        for (String t : tokens) {
            if (!lower.contains(t.toLowerCase())) return false;
        }
        return true;
    }

    private static long toKB(long value, String unit) {
        switch (unit) {
            case "K": return value;
            case "M": return value * 1024L;
            case "G": return value * 1024L * 1024L;
            case "T": return value * 1024L * 1024L * 1024L;
            default: return value;
        }
    }
}
