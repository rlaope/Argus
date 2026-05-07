package io.argus.cli.render;

import io.argus.cli.model.MethodSample;
import io.argus.cli.model.ProfileResult;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Renders an ASCII flame graph (top-N leaf view) from a {@link ProfileResult}.
 *
 * <p><b>Layout note:</b> This is the "top-N stack" fallback layout described in the feature
 * spec. It shows the top {@code maxLeaves} hot leaf methods as horizontal bars proportional
 * to their sample percentage, together with the most common root frame on the same line.
 * A full hierarchical Brendan-Gregg-style flame layout (stacked frames) is tracked as a
 * separate follow-up task.
 *
 * <p>When {@code collapsedRaw} is available on the result the renderer also derives
 * per-leaf "most common call-chain root" by scanning the raw collapsed stacks; otherwise
 * roots are omitted.
 *
 * <p>Color categories:
 * <ul>
 *   <li>GC / GC_task / [GC] frames — RED</li>
 *   <li>JNI / native / _stub frames — YELLOW</li>
 *   <li>idle / epoll / park / sleep  — DIM</li>
 *   <li>everything else (Java)       — CYAN</li>
 * </ul>
 */
public final class AsciiFlameRenderer {

    private static final int DEFAULT_MAX_LEAVES = 20;
    private static final char BLOCK = '█';

    private AsciiFlameRenderer() {}

    /**
     * Renders the ASCII flame graph to a {@link StringBuilder} and returns it as a String.
     *
     * @param result    profiling result (must not be {@code null}, status must be "ok")
     * @param maxLeaves max leaf methods to display (honours {@code --top=N})
     * @param useColor  whether to emit ANSI colour codes
     * @return the fully rendered output, suitable for printing to stdout
     */
    public static String render(ProfileResult result, int maxLeaves, boolean useColor) {
        if (result == null || "error".equals(result.status())) {
            return "No profile data to render.\n";
        }

        int termWidth = RichRenderer.terminalWidth();
        // Inner bar area: leave room for box borders + label column
        // Box: "│ " (2) + content + " │" (2) = 4 chars for box frame
        // Label column: up to 40 chars + "  " gap + "(100.0%)  " = ~52 chars minimum
        // Bar column: whatever remains
        int labelWidth = 40;
        int pctWidth   = 8;  // " (XX.X%)"
        int barAreaMax = termWidth - 4 - labelWidth - pctWidth - 4; // 4 = gaps + spaces
        if (barAreaMax < 8) barAreaMax = 8;

        List<MethodSample> methods = result.topMethods();
        int limit = Math.min(maxLeaves > 0 ? maxLeaves : DEFAULT_MAX_LEAVES, methods.size());
        if (limit == 0) {
            return RichRenderer.boxHeader(useColor, "ASCII Flame", termWidth)
                    + "\n" + RichRenderer.boxLine("  No samples collected.", termWidth)
                    + "\n" + RichRenderer.boxFooter(useColor, null, termWidth) + "\n";
        }

        // Build root map from collapsedRaw if present
        Map<String, String> leafToRoot = buildRootMap(result.collapsedRaw());

        // Find the maximum sample count for bar scaling (relative to leader)
        long maxSamples = methods.isEmpty() ? 1L : methods.get(0).samples();
        if (maxSamples < 1) maxSamples = 1;

        StringBuilder out = new StringBuilder(termWidth * (limit * 3 + 6));

        // ── header ──────────────────────────────────────────────────────────
        String typeLabel = result.type() != null ? result.type() : "cpu";
        String durLabel  = result.durationSec() > 0 ? result.durationSec() + "s" : "";
        String hdrMeta   = typeLabel + (durLabel.isEmpty() ? "" : " " + durLabel);
        out.append(RichRenderer.boxHeader(useColor, "ASCII Flame", termWidth, hdrMeta));
        out.append('\n');
        out.append(RichRenderer.emptyLine(termWidth)).append('\n');

        // ── column header ────────────────────────────────────────────────────
        String colHdr = AnsiStyle.style(useColor, AnsiStyle.BOLD)
                + RichRenderer.padRight("#", 4)
                + RichRenderer.padRight("Method", labelWidth)
                + RichRenderer.padRight("%", pctWidth)
                + "Bar"
                + AnsiStyle.style(useColor, AnsiStyle.RESET);
        out.append(RichRenderer.boxLine("  " + colHdr, termWidth)).append('\n');

        String sep = AnsiStyle.style(useColor, AnsiStyle.DIM)
                + "  " + "─".repeat(4)
                + "─".repeat(labelWidth)
                + "─".repeat(pctWidth)
                + "─".repeat(Math.min(barAreaMax, 20))
                + AnsiStyle.style(useColor, AnsiStyle.RESET);
        out.append(RichRenderer.boxLine(sep, termWidth)).append('\n');

        // ── rows ─────────────────────────────────────────────────────────────
        long totalSamples = result.totalSamples();
        if (totalSamples < 1) totalSamples = 1;

        for (int i = 0; i < limit; i++) {
            MethodSample m = methods.get(i);

            // Percentage relative to total samples (matches ProfileResult semantics)
            double pct = m.percentage();

            // Bar width proportional to this method's share vs the top method
            int barWidth = (int) Math.round((double) m.samples() / maxSamples * barAreaMax);
            if (barWidth < 1 && m.samples() > 0) barWidth = 1;

            // Frame colour
            String frameColor = frameColor(useColor, m.method());
            String resetCode  = AnsiStyle.style(useColor, AnsiStyle.RESET);

            // Label: truncated method name
            String methodLabel = RichRenderer.truncate(m.method(), labelWidth - 1);
            String pctStr      = String.format("(%5.1f%%)", pct);

            // Build bar string
            String bar = frameColor + String.valueOf(BLOCK).repeat(barWidth) + resetCode;

            // Root annotation (dim, appended after bar)
            String rootAnnotation = "";
            String root = leafToRoot.get(m.method());
            if (root != null && !root.isEmpty()) {
                String shortRoot = RichRenderer.truncate(root, 24);
                rootAnnotation = AnsiStyle.style(useColor, AnsiStyle.DIM)
                        + " ← " + shortRoot + resetCode;
            }

            String rank   = RichRenderer.padRight(String.valueOf(i + 1), 4);
            String label  = frameColor + RichRenderer.padRight(methodLabel, labelWidth) + resetCode;
            String pctFmt = AnsiStyle.style(useColor, AnsiStyle.BOLD)
                    + RichRenderer.padRight(pctStr, pctWidth) + resetCode;

            String line = "  " + rank + label + pctFmt + bar + rootAnnotation;
            out.append(RichRenderer.boxLine(line, termWidth)).append('\n');
        }

        // ── footer ───────────────────────────────────────────────────────────
        out.append(RichRenderer.emptyLine(termWidth)).append('\n');
        String footerNote = limit + " hottest methods of "
                + formatWithCommas(totalSamples) + " samples  (top-N view; full flame = --flame)";
        out.append(RichRenderer.boxFooter(useColor, footerNote, termWidth)).append('\n');

        return out.toString();
    }

