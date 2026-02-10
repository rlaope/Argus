package io.argus.cli;

/**
 * Renders metrics data to the terminal using ANSI escape codes.
 */
public final class TerminalRenderer {

    private static final String RESET = "\033[0m";
    private static final String BOLD = "\033[1m";
    private static final String DIM = "\033[2m";
    private static final String GREEN = "\033[32m";
    private static final String YELLOW = "\033[33m";
    private static final String RED = "\033[31m";
    private static final String CYAN = "\033[36m";
    private static final String WHITE = "\033[37m";
    private static final String BG_RED = "\033[41m";
    private static final String CLEAR_SCREEN = "\033[2J\033[H";

    private final boolean useColor;
    private final String host;
    private final int port;
    private final int intervalSec;
    private long startTime;

    public TerminalRenderer(boolean useColor, String host, int port, int intervalSec) {
        this.useColor = useColor;
        this.host = host;
        this.port = port;
        this.intervalSec = intervalSec;
        this.startTime = System.currentTimeMillis();
    }

    /**
     * Renders a full metrics snapshot to the terminal.
     */
    public void render(MetricsSnapshot snap) {
        StringBuilder sb = new StringBuilder(2048);
        sb.append(CLEAR_SCREEN);

        if (!snap.connected()) {
            renderDisconnected(sb);
            System.out.print(sb);
            System.out.flush();
            return;
        }

        renderHeader(sb);
        renderSeparator(sb);
        renderSystemMetrics(sb, snap);
        renderSeparator(sb);
        renderVirtualThreads(sb, snap);
        renderSeparator(sb);
        renderProfilingContention(sb, snap);
        renderSeparator(sb);
        renderFooter(sb);

        System.out.print(sb);
        System.out.flush();
    }

    private void renderDisconnected(StringBuilder sb) {
        renderHeader(sb);
        sb.append('\n');
        sb.append(color(RED, BOLD)).append("  CONNECTION FAILED")
                .append(color(RESET)).append('\n');
        sb.append(color(DIM)).append("  Cannot connect to Argus server at ")
                .append(host).append(':').append(port).append(color(RESET)).append('\n');
        sb.append(color(DIM)).append("  Retrying every ").append(intervalSec)
                .append("s...").append(color(RESET)).append('\n');
    }

    private void renderHeader(StringBuilder sb) {
        String uptime = formatUptime(System.currentTimeMillis() - startTime);
        sb.append(color(BOLD, CYAN)).append(" Argus JVM Monitor")
                .append(color(RESET, DIM)).append(" | ")
                .append(host).append(':').append(port)
                .append(" | uptime ").append(uptime)
                .append(" | refresh ").append(intervalSec).append('s')
                .append(color(RESET)).append('\n');
    }

    private void renderSeparator(StringBuilder sb) {
        sb.append(color(DIM)).append(" ").append("─".repeat(65))
                .append(color(RESET)).append('\n');
    }

    private void renderSystemMetrics(StringBuilder sb, MetricsSnapshot snap) {
        // CPU
        sb.append("  CPU   ");
        appendProgressBar(sb, snap.cpuJvmPercent(), 16);
        sb.append(colorByThreshold(snap.cpuJvmPercent(), 70, 90))
                .append(String.format(" %5.1f%%", snap.cpuJvmPercent()))
                .append(color(RESET)).append(" JVM  | ")
                .append(String.format("%5.1f%%", snap.cpuMachinePercent()))
                .append(" Machine\n");

        // Heap
        double heapUsedMB = snap.heapUsedBytes() / (1024.0 * 1024.0);
        double heapCommitMB = snap.heapCommittedBytes() / (1024.0 * 1024.0);
        double heapPct = heapCommitMB > 0 ? (heapUsedMB / heapCommitMB) * 100 : 0;
        sb.append("  Heap  ");
        appendProgressBar(sb, heapPct, 16);
        sb.append(colorByThreshold(heapPct, 70, 90))
                .append(String.format(" %s/%s", formatBytes(snap.heapUsedBytes()), formatBytes(snap.heapCommittedBytes())))
                .append(color(RESET));
        sb.append("  | GC: ")
                .append(colorByThreshold(snap.gcOverheadPercent(), 2, 5))
                .append(String.format("%.1f%%", snap.gcOverheadPercent()))
                .append(color(RESET)).append(" overhead\n");

        // Metaspace + GC
        sb.append("  Meta  ")
                .append(String.format("%.0fMB", snap.metaspaceUsedMB()))
                .append(" (").append(formatNumber(snap.classCount())).append(" classes)")
                .append("        | GC events: ")
                .append(formatNumber(snap.gcTotalEvents()))
                .append(" (").append(String.format("%.0fms", snap.gcTotalPauseMs())).append(" total)\n");
    }

