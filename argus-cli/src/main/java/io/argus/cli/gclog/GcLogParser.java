package io.argus.cli.gclog;

import java.io.BufferedReader;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Parses GC log files in multiple formats using streaming (no full-file load).
 * Supports: JDK 9+ unified logging (both uptime and ISO timestamps),
 * JDK 8 legacy, ZGC, Shenandoah.
 */
public final class GcLogParser {

    // JDK 9+ unified with uptime: [0.234s] or decorated: [2024-01-15T10:30:45.123+0000]
    private static final Pattern TIMESTAMP_UPTIME = Pattern.compile("\\[(\\d+\\.\\d+)s\\]");
    private static final Pattern TIMESTAMP_ISO = Pattern.compile("\\[(\\d{4}-\\d{2}-\\d{2}T[\\d:.+]+)\\]");

    // G1/Parallel/Serial pause: Pause Young|Mixed|Full (...) 24M->8M(256M) 3.456ms
    private static final Pattern UNIFIED_PAUSE = Pattern.compile(
            "GC\\(\\d+\\)\\s+Pause\\s+(\\S+(?:\\s+\\([^)]*\\))*)\\s+(\\d+)([MKG])->(\\d+)([MKG])\\((\\d+)([MKG])\\)\\s+(\\d+\\.?\\d*)ms");

    // Unified pause with cause in parens: Pause Young (Normal) (G1 Evacuation Pause) ...
    private static final Pattern UNIFIED_CAUSE = Pattern.compile("\\(([^)]+)\\)\\s+\\d+[MKG]->");

    // ZGC: GC(0) Garbage Collection (Warmup) 128M(50%)->64M(25%)
    private static final Pattern ZGC_PAUSE = Pattern.compile(
            "GC\\(\\d+\\)\\s+Pause\\s+(\\w+)\\s+(\\d+\\.?\\d*)ms");
    private static final Pattern ZGC_CYCLE = Pattern.compile(
            "GC\\(\\d+\\)\\s+Garbage Collection\\s+\\(([^)]+)\\)\\s+(\\d+)([MKG])\\([^)]*\\)->(\\d+)([MKG])");

    // Shenandoah: GC(0) Pause Init Mark 0.123ms | Pause Final Mark 0.456ms
    private static final Pattern SHENANDOAH_PAUSE = Pattern.compile(
            "GC\\(\\d+\\)\\s+Pause\\s+(Init Mark|Final Mark|Init Update|Final Update|Full).*?(\\d+\\.?\\d*)ms");

    // JDK 8 legacy: 1.234: [GC (Allocation Failure) ... 65536K->8200K(251392K), 0.0123456 secs]
    private static final Pattern LEGACY_GC = Pattern.compile(
            "(\\d+\\.\\d+):\\s+\\[(Full )?GC\\s*\\(([^)]+)\\).*?(\\d+)K->(\\d+)K\\((\\d+)K\\),?\\s+(\\d+\\.\\d+)\\s+secs");

    // JDK 9+ concurrent: GC(1) Concurrent Mark 12.345ms
    private static final Pattern UNIFIED_CONCURRENT = Pattern.compile(
            "GC\\(\\d+\\)\\s+Concurrent\\s+(\\S+)\\s+(\\d+\\.?\\d*)ms");

    /**
     * Parse GC log file using streaming reader. Handles multi-GB files.
     */
    public static List<GcEvent> parse(Path logFile) throws IOException {
        List<GcEvent> events = new ArrayList<>();
        boolean unified = false;
        boolean formatDetected = false;

        try (BufferedReader reader = Files.newBufferedReader(logFile)) {
            String line;
            while ((line = reader.readLine()) != null) {
                // Auto-detect format from first non-empty line
                if (!formatDetected && !line.isBlank()) {
                    unified = line.contains("[gc") || line.contains("[info]")
                            || line.startsWith("[") && (line.contains("s]") || line.contains("T"));
                    formatDetected = true;
                }

                GcEvent event = unified ? parseUnifiedLine(line) : parseLegacyLine(line);
                if (event != null) events.add(event);
            }
        }
        return events;
    }

