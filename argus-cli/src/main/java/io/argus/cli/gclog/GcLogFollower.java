package io.argus.cli.gclog;

import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Tails a growing GC log file, polling for new lines and parsing them.
 * Thread-safe for the poll loop pattern (single consumer).
 */
public final class GcLogFollower {

    private final Path logFile;
    private final int maxEvents;

    private long filePosition = 0;
    private boolean formatDetected = false;
    private boolean unified = false;
    private final StringBuilder lineBuffer = new StringBuilder();

    public GcLogFollower(Path logFile, int maxEvents) {
        this.logFile = logFile;
        this.maxEvents = maxEvents;
    }

    public GcLogFollower(Path logFile) {
        this(logFile, 1000);
    }

    /**
     * Reads the entire file from the beginning, returning all parsed events.
     * Resets the follower position to end of file.
     */
    public synchronized List<GcEvent> readAll() throws IOException {
        filePosition = 0;
        lineBuffer.setLength(0);
        formatDetected = false;
        unified = false;
        return pollNewEvents();
    }

    /**
     * Reads new bytes since last position, parses any complete lines,
     * and returns newly parsed GcEvents. Returns empty list if no new data.
     */
    public synchronized List<GcEvent> pollNewEvents() throws IOException {
        try (RandomAccessFile raf = new RandomAccessFile(logFile.toFile(), "r")) {
            long fileLength = raf.length();
            if (fileLength <= filePosition) {
                return Collections.emptyList();
            }

            raf.seek(filePosition);
            long bytesToRead = fileLength - filePosition;
            byte[] buf = new byte[(int) Math.min(bytesToRead, 4 * 1024 * 1024)]; // 4MB cap
            int bytesRead = raf.read(buf);
            if (bytesRead <= 0) {
                return Collections.emptyList();
            }

            filePosition += bytesRead;

            String chunk = new String(buf, 0, bytesRead, StandardCharsets.UTF_8);
            lineBuffer.append(chunk);

            List<GcEvent> events = new ArrayList<>();
            int start = 0;
            int len = lineBuffer.length();
            for (int i = 0; i < len; i++) {
                if (lineBuffer.charAt(i) == '\n') {
                    String line = lineBuffer.substring(start, i).stripTrailing();
                    start = i + 1;
                    if (line.isEmpty()) continue;

                    // Auto-detect format from first non-empty line
                    if (!formatDetected) {
                        unified = line.contains("[gc") || line.contains("[info]")
                                || (line.startsWith("[") && (line.contains("s]") || line.contains("T")));
                        formatDetected = true;
                    }

                    GcEvent event = parseLine(line, unified);
                    if (event != null) events.add(event);
                }
            }
            // Keep any incomplete line in buffer
            lineBuffer.delete(0, start);

            return events;
        }
    }

    /**
     * Parses a single GC log line using the detected format.
     * Package-private to allow reuse from tests.
     */
    static GcEvent parseLine(String line, boolean unified) {
        return unified ? parseUnifiedLine(line) : parseLegacyLine(line);
    }

    // ── Unified (JDK 9+) ────────────────────────────────────────────────────

    private static GcEvent parseUnifiedLine(String line) {
        double timestamp = extractTimestamp(line);
        if (timestamp < 0) return null;

        java.util.regex.Matcher m = GcLogPatterns.UNIFIED_PAUSE.matcher(line);
        if (m.find()) {
            String type = m.group(1).trim();
            long heapBefore = toKB(Long.parseLong(m.group(2)), m.group(3));
            long heapAfter  = toKB(Long.parseLong(m.group(4)), m.group(5));
            long heapTotal  = toKB(Long.parseLong(m.group(6)), m.group(7));
            double pauseMs  = Double.parseDouble(m.group(8));

            String cause = "";
            java.util.regex.Matcher cm = GcLogPatterns.UNIFIED_CAUSE.matcher(line);
            if (cm.find()) cause = cm.group(1).trim();

            return new GcEvent(timestamp, type, cause, pauseMs, heapBefore, heapAfter, heapTotal);
        }

        java.util.regex.Matcher zm = GcLogPatterns.ZGC_PAUSE.matcher(line);
        if (zm.find() && line.contains("ZGC")) {
            return new GcEvent(timestamp, "ZGC Pause " + zm.group(1), "ZGC",
                    Double.parseDouble(zm.group(2)), 0, 0, 0);
        }

        java.util.regex.Matcher zcm = GcLogPatterns.ZGC_CYCLE.matcher(line);
        if (zcm.find()) {
            long heapBefore = toKB(Long.parseLong(zcm.group(2)), zcm.group(3));
            long heapAfter  = toKB(Long.parseLong(zcm.group(4)), zcm.group(5));
            return new GcEvent(timestamp, "ZGC Cycle", zcm.group(1), 0, heapBefore, heapAfter, 0);
        }

        java.util.regex.Matcher sm = GcLogPatterns.SHENANDOAH_PAUSE.matcher(line);
        if (sm.find()) {
            return new GcEvent(timestamp, "Shenandoah " + sm.group(1), "Shenandoah",
                    Double.parseDouble(sm.group(2)), 0, 0, 0);
        }

        java.util.regex.Matcher cm2 = GcLogPatterns.UNIFIED_CONCURRENT.matcher(line);
        if (cm2.find()) {
            return new GcEvent(timestamp, "Concurrent " + cm2.group(1), "Concurrent",
                    Double.parseDouble(cm2.group(2)), 0, 0, 0);
        }

        return null;
    }

    private static GcEvent parseLegacyLine(String line) {
        java.util.regex.Matcher m = GcLogPatterns.LEGACY_GC.matcher(line);
        if (!m.find()) return null;

        double timestamp = Double.parseDouble(m.group(1));
        boolean full    = m.group(2) != null;
        String cause    = m.group(3).trim();
        long heapBefore = Long.parseLong(m.group(4));
        long heapAfter  = Long.parseLong(m.group(5));
        long heapTotal  = Long.parseLong(m.group(6));
        double pauseMs  = Double.parseDouble(m.group(7)) * 1000;

        return new GcEvent(timestamp, full ? "Full" : "Young", cause,
                pauseMs, heapBefore, heapAfter, heapTotal);
    }

    private static double extractTimestamp(String line) {
        java.util.regex.Matcher m = GcLogPatterns.TIMESTAMP_UPTIME.matcher(line);
        if (m.find()) return Double.parseDouble(m.group(1));

        java.util.regex.Matcher im = GcLogPatterns.TIMESTAMP_ISO.matcher(line);
        if (im.find()) {
            try {
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
            default  -> value;
        };
    }

    public int maxEvents() { return maxEvents; }
}
