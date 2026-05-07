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
public record ClassLeakDiff(
        long baseEpochSec,
        long currentEpochSec,
        List<Row> rows
) {

    public enum Severity { OK, WARNING, CRITICAL }

    /**
     * A single diff row — one classloader, base vs. current counts.
     *
     * @param address      classloader address (stable identity key)
     * @param type         classloader type name
     * @param baseCount    class count in the baseline snapshot (0 if loader is new)
     * @param currentCount class count in the current snapshot (0 if loader vanished)
     * @param isNew        true when this loader did not exist in the baseline
     */
    public record Row(
            String address,
            String type,
            long baseCount,
            long currentCount,
            boolean isNew
    ) {
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
