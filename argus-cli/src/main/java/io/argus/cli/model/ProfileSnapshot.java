package io.argus.cli.model;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Persistent profile snapshot for trend / diff analysis.
 *
 * <p>Serialised as a JSON document so the user can store it in a file, send it
 * via pipe, or commit it to a repo. Mirrors the {@link NmtBaseline} pattern.
 *
 * <p>All methods from the profiling run are stored (not truncated to top-N) so
 * that diffs are accurate.
 */
public final class ProfileSnapshot {

    private static final DateTimeFormatter ISO = DateTimeFormatter
            .ofPattern("yyyy-MM-dd'T'HH:mm:ss'Z'")
            .withZone(ZoneOffset.UTC);

    private final String argusVersion;
    private final String capturedAt;   // ISO-8601 UTC
    private final long   capturedAtEpochSec;
    private final long   pid;
    private final String type;
    private final int    durationSec;
    private final long   totalSamples;
    private final List<MethodSample> methods;

    public ProfileSnapshot(String argusVersion, String capturedAt, long capturedAtEpochSec,
                           long pid, String type, int durationSec, long totalSamples,
                           List<MethodSample> methods) {
        this.argusVersion       = argusVersion;
        this.capturedAt         = capturedAt;
        this.capturedAtEpochSec = capturedAtEpochSec;
        this.pid                = pid;
        this.type               = type;
        this.durationSec        = durationSec;
        this.totalSamples       = totalSamples;
        this.methods            = List.copyOf(methods);
    }

    public String argusVersion()        { return argusVersion; }
    public String capturedAt()          { return capturedAt; }
    public long   capturedAtEpochSec()  { return capturedAtEpochSec; }
    public long   pid()                 { return pid; }
    public String type()                { return type; }
    public int    durationSec()         { return durationSec; }
    public long   totalSamples()        { return totalSamples; }
    public List<MethodSample> methods() { return methods; }

    // -------------------------------------------------------------------------
    // Save / Load
    // -------------------------------------------------------------------------

    public static void save(Path file, long pid, ProfileResult result) throws IOException {
        Instant now = Instant.now();
        long epochSec = now.getEpochSecond();
        String capturedAt = ISO.format(now);

        String ver = ProfileSnapshot.class.getPackage() != null
                && ProfileSnapshot.class.getPackage().getImplementationVersion() != null
                ? ProfileSnapshot.class.getPackage().getImplementationVersion() : "dev";

        StringBuilder sb = new StringBuilder();
        sb.append("{\"argusVersion\":\"").append(escape(ver)).append('"')
          .append(",\"capturedAt\":\"").append(capturedAt).append('"')
          .append(",\"capturedAtEpochSec\":").append(epochSec)
          .append(",\"pid\":").append(pid)
          .append(",\"type\":\"").append(escape(result.type() != null ? result.type() : "cpu")).append('"')
          .append(",\"durationSec\":").append(result.durationSec())
          .append(",\"totalSamples\":").append(result.totalSamples())
          .append(",\"methods\":[");

        boolean first = true;
        for (MethodSample m : result.topMethods()) {
            if (!first) sb.append(',');
            sb.append("{\"method\":\"").append(escape(m.method())).append('"')
              .append(",\"samples\":").append(m.samples())
              .append(",\"percentage\":").append(String.format("%.2f", m.percentage()))
              .append('}');
            first = false;
        }
        sb.append("]}");
        Files.writeString(file, sb.toString());
    }

    public static ProfileSnapshot load(Path file) throws IOException {
        String json = Files.readString(file);
        String ver         = parseStringField(json, "argusVersion", "unknown");
        String capturedAt  = parseStringField(json, "capturedAt", "");
        long   epochSec    = parseLongField(json, "capturedAtEpochSec", 0);
        long   pid         = parseLongField(json, "pid", -1);
        String type        = parseStringField(json, "type", "cpu");
        int    durationSec = (int) parseLongField(json, "durationSec", 0);
        long   total       = parseLongField(json, "totalSamples", 0);
        List<MethodSample> methods = parseMethods(json);
        return new ProfileSnapshot(ver, capturedAt, epochSec, pid, type, durationSec, total, methods);
    }

    // -------------------------------------------------------------------------
    // Diff
    // -------------------------------------------------------------------------

    public record DiffEntry(String method,
                            long beforeSamples,
                            long afterSamples,
                            long deltaSamples,
                            double deltaPct,
                            String state) {}   // "changed" | "new" | "gone"