    private void renderVirtualThreads(StringBuilder sb, MetricsSnapshot snap) {
        sb.append("  Virtual Threads: ")
                .append(color(BOLD)).append(formatNumber(snap.activeThreads()))
                .append(color(RESET)).append(" active | ")
                .append(formatNumber(snap.startEvents())).append(" total");

        if (snap.totalPinnedEvents() > 0) {
            sb.append(" | ").append(color(YELLOW))
                    .append(formatNumber(snap.totalPinnedEvents())).append(" pinned")
                    .append(color(RESET))
                    .append(" (").append(snap.uniquePinningStacks()).append(" stacks)");
        }
        sb.append('\n');

        sb.append("  Carriers: ")
                .append(snap.carrierCount()).append(" threads")
                .append(" | avg ").append(String.format("%.1f", snap.avgVtPerCarrier()))
                .append(" VT/carrier\n");
    }

    private void renderProfilingContention(StringBuilder sb, MetricsSnapshot snap) {
        // Hot Methods column
        if (snap.profilingSamples() > 0 && !snap.hotMethods().isEmpty()) {
            sb.append(color(BOLD)).append("  Hot Methods")
                    .append(color(RESET, DIM))
                    .append(" (").append(formatNumber(snap.profilingSamples())).append(" samples)")
                    .append(color(RESET)).append('\n');

            for (var m : snap.hotMethods()) {
                String shortClass = shortenClassName(m.className());
                sb.append(colorByThreshold(m.percentage(), 30, 60))
                        .append(String.format("   %5.1f%%", m.percentage()))
                        .append(color(RESET)).append(' ')
                        .append(shortClass).append('.').append(m.methodName())
                        .append('\n');
            }
        } else {
            sb.append(color(DIM)).append("  Profiling: disabled")
                    .append(color(RESET)).append('\n');
        }

        // Contention
        if (snap.contentionEvents() > 0 && !snap.contentionHotspots().isEmpty()) {
            sb.append(color(BOLD)).append("  Contention")
                    .append(color(RESET, DIM))
                    .append(" (").append(formatNumber(snap.contentionEvents())).append(" events, ")
                    .append(String.format("%.0fms", snap.contentionTimeMs())).append(")")
                    .append(color(RESET)).append('\n');

            for (var h : snap.contentionHotspots()) {
                sb.append("   ").append(shortenClassName(h.monitorClass()))
                        .append(" (").append(h.eventCount()).append(" events)\n");
            }
        }
    }

    private void renderFooter(StringBuilder sb) {
        sb.append(color(DIM))
                .append("  q: quit | Ctrl+C: exit")
                .append(color(RESET)).append('\n');
    }

    // --- Helpers ---

    private void appendProgressBar(StringBuilder sb, double percent, int width) {
        int filled = (int) Math.round(percent / 100.0 * width);
        filled = Math.max(0, Math.min(width, filled));

        sb.append('[');
        sb.append(colorByThreshold(percent, 70, 90));
        for (int i = 0; i < width; i++) {
            sb.append(i < filled ? '\u2588' : '\u2591');
        }
        sb.append(color(RESET));
        sb.append(']');
    }

    private String colorByThreshold(double value, double warn, double crit) {
        if (!useColor) return "";
        if (value >= crit) return RED;
        if (value >= warn) return YELLOW;
        return GREEN;
    }

    private String color(String... codes) {
        if (!useColor) return "";
        return String.join("", codes);
    }

    private static String formatUptime(long ms) {
        long sec = ms / 1000;
        if (sec < 60) return sec + "s";
        long min = sec / 60;
        sec %= 60;
        if (min < 60) return min + "m " + sec + "s";
        long hr = min / 60;
        min %= 60;
        return hr + "h " + min + "m";
    }

    private static String formatBytes(long bytes) {
        if (bytes <= 0) return "0B";
        double mb = bytes / (1024.0 * 1024.0);
        if (mb >= 1024) return String.format("%.1fG", mb / 1024.0);
        return String.format("%.0fM", mb);
    }

    private static String formatNumber(long n) {
        if (n < 1000) return String.valueOf(n);
        if (n < 1_000_000) return String.format("%.1fK", n / 1000.0);
        return String.format("%.1fM", n / 1_000_000.0);
    }

    private static String shortenClassName(String className) {
        if (className == null) return "?";
        // Keep last 2 segments: "io.argus.server.ArgusServer" -> "server.ArgusServer"
        String[] parts = className.split("\\.");
        if (parts.length <= 2) return className;
        return parts[parts.length - 2] + "." + parts[parts.length - 1];
    }
}
