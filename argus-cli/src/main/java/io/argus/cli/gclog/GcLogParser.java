package io.argus.cli.gclog;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Parses GC log files in both JDK 9+ unified logging format and JDK 8 legacy format.
 * Auto-detects the format from the first few lines.
 */
public final class GcLogParser {

    // JDK 9+ unified: [0.234s][info][gc] GC(0) Pause Young (Normal) (G1 Evacuation Pause) 24M->8M(256M) 3.456ms
    private static final Pattern UNIFIED_PAUSE = Pattern.compile(
            "\\[(\\d+\\.\\d+)s\\].*GC\\(\\d+\\)\\s+Pause\\s+(\\S+(?:\\s+\\([^)]*\\))?)\\s+\\(([^)]+)\\)\\s+(\\d+)([MKG])->(\\d+)([MKG])\\((\\d+)([MKG])\\)\\s+(\\d+\\.\\d+)ms");

    // JDK 9+ concurrent: [1.234s][info][gc] GC(1) Concurrent Mark 12.345ms
    private static final Pattern UNIFIED_CONCURRENT = Pattern.compile(
            "\\[(\\d+\\.\\d+)s\\].*GC\\(\\d+\\)\\s+Concurrent\\s+(\\S+)\\s+(\\d+\\.\\d+)ms");

    // JDK 8 legacy: 1.234: [GC (Allocation Failure) [PSYoungGen: 65536K->8192K(76288K)] 65536K->8200K(251392K), 0.0123456 secs]
    private static final Pattern LEGACY_GC = Pattern.compile(
            "(\\d+\\.\\d+):\\s+\\[(Full )?GC\\s*\\(([^)]+)\\).*?(\\d+)K->(\\d+)K\\((\\d+)K\\),?\\s+(\\d+\\.\\d+)\\s+secs");

    public static List<GcEvent> parse(Path logFile) throws IOException {
        List<String> lines = Files.readAllLines(logFile);
        if (lines.isEmpty()) return List.of();

        // Auto-detect format
        boolean unified = lines.stream().limit(20).anyMatch(l -> l.contains("[gc") || l.contains("[info][gc"));

        List<GcEvent> events = new ArrayList<>();
        for (String line : lines) {
            GcEvent event = unified ? parseUnifiedLine(line) : parseLegacyLine(line);
            if (event != null) events.add(event);
        }
        return List.copyOf(events);
    }

    private static GcEvent parseUnifiedLine(String line) {
        // Try pause pattern
        Matcher m = UNIFIED_PAUSE.matcher(line);
        if (m.find()) {
            double timestamp = Double.parseDouble(m.group(1));
            String type = m.group(2).trim();
            String cause = m.group(3).trim();
            long heapBefore = toKB(Long.parseLong(m.group(4)), m.group(5));
            long heapAfter = toKB(Long.parseLong(m.group(6)), m.group(7));
            long heapTotal = toKB(Long.parseLong(m.group(8)), m.group(9));
            long pauseMs = (long) Double.parseDouble(m.group(10));
            return new GcEvent(timestamp, type, cause, pauseMs, heapBefore, heapAfter, heapTotal);
        }

        // Try concurrent pattern
        Matcher cm = UNIFIED_CONCURRENT.matcher(line);
        if (cm.find()) {
            double timestamp = Double.parseDouble(cm.group(1));
            String phase = cm.group(2);
            long durationMs = (long) Double.parseDouble(cm.group(3));
            return new GcEvent(timestamp, "Concurrent " + phase, "Concurrent", durationMs, 0, 0, 0);
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
        long pauseMs = (long) (Double.parseDouble(m.group(7)) * 1000);
        String type = full ? "Full" : "Young";

        return new GcEvent(timestamp, type, cause, pauseMs, heapBefore, heapAfter, heapTotal);
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