    private static GcEvent parseUnifiedLine(String line) {
        // Extract timestamp
        double timestamp = extractTimestamp(line);
        if (timestamp < 0) return null;

        // Try G1/Parallel/Serial pause
        Matcher m = UNIFIED_PAUSE.matcher(line);
        if (m.find()) {
            String type = m.group(1).trim();
            long heapBefore = toKB(Long.parseLong(m.group(2)), m.group(3));
            long heapAfter = toKB(Long.parseLong(m.group(4)), m.group(5));
            long heapTotal = toKB(Long.parseLong(m.group(6)), m.group(7));
            double pauseMs = Double.parseDouble(m.group(8));

            // Extract cause from parenthesized groups
            String cause = "";
            Matcher cm = UNIFIED_CAUSE.matcher(line);
            if (cm.find()) cause = cm.group(1).trim();

            return new GcEvent(timestamp, type, cause, pauseMs, heapBefore, heapAfter, heapTotal);
        }

        // Try ZGC pause
        Matcher zm = ZGC_PAUSE.matcher(line);
        if (zm.find() && line.contains("ZGC")) {
            String type = "ZGC Pause " + zm.group(1);
            double pauseMs = Double.parseDouble(zm.group(2));
            return new GcEvent(timestamp, type, "ZGC", pauseMs, 0, 0, 0);
        }

        // Try ZGC cycle (not a pause but useful for analysis)
        Matcher zcm = ZGC_CYCLE.matcher(line);
        if (zcm.find()) {
            String cause = zcm.group(1);
            long heapBefore = toKB(Long.parseLong(zcm.group(2)), zcm.group(3));
            long heapAfter = toKB(Long.parseLong(zcm.group(4)), zcm.group(5));
            return new GcEvent(timestamp, "ZGC Cycle", cause, 0, heapBefore, heapAfter, 0);
        }

        // Try Shenandoah pause
        Matcher sm = SHENANDOAH_PAUSE.matcher(line);
        if (sm.find()) {
            String type = "Shenandoah " + sm.group(1);
            double pauseMs = Double.parseDouble(sm.group(2));
            return new GcEvent(timestamp, type, "Shenandoah", pauseMs, 0, 0, 0);
        }

        // Try concurrent phase
        Matcher cm = UNIFIED_CONCURRENT.matcher(line);
        if (cm.find()) {
            double durationMs = Double.parseDouble(cm.group(2));
            return new GcEvent(timestamp, "Concurrent " + cm.group(1), "Concurrent", durationMs, 0, 0, 0);
        }

        return null;
    }

    private static GcEvent parseLegacyLine(String line) {
        Matcher m = LEGACY_GC.matcher(line);
        if (!m.find()) return null;

        double timestamp = Double.parseDouble(m.group(1));
        boolean full = m.group(2) != null;
        String cause = m.group(3).trim();
        long heapBefore = Long.parseLong(m.group(4));
        long heapAfter = Long.parseLong(m.group(5));
        long heapTotal = Long.parseLong(m.group(6));
        double pauseMs = Double.parseDouble(m.group(7)) * 1000;

        return new GcEvent(timestamp, full ? "Full" : "Young", cause, pauseMs, heapBefore, heapAfter, heapTotal);
    }

    private static double extractTimestamp(String line) {
        Matcher m = TIMESTAMP_UPTIME.matcher(line);
        if (m.find()) return Double.parseDouble(m.group(1));

        // ISO timestamp — convert to seconds since epoch (approximate, relative is fine)
        Matcher im = TIMESTAMP_ISO.matcher(line);
        if (im.find()) {
            try {
                // Use simple heuristic: parse hours+minutes+seconds from ISO
                String iso = im.group(1);
                int tIdx = iso.indexOf('T');
                if (tIdx > 0) {
                    String timePart = iso.substring(tIdx + 1);
                    String[] parts = timePart.split("[:+]");
                    if (parts.length >= 3) {
                        return Double.parseDouble(parts[0]) * 3600
                                + Double.parseDouble(parts[1]) * 60
                                + Double.parseDouble(parts[2]);
                    }
                }
            } catch (Exception ignored) {}
        }
        return -1;
    }

    private static long toKB(long value, String unit) {
        return switch (unit) {
            case "K" -> value;
            case "M" -> value * 1024;
            case "G" -> value * 1024 * 1024;
            default -> value;
        };
    }
}
