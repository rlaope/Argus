package io.argus.diagnostics.model;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Persistent NMT snapshot for trend / diff analysis.
 *
 * <p>Serialised as a tiny JSON document so the user can store it in a file, send it via
 * pipe, or commit it to a repo. Format matches what {@code argus nmt --format=json}
 * already emits, plus a {@code "capturedAtEpochSec"} field so the diff can compute
 * elapsed time and growth rate.
 */
public final class NmtBaseline {

    private final long capturedAtEpochSec;
    private final NmtResult snapshot;

    public NmtBaseline(long capturedAtEpochSec, NmtResult snapshot) {
        this.capturedAtEpochSec = capturedAtEpochSec;
        this.snapshot = snapshot;
    }

    public long capturedAtEpochSec() { return capturedAtEpochSec; }
    public NmtResult snapshot() { return snapshot; }

    public static void save(Path file, NmtResult snapshot) throws IOException {
        long now = System.currentTimeMillis() / 1000L;
        StringBuilder sb = new StringBuilder();
        sb.append("{\"capturedAtEpochSec\":").append(now)
          .append(",\"totalReservedKB\":").append(snapshot.totalReservedKB())
          .append(",\"totalCommittedKB\":").append(snapshot.totalCommittedKB())
          .append(",\"categories\":[");
        boolean first = true;
        for (NmtResult.NmtCategory c : snapshot.categories()) {
            if (!first) sb.append(',');
            sb.append("{\"name\":\"").append(escape(c.name())).append('"')
              .append(",\"reservedKB\":").append(c.reservedKB())
              .append(",\"committedKB\":").append(c.committedKB())
              .append('}');
            first = false;
        }
        sb.append("]}");
        Files.writeString(file, sb.toString());
    }

    public static NmtBaseline load(Path file) throws IOException {
        String json = Files.readString(file);
        long capturedAt = parseLongField(json, "capturedAtEpochSec", 0);
        long totalRes = parseLongField(json, "totalReservedKB", 0);
        long totalCom = parseLongField(json, "totalCommittedKB", 0);
        List<NmtResult.NmtCategory> cats = parseCategories(json);
        return new NmtBaseline(capturedAt, new NmtResult(totalRes, totalCom, cats));
    }

    /**
     * Diff against a current snapshot, joined by category name.
     */
    public static List<DiffRow> diff(NmtBaseline baseline, NmtResult current) {
        Map<String, NmtResult.NmtCategory> baseByName = new LinkedHashMap<>();
        for (NmtResult.NmtCategory c : baseline.snapshot().categories()) baseByName.put(c.name(), c);
        Map<String, NmtResult.NmtCategory> curByName = new LinkedHashMap<>();
        for (NmtResult.NmtCategory c : current.categories()) curByName.put(c.name(), c);

        Map<String, DiffRow> rows = new LinkedHashMap<>();
        for (var e : baseByName.entrySet()) {
            NmtResult.NmtCategory cur = curByName.get(e.getKey());
            long baseR = e.getValue().reservedKB();
            long baseC = e.getValue().committedKB();
            long curR = cur != null ? cur.reservedKB() : 0;
            long curC = cur != null ? cur.committedKB() : 0;
            rows.put(e.getKey(), new DiffRow(e.getKey(), baseR, curR, baseC, curC));
        }
        // Categories that appeared after the baseline (e.g., a new pool emerged)
        for (var e : curByName.entrySet()) {
            if (!rows.containsKey(e.getKey())) {
                rows.put(e.getKey(), new DiffRow(e.getKey(), 0, e.getValue().reservedKB(),
                        0, e.getValue().committedKB()));
            }
        }
        return new ArrayList<>(rows.values());
    }

    public static final class DiffRow {
        private final String name;
        private final long baseReservedKB;
        private final long curReservedKB;
        private final long baseCommittedKB;
        private final long curCommittedKB;

        public DiffRow(String name,
                       long baseReservedKB, long curReservedKB,
                       long baseCommittedKB, long curCommittedKB) {
            this.name = name;
            this.baseReservedKB = baseReservedKB;
            this.curReservedKB = curReservedKB;
            this.baseCommittedKB = baseCommittedKB;
            this.curCommittedKB = curCommittedKB;
        }

        public String name() { return name; }
        public long baseReservedKB() { return baseReservedKB; }
        public long curReservedKB() { return curReservedKB; }
        public long baseCommittedKB() { return baseCommittedKB; }
        public long curCommittedKB() { return curCommittedKB; }

        public long reservedDeltaKB() { return curReservedKB - baseReservedKB; }
        public long committedDeltaKB() { return curCommittedKB - baseCommittedKB; }
        public double committedDeltaPct() {
            if (baseCommittedKB == 0) return curCommittedKB > 0 ? Double.POSITIVE_INFINITY : 0;
            return (committedDeltaKB() * 100.0) / baseCommittedKB;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (!(o instanceof DiffRow)) return false;
            DiffRow that = (DiffRow) o;
            return baseReservedKB == that.baseReservedKB
                    && curReservedKB == that.curReservedKB
                    && baseCommittedKB == that.baseCommittedKB
                    && curCommittedKB == that.curCommittedKB
                    && java.util.Objects.equals(name, that.name);
        }

        @Override
        public int hashCode() {
            return java.util.Objects.hash(name, baseReservedKB, curReservedKB,
                    baseCommittedKB, curCommittedKB);
        }

        @Override
        public String toString() {
            return "DiffRow[name=" + name + ", baseReservedKB=" + baseReservedKB
                    + ", curReservedKB=" + curReservedKB + ", baseCommittedKB=" + baseCommittedKB
                    + ", curCommittedKB=" + curCommittedKB + "]";
        }
    }

    private static long parseLongField(String json, String key, long defaultValue) {
        Matcher m = Pattern.compile("\"" + Pattern.quote(key) + "\"\\s*:\\s*(-?\\d+)").matcher(json);
        return m.find() ? Long.parseLong(m.group(1)) : defaultValue;
    }

    private static List<NmtResult.NmtCategory> parseCategories(String json) {
        List<NmtResult.NmtCategory> out = new ArrayList<>();
        int arrStart = json.indexOf("\"categories\"");
        if (arrStart < 0) return out;
        int open = json.indexOf('[', arrStart);
        int close = matchingBracket(json, open);
        if (open < 0 || close < 0) return out;

        Pattern obj = Pattern.compile(
                "\\{\\s*\"name\"\\s*:\\s*\"([^\"]*)\"\\s*,\\s*\"reservedKB\"\\s*:\\s*(\\d+)\\s*,\\s*\"committedKB\"\\s*:\\s*(\\d+)\\s*}");
        Matcher m = obj.matcher(json.substring(open + 1, close));
        while (m.find()) {
            out.add(new NmtResult.NmtCategory(m.group(1),
                    Long.parseLong(m.group(2)), Long.parseLong(m.group(3))));
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
        StringBuilder sb = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"': sb.append("\\\""); break;
                case '\\': sb.append("\\\\"); break;
                case '\n': sb.append("\\n"); break;
                case '\r': sb.append("\\r"); break;
                case '\t': sb.append("\\t"); break;
                default:
                    if (c < 0x20) sb.append(String.format("\\u%04x", (int) c));
                    else sb.append(c);
                    break;
            }
        }
        return sb.toString();
    }
}
