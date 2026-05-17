package io.argus.diagnostics.gcscore;

/**
 * Scored result for one KPI axis of the GC Health Score Card.
 */
public final class AxisScore {

    public enum Verdict { PASS, WARN, FAIL, NA }

    private final String name;
    private final double value;
    private final String unit;
    private final String target;
    private final int score;
    private final Verdict verdict;
    private final boolean available;

    public AxisScore(String name, double value, String unit, String target,
                     int score, Verdict verdict, boolean available) {
        this.name = name;
        this.value = value;
        this.unit = unit;
        this.target = target;
        this.score = score;
        this.verdict = verdict;
        this.available = available;
    }

    public String name() { return name; }
    public double value() { return value; }
    public String unit() { return unit; }
    public String target() { return target; }
    public int score() { return score; }
    public Verdict verdict() { return verdict; }
    public boolean available() { return available; }

    public static AxisScore na(String name, String target) {
        return new AxisScore(name, 0, "", target, 0, Verdict.NA, false);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof AxisScore)) return false;
        AxisScore that = (AxisScore) o;
        return Double.compare(that.value, value) == 0
                && score == that.score
                && available == that.available
                && java.util.Objects.equals(name, that.name)
                && java.util.Objects.equals(unit, that.unit)
                && java.util.Objects.equals(target, that.target)
                && verdict == that.verdict;
    }

    @Override
    public int hashCode() {
        return java.util.Objects.hash(name, value, unit, target, score, verdict, available);
    }

    @Override
    public String toString() {
        return "AxisScore[name=" + name + ", value=" + value + ", unit=" + unit
                + ", target=" + target + ", score=" + score + ", verdict=" + verdict
                + ", available=" + available + "]";
    }
}
