package io.argus.cli.llm;

import io.argus.diagnostics.doctor.Finding;

import java.util.List;

/**
 * Serializes structured diagnostic findings into a compact, schema'd context
 * payload for an LLM root-cause prompt.
 *
 * <p><b>Findings-only guarantee:</b> the payload contains ONLY the metrics,
 * categories, titles, details, and recommendations present in the supplied
 * {@link Finding} objects. No metric or number is synthesised here. The system
 * instruction explicitly forbids the model from inventing numbers, so the
 * deterministic findings remain the source of truth.
 */
public final class FindingsPrompt {

    private FindingsPrompt() {}

    /**
     * The system instruction sent alongside the findings payload. It constrains
     * the model to reason ONLY over the provided findings and to never fabricate
     * metrics, so the output stays advisory and grounded.
     */
    public static final String SYSTEM_INSTRUCTION =
            "You are a JVM diagnostics assistant. You will be given a set of structured "
            + "diagnostic findings produced by deterministic rules. Explain the most likely "
            + "root cause and how the findings relate to each other. "
            + "Use ONLY the metrics, numbers, and facts present in the FINDINGS payload below. "
            + "Do NOT invent or estimate any metric, number, threshold, or measurement that is "
            + "not explicitly present in the findings. If something is unknown, say it is unknown. "
            + "Be concise.";

    /**
     * Serializes the findings into a deterministic, line-oriented context block.
     * Each finding is rendered with its severity, category, title, detail, and
     * recommendations exactly as captured — nothing is added or rounded.
     */
    public static String serialize(List<Finding> findings) {
        StringBuilder sb = new StringBuilder();
        sb.append("FINDINGS (").append(findings.size()).append(")\n");
        int i = 1;
        for (Finding f : findings) {
            sb.append("- #").append(i++).append(' ')
              .append(f.severity().label()).append(" | ")
              .append(f.category()).append(" | ")
              .append(f.title()).append('\n');
            if (f.detail() != null && !f.detail().isEmpty()) {
                sb.append("  detail: ").append(f.detail()).append('\n');
            }
            for (String rec : f.recommendations()) {
                sb.append("  recommend: ").append(rec).append('\n');
            }
            for (String flag : f.suggestedFlags()) {
                sb.append("  flag: ").append(flag).append('\n');
            }
        }
        return sb.toString();
    }

    /**
     * Builds the full user prompt: a short instruction followed by the
     * findings payload. The returned text contains no numbers beyond those
     * already present in the findings.
     */
    public static String buildPrompt(List<Finding> findings) {
        return "Analyze the following JVM diagnostic findings and explain the likely "
                + "root cause. Reference only the data shown.\n\n"
                + serialize(findings);
    }
}