    // -------------------------------------------------------------------------
    // Internal helpers
    // -------------------------------------------------------------------------

    /**
     * Returns the ANSI color code for a frame based on its name.
     * Skips coloring when {@code useColor} is false.
     */
    private static String frameColor(boolean useColor, String method) {
        if (!useColor || method == null) return "";
        String lc = method.toLowerCase();
        if (lc.contains("gc") || lc.startsWith("[gc]") || lc.contains("gc_task")) {
            return AnsiStyle.RED;
        }
        if (lc.contains("_stub") || lc.startsWith("jni") || lc.contains("/native/")
                || lc.endsWith("_native") || lc.contains("::") /* C++ native */) {
            return AnsiStyle.YELLOW;
        }
        if (lc.contains("park") || lc.contains("epoll") || lc.contains("idle")
                || lc.contains("sleep") || lc.contains("wait")) {
            return AnsiStyle.DIM;
        }
        return AnsiStyle.CYAN;
    }

    /**
     * Parses collapsed stack text and builds a map from leaf-method name to its most
     * common root frame (the first segment of the heaviest stack containing that leaf).
     *
     * <p>Returns an empty map if {@code collapsedRaw} is null or blank.
     */
    static Map<String, String> buildRootMap(String collapsedRaw) {
        if (collapsedRaw == null || collapsedRaw.isBlank()) {
            return Collections.emptyMap();
        }

        // leaf -> (root -> total count)
        Map<String, Map<String, Long>> leafRootCounts = new HashMap<>();

        for (String line : collapsedRaw.split("\n")) {
            if (line.isBlank()) continue;
            int lastSpace = line.lastIndexOf(' ');
            if (lastSpace < 0) continue;
            String stack    = line.substring(0, lastSpace);
            String countStr = line.substring(lastSpace + 1).trim();
            long count;
            try { count = Long.parseLong(countStr); } catch (NumberFormatException e) { continue; }

            String[] frames = stack.split(";");
            if (frames.length == 0) continue;

            String root = frames[0].trim();
            String leaf = frames[frames.length - 1].trim();
            if (root.isEmpty() || leaf.isEmpty()) continue;

            leafRootCounts
                    .computeIfAbsent(leaf, k -> new HashMap<>())
                    .merge(root, count, Long::sum);
        }

        // For each leaf pick the root with the highest aggregate count
        Map<String, String> result = new HashMap<>();
        for (Map.Entry<String, Map<String, Long>> entry : leafRootCounts.entrySet()) {
            String leaf = entry.getKey();
            String bestRoot = Collections.max(
                    entry.getValue().entrySet(),
                    Map.Entry.comparingByValue()
            ).getKey();
            result.put(leaf, bestRoot);
        }
        return result;
    }

    private static String formatWithCommas(long n) {
        String s = String.valueOf(n);
        if (s.length() <= 3) return s;
        StringBuilder sb = new StringBuilder();
        int rem = s.length() % 3;
        if (rem > 0) sb.append(s, 0, rem);
        for (int i = rem; i < s.length(); i += 3) {
            if (sb.length() > 0) sb.append(',');
            sb.append(s, i, i + 3);
        }
        return sb.toString();
    }
}
