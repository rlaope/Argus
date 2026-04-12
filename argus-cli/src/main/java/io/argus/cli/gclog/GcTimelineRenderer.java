package io.argus.cli.gclog;

import io.argus.cli.render.AnsiStyle;
import io.argus.cli.render.RichRenderer;

import java.util.List;

/**
 * Renders an ASCII heatmap of GC pause events over time.
 */
public final class GcTimelineRenderer {

    // Block characters for magnitude: space + 8 increasing heights
    private static final char[] BLOCKS = {' ', '▁', '▂', '▃', '▄', '▅', '▆', '▇', '█'};
    // Density characters for event count
    private static final char[] DENSITY = {' ', '·', ':', '+', '#'};

    private GcTimelineRenderer() {}

    /**
     * Render pause timeline heatmap as box-formatted lines.
     *
     * @param events all GC events (sorted by timestamp)
     * @param p50Ms  median pause for color thresholds
     * @param p95Ms  95th percentile pause for color thresholds
     * @param width  total box width (RichRenderer.DEFAULT_WIDTH)
     * @param color  whether to use ANSI colors
     * @return rendered lines ready for System.out.print
     */
    public static String render(List<GcEvent> events, long p50Ms, long p95Ms, int width, boolean color) {
        // Filter to only pause (non-concurrent) events
        List<GcEvent> pauses = events.stream()
                .filter(e -> !e.isConcurrent())
                .toList();

        StringBuilder out = new StringBuilder();

        if (pauses.isEmpty()) {
            out.append(RichRenderer.boxLine("  No pause events to display.", width)).append('\n');
            return out.toString();
        }

        // Chart width: leave room for "│ " prefix, label (6 chars), " │" suffix = 4 border chars + 2 label + 1 space = 7
        // width - 4 (box border) - 7 (label + space) = width - 11, clamp min 10
        int chartWidth = Math.max(10, width - 11);

        double firstTs = pauses.getFirst().timestampSec();
        double lastTs = pauses.getLast().timestampSec();
        double rangeTs = lastTs - firstTs;

        // Handle degenerate case: all events at same timestamp
        if (rangeTs <= 0.0) {
            rangeTs = 1.0;
        }

        // Bucket data: maxPause and event count per bucket
        double[] bucketMaxPause = new double[chartWidth];
        int[] bucketCount = new int[chartWidth];
        boolean[] bucketHasFullGc = new boolean[chartWidth];

        for (GcEvent e : pauses) {
            double relTs = e.timestampSec() - firstTs;
            int idx = (int) (relTs / rangeTs * (chartWidth - 1));
            idx = Math.max(0, Math.min(chartWidth - 1, idx));
            if (e.pauseMs() > bucketMaxPause[idx]) {
                bucketMaxPause[idx] = e.pauseMs();
            }
            bucketCount[idx]++;
            if (e.isFullGc()) {
                bucketHasFullGc[idx] = true;
            }
        }

        // Global max for scaling
        double globalMax = 1.0;
        for (double v : bucketMaxPause) {
            if (v > globalMax) globalMax = v;
        }

        // Max count for density scaling
        int maxCount = 1;
        for (int c : bucketCount) {
            if (c > maxCount) maxCount = c;
        }

        // Build magnitude row
        StringBuilder magRow = new StringBuilder();
        for (int i = 0; i < chartWidth; i++) {
            if (bucketCount[i] == 0) {
                magRow.append(' ');
                continue;
            }
            if (bucketHasFullGc[i]) {
                magRow.append(AnsiStyle.style(color, AnsiStyle.RED, AnsiStyle.BOLD));
                magRow.append('F');
                magRow.append(AnsiStyle.style(color, AnsiStyle.RESET));
                continue;
            }
            double pause = bucketMaxPause[i];
            int blockIdx = (int) Math.round(pause / globalMax * 8);
            blockIdx = Math.max(1, Math.min(8, blockIdx));
            char block = BLOCKS[blockIdx];

            String col;
            if (pause < p50Ms) {
                col = AnsiStyle.style(color, AnsiStyle.GREEN);
            } else if (pause <= p95Ms) {
                col = AnsiStyle.style(color, AnsiStyle.YELLOW);
            } else {
                col = AnsiStyle.style(color, AnsiStyle.RED);
            }
            magRow.append(col).append(block).append(AnsiStyle.style(color, AnsiStyle.RESET));
        }

        // Build density row
        StringBuilder densRow = new StringBuilder();
        for (int i = 0; i < chartWidth; i++) {
            int cnt = bucketCount[i];
            if (cnt == 0) {
                densRow.append(' ');
            } else {
                int di = (int) Math.round((double) cnt / maxCount * 4);
                di = Math.max(1, Math.min(4, di));
                densRow.append(AnsiStyle.style(color, AnsiStyle.DIM))
                       .append(DENSITY[di])
                       .append(AnsiStyle.style(color, AnsiStyle.RESET));
            }
        }

        // Build time axis labels
        // Show label every ~20 chars, always include start and end
        String[] axisLabels = buildAxisLabels(chartWidth, firstTs, lastTs);

        // Compose axis row: place labels at their positions
        char[] axisChars = new char[chartWidth];
        java.util.Arrays.fill(axisChars, ' ');
        for (int i = 0; i < axisLabels.length; i++) {
            if (axisLabels[i] == null) continue;
            String lbl = axisLabels[i];
            // Center label: start at i - lbl.length()/2, but clamp
            int start = i - lbl.length() / 2;
            start = Math.max(0, Math.min(chartWidth - lbl.length(), start));
            for (int j = 0; j < lbl.length() && start + j < chartWidth; j++) {
                axisChars[start + j] = lbl.charAt(j);
            }
        }
        String axisRow = new String(axisChars);

        // Output all rows wrapped in boxLine
        String labelMag  = RichRenderer.padRight("pause", 6);
        String labelDens = RichRenderer.padRight("count", 6);
        String labelAxis = RichRenderer.padRight("time", 6);

        out.append(RichRenderer.boxLine("  " + labelMag + " " + magRow, width)).append('\n');
        out.append(RichRenderer.boxLine("  " + labelDens + " " + densRow, width)).append('\n');
        out.append(RichRenderer.boxLine("  " + labelAxis + " " + axisRow, width)).append('\n');
        out.append(RichRenderer.emptyLine(width)).append('\n');

        // Legend
        String legend = AnsiStyle.style(color, AnsiStyle.GREEN) + "▄ <p50"
                + AnsiStyle.style(color, AnsiStyle.RESET) + "  "
                + AnsiStyle.style(color, AnsiStyle.YELLOW) + "▄ p50-p95"
                + AnsiStyle.style(color, AnsiStyle.RESET) + "  "
                + AnsiStyle.style(color, AnsiStyle.RED) + "▄ >p95"
                + AnsiStyle.style(color, AnsiStyle.RESET) + "  "
                + AnsiStyle.style(color, AnsiStyle.RED, AnsiStyle.BOLD) + "F Full GC"
                + AnsiStyle.style(color, AnsiStyle.RESET)
                + "   p50=" + p50Ms + "ms  p95=" + p95Ms + "ms";
        out.append(RichRenderer.boxLine("  " + legend, width)).append('\n');

        return out.toString();
    }

    /**
     * Build axis label strings indexed by their chart position.
     * Labels appear at start, end, and evenly-spaced intervals (~every 20 chars).
     */
    private static String[] buildAxisLabels(int chartWidth, double firstTs, double lastTs) {
        String[] labels = new String[chartWidth];

        // Determine label positions: start, end, and intermediates every ~20 chars
        int step = 20;
        int numLabels = Math.max(2, chartWidth / step + 1);

        for (int n = 0; n < numLabels; n++) {
            int pos;
            if (n == 0) {
                pos = 0;
            } else if (n == numLabels - 1) {
                pos = chartWidth - 1;
            } else {
                pos = (int) ((double) n / (numLabels - 1) * (chartWidth - 1));
            }
            double ts = firstTs + (double) pos / (chartWidth - 1) * (lastTs - firstTs);
            labels[pos] = formatTime(ts);
        }
        return labels;
    }

    private static String formatTime(double sec) {
        if (sec < 60.0) return String.format("%.0fs", sec);
        if (sec < 3600.0) return String.format("%.0fm", sec / 60.0);
        return String.format("%.0fh", sec / 3600.0);
    }
}