    /**
     * Computes a diff between two snapshots, sorted by |delta| descending.
     * Trivial entries (both &lt; 10 samples AND |delta| &lt; 5) are filtered out.
     */
    public static List<DiffEntry> diff(ProfileSnapshot before, ProfileSnapshot after) {
        Map<String, Long> beforeMap = new LinkedHashMap<>();
        for (MethodSample m : before.methods()) beforeMap.put(m.method(), m.samples());
        Map<String, Long> afterMap = new LinkedHashMap<>();
        for (MethodSample m : after.methods()) afterMap.put(m.method(), m.samples());

        Map<String, DiffEntry> entries = new LinkedHashMap<>();
        for (var e : beforeMap.entrySet()) {
            long b = e.getValue();
            long a = afterMap.getOrDefault(e.getKey(), 0L);
            long delta = a - b;
            double pct = after.totalSamples() > 0
                    ? (delta * 100.0) / after.totalSamples() : 0.0;
            String state = a == 0 ? "gone" : "changed";
            entries.put(e.getKey(), new DiffEntry(e.getKey(), b, a, delta, pct, state));
        }
        for (var e : afterMap.entrySet()) {
            if (!entries.containsKey(e.getKey())) {
                long a = e.getValue();
                double pct = after.totalSamples() > 0 ? (a * 100.0) / after.totalSamples() : 0.0;
                entries.put(e.getKey(), new DiffEntry(e.getKey(), 0L, a, a, pct, "new"));
            }
        }

        List<DiffEntry> result = new ArrayList<>();
        for (DiffEntry de : entries.values()) {
            // skip zero-delta entries (self-vs-self or truly unchanged methods)
            if (de.deltaSamples() == 0 && !"new".equals(de.state()) && !"gone".equals(de.state())) {
                continue;
            }
            // filter trivially small entries
            if (de.beforeSamples() < 10 && de.afterSamples() < 10 && Math.abs(de.deltaSamples()) < 5) {
                continue;
            }
            result.add(de);
        }
        result.sort((x, y) -> Long.compare(Math.abs(y.deltaSamples()), Math.abs(x.deltaSamples())));
        return result;
    }

    // -------------------------------------------------------------------------
    // JSON parsing helpers (no external deps — mirrors NmtBaseline pattern)
    // -------------------------------------------------------------------------

    private static long parseLongField(String json, String key, long def) {
        Matcher m = Pattern.compile("\"" + Pattern.quote(key) + "\"\\s*:\\s*(-?\\d+)").matcher(json);
        return m.find() ? Long.parseLong(m.group(1)) : def;
    }

    private static String parseStringField(String json, String key, String def) {
        Matcher m = Pattern.compile("\"" + Pattern.quote(key) + "\"\\s*:\\s*\"([^\"]*)\"").matcher(json);
        return m.find() ? m.group(1) : def;
    }

    private static List<MethodSample> parseMethods(String json) {
        List<MethodSample> out = new ArrayList<>();
        int arrStart = json.indexOf("\"methods\"");
        if (arrStart < 0) return out;
        int open  = json.indexOf('[', arrStart);
        int close = matchingBracket(json, open);
        if (open < 0 || close < 0) return out;

        Pattern obj = Pattern.compile(
                "\\{\\s*\"method\"\\s*:\\s*\"([^\"]*)\"\\s*," +
                "\\s*\"samples\"\\s*:\\s*(\\d+)\\s*," +
                "\\s*\"percentage\"\\s*:\\s*([0-9.]+)\\s*}");
        Matcher m = obj.matcher(json.substring(open + 1, close));
        while (m.find()) {
            out.add(new MethodSample(m.group(1),
                    Long.parseLong(m.group(2)),
                    Double.parseDouble(m.group(3))));
        }
        return out;
    }

    private static int matchingBracket(String s, int open) {
        if (open < 0) return -1;
        int depth = 0;
        for (int i = open; i < s.length(); i++) {
            char c = s.charAt(i);
            if      (c == '[') depth++;
            else if (c == ']' && --depth == 0) return i;
        }
        return -1;
    }

    public static String escape(String s) {
        StringBuilder sb = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"'  -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                default   -> {
                    if (c < 0x20) sb.append(String.format("\\u%04x", (int) c));
                    else sb.append(c);
                }
            }
        }
        return sb.toString();
    }
}
