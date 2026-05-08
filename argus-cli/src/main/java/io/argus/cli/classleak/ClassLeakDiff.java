package io.argus.cli.classleak;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Diff between two {@link ClassLeakSnapshot}s, joined by classloader {@code address}.
 *
 * <p>Growth thresholds:
 * <ul>
 *   <li>WARNING  — class count grew ≥ 10 %</li>
 *   <li>CRITICAL — class count grew ≥ 50 % OR added &gt; 1 000 new classes</li>
 * </ul>
 */
public final class ClassLeakDiff {

    private final long baseEpochSec;
    private final long currentEpochSec;
    private final List<Row> rows;

    public ClassLeakDiff(long baseEpochSec, long currentEpochSec, List<Row> rows) {
        this.baseEpochSec = baseEpochSec;
        this.currentEpochSec = currentEpochSec;
        this.rows = rows;
    }

    public long baseEpochSec() { return baseEpochSec; }
    public long currentEpochSec() { return currentEpochSec; }
    public List<Row> rows() { return rows; }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof ClassLeakDiff)) return false;
        ClassLeakDiff that = (ClassLeakDiff) o;
        return baseEpochSec == that.baseEpochSec
                && currentEpochSec == that.currentEpochSec
                && java.util.Objects.equals(rows, that.rows);
    }

    @Override
    public int hashCode() {
        return java.util.Objects.hash(baseEpochSec, currentEpochSec, rows);
    }

    @Override
    public String toString() {
        return "ClassLeakDiff[baseEpochSec=" + baseEpochSec
                + ", currentEpochSec=" + currentEpochSec + ", rows=" + rows + "]";
    }

    public enum Severity { OK, WARNING, CRITICAL }

    /**
     * A single diff row — one classloader, base vs. current counts.
     */
    public static final class Row {
        private final String address;
        private final String type;
        private final long baseCount;
        private final long currentCount;
        private final boolean isNew;

        public Row(String address, String type, long baseCount,
                   long currentCount, boolean isNew) {
            this.address = address;
            this.type = type;
            this.baseCount = baseCount;
            this.currentCount = currentCount;
            this.isNew = isNew;
        }

        public String address() { return address; }
        public String type() { return type; }
        public long baseCount() { return baseCount; }
        public long currentCount() { return currentCount; }
        public boolean isNew() { return isNew; }

        public long delta() { return currentCount - baseCount; }

        public double deltaPct() {
            if (baseCount == 0) return currentCount > 0 ? Double.POSITIVE_INFINITY : 0.0;
            return delta() * 100.0 / baseCount;
        }

        public Severity severity() {
            long d = delta();
            if (d <= 0) return Severity.OK;
            if (isNew) return Severity.WARNING; // brand-new loader always worth noting
            double pct = deltaPct();
            if (pct >= 50.0 || d > 1_000) return Severity.CRITICAL;
            if (pct >= 10.0) return Severity.WARNING;
            return Severity.OK;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (!(o instanceof Row)) return false;
            Row that = (Row) o;
            return baseCount == that.baseCount
                    && currentCount == that.currentCount
                    && isNew == that.isNew
                    && java.util.Objects.equals(address, that.address)
                    && java.util.Objects.equals(type, that.type);
        }

        @Override
        public int hashCode() {
            return java.util.Objects.hash(address, type, baseCount, currentCount, isNew);
        }

        @Override
        public String toString() {
            return "Row[address=" + address + ", type=" + type + ", baseCount=" + baseCount
                    + ", currentCount=" + currentCount + ", isNew=" + isNew + "]";
        }
    }

    // ── Factory ───────────────────────────────────────────────────────────────

    /**
     * Computes the diff between a saved baseline snapshot and the current entries.
     */
    public static ClassLeakDiff compute(ClassLeakSnapshot baseline, long currentEpochSec,
                                        List<ClassLoaderEntry> current) {
        Map<String, ClassLoaderEntry> baseByAddr = new LinkedHashMap<>();
        for (ClassLoaderEntry e : baseline.entries()) baseByAddr.put(e.address(), e);

        Map<String, ClassLoaderEntry> curByAddr = new LinkedHashMap<>();
        for (ClassLoaderEntry e : current) curByAddr.put(e.address(), e);

        List<Row> rows = new ArrayList<>();

        // Loaders present in baseline
        for (var be : baseByAddr.entrySet()) {
            ClassLoaderEntry cur = curByAddr.get(be.getKey());
            long baseCount = be.getValue().classCount();
            long curCount  = cur != null ? cur.classCount() : 0;
            String type    = cur != null ? cur.type() : be.getValue().type();
            rows.add(new Row(be.getKey(), type, baseCount, curCount, false));
        }

        // Loaders that appeared after the baseline
        for (var ce : curByAddr.entrySet()) {
            if (!baseByAddr.containsKey(ce.getKey())) {
                rows.add(new Row(ce.getKey(), ce.getValue().type(), 0, ce.getValue().classCount(), true));
            }
        }

        return new ClassLeakDiff(baseline.capturedAtEpochSec(), currentEpochSec, rows);
    }
}
