package io.argus.cli.classleak;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Persistent snapshot of {@code jcmd VM.classloader_stats} output for trend/diff analysis.
 *
 * <p>Serialised as a minimal JSON document (no external library dependency).
 * JSON shape:
 * <pre>
 * {
 *   "capturedAtEpochSec": 1715000000,
 *   "entries": [
 *     {"address":"0x...","parent":"0x...","type":"...","classCount":735,"chunkBytes":5586944,"blockBytes":5581408}
 *   ]
 * }
 * </pre>
 *
 * @param capturedAtEpochSec  Unix epoch second when the snapshot was taken
 * @param entries             parsed classloader rows, ordered as returned by the JVM
 */
public final class ClassLeakSnapshot {

    private final long capturedAtEpochSec;
    private final List<ClassLoaderEntry> entries;

    public ClassLeakSnapshot(long capturedAtEpochSec, List<ClassLoaderEntry> entries) {
        this.capturedAtEpochSec = capturedAtEpochSec;
        this.entries = entries;
    }

    public long capturedAtEpochSec() { return capturedAtEpochSec; }
    public List<ClassLoaderEntry> entries() { return entries; }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof ClassLeakSnapshot)) return false;
        ClassLeakSnapshot that = (ClassLeakSnapshot) o;
        return capturedAtEpochSec == that.capturedAtEpochSec
                && java.util.Objects.equals(entries, that.entries);
    }

    @Override
    public int hashCode() {
        return java.util.Objects.hash(capturedAtEpochSec, entries);
    }

    @Override
    public String toString() {
        return "ClassLeakSnapshot[capturedAtEpochSec=" + capturedAtEpochSec
                + ", entries=" + entries + "]";
    }

    // ── Serialization ────────────────────────────────────────────────────────

    public static void save(Path file, List<ClassLoaderEntry> entries) throws IOException {
        long now = System.currentTimeMillis() / 1000L;
        StringBuilder sb = new StringBuilder();
        sb.append("{\"capturedAtEpochSec\":").append(now).append(",\"entries\":[");
        boolean first = true;
        for (ClassLoaderEntry e : entries) {
            if (!first) sb.append(',');
            first = false;
            sb.append('{');
            sb.append("\"address\":\"").append(escape(e.address())).append('"');
            sb.append(",\"parent\":\"").append(escape(e.parent())).append('"');
            sb.append(",\"type\":\"").append(escape(e.type())).append('"');
            sb.append(",\"classCount\":").append(e.classCount());
            sb.append(",\"chunkBytes\":").append(e.chunkBytes());
            sb.append(",\"blockBytes\":").append(e.blockBytes());
            sb.append('}');
        }
        sb.append("]}");
        Files.writeString(file, sb.toString());
    }

    public static ClassLeakSnapshot load(Path file) throws IOException {
        String json = Files.readString(file);
        long capturedAt = parseLong(json, "capturedAtEpochSec", 0);
        List<ClassLoaderEntry> entries = parseEntries(json);
        return new ClassLeakSnapshot(capturedAt, entries);
    }

    // ── Internal JSON parsing ─────────────────────────────────────────────────

    private static long parseLong(String json, String key, long defaultValue) {
        Matcher m = Pattern.compile("\"" + Pattern.quote(key) + "\"\\s*:\\s*(-?\\d+)").matcher(json);
        return m.find() ? Long.parseLong(m.group(1)) : defaultValue;
    }

    private static List<ClassLoaderEntry> parseEntries(String json) {
        List<ClassLoaderEntry> out = new ArrayList<>();
        int arrStart = json.indexOf("\"entries\"");
        if (arrStart < 0) return out;
        int open = json.indexOf('[', arrStart);
        int close = matchingBracket(json, open);
        if (open < 0 || close < 0) return out;

        // Match each object in the array
        Pattern obj = Pattern.compile(
                "\\{[^}]*\"address\"\\s*:\\s*\"([^\"]*)\"[^}]*" +
                "\"parent\"\\s*:\\s*\"([^\"]*)\"[^}]*" +
                "\"type\"\\s*:\\s*\"([^\"]*)\"[^}]*" +
                "\"classCount\"\\s*:\\s*(\\d+)[^}]*" +
                "\"chunkBytes\"\\s*:\\s*(\\d+)[^}]*" +
                "\"blockBytes\"\\s*:\\s*(\\d+)[^}]*\\}");
        Matcher m = obj.matcher(json.substring(open + 1, close));
        while (m.find()) {
            out.add(new ClassLoaderEntry(
                    m.group(1),
                    m.group(2),
                    m.group(3),
                    Long.parseLong(m.group(4)),
                    Long.parseLong(m.group(5)),
                    Long.parseLong(m.group(6))
            ));
        }
        return out;
    }

    private static int matchingBracket(String s, int open) {
        if (open < 0) return -1;
        int depth = 0;
        for (int i = open; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '[') depth++;
            else if (c == ']' && --depth == 0) return i;
        }
        return -1;
    }

    private static String escape(String s) {
        if (s == null) return "";
        StringBuilder sb = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"': sb.append("\\\""); break;
                case '\\': sb.append("\\\\"); break;
                case '\n': sb.append("\\n"); break;
                case '\r': sb.append("\\r"); break;
                case '\t': sb.append("\\t"); break;
                default: if (c < 0x20) sb.append(String.format("\\u%04x", (int) c)); else sb.append(c); break;
            }
        }
        return sb.toString();
    }
}
