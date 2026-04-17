package io.argus.cli.gcscore;

import java.util.List;

/**
 * Full Health Score Card result: per-axis scores, overall grade, and hints.
 *
 * @param axes       per-axis scored results in display order
 * @param overall    0–100 weighted overall score
 * @param grade      "A"/"B"/"C"/"D"/"F"
 * @param summary    one-line verdict summary (e.g. "good, minor tuning opportunities")
 * @param hints      up to 3 prioritized improvement hints
 */
public record GcScoreResult(
        List<AxisScore> axes,
        int overall,
        String grade,
        String summary,
        List<String> hints) {}
