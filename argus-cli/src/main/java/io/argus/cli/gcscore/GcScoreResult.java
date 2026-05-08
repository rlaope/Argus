package io.argus.cli.gcscore;

import java.util.List;

/**
 * Full Health Score Card result: per-axis scores, overall grade, and hints.
 */
public final class GcScoreResult {
    private final List<AxisScore> axes;
    private final int overall;
    private final String grade;
    private final String summary;
    private final List<String> hints;

    public GcScoreResult(List<AxisScore> axes, int overall, String grade,
                         String summary, List<String> hints) {
        this.axes = axes;
        this.overall = overall;
        this.grade = grade;
        this.summary = summary;
        this.hints = hints;
    }

    public List<AxisScore> axes() { return axes; }
    public int overall() { return overall; }
    public String grade() { return grade; }
    public String summary() { return summary; }
    public List<String> hints() { return hints; }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof GcScoreResult)) return false;
        GcScoreResult that = (GcScoreResult) o;
        return overall == that.overall
                && java.util.Objects.equals(axes, that.axes)
                && java.util.Objects.equals(grade, that.grade)
                && java.util.Objects.equals(summary, that.summary)
                && java.util.Objects.equals(hints, that.hints);
    }

    @Override
    public int hashCode() {
        return java.util.Objects.hash(axes, overall, grade, summary, hints);
    }

    @Override
    public String toString() {
        return "GcScoreResult[axes=" + axes + ", overall=" + overall + ", grade=" + grade
                + ", summary=" + summary + ", hints=" + hints + "]";
    }
}
