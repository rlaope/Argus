package io.argus.cli.command;

import io.argus.cli.config.CliConfig;
import io.argus.cli.config.Messages;
import io.argus.cli.doctor.JvmSnapshot;
import io.argus.cli.doctor.JvmSnapshotCollector;
import io.argus.cli.provider.ProviderRegistry;
import io.argus.cli.render.AnsiStyle;
import io.argus.core.command.CommandGroup;

import java.io.IOException;

/**
 * Real-time terminal dashboard for JVM monitoring — htop for JVM.
 *
 * <p>Uses ANSI escape codes for cursor positioning and in-place updates.
 * Refreshes every N seconds (default 2s). Press 'q' to quit.
 *
 * <p>Currently monitors the local JVM; future: remote via agent HTTP API.
 */
public final class WatchCommand implements Command {

    private static final int DEFAULT_INTERVAL = 2;
    private static final String CLEAR_SCREEN = "\033[2J\033[H";
    private static final String HIDE_CURSOR = "\033[?25l";
    private static final String SHOW_CURSOR = "\033[?25h";

    @Override public String name() { return "watch"; }
    @Override public CommandGroup group() { return CommandGroup.MONITORING; }

    @Override
    public String description(Messages messages) {
        return messages.get("cmd.watch.desc");
    }

    @Override
    public void execute(String[] args, CliConfig config, ProviderRegistry registry, Messages messages) {
        int interval = DEFAULT_INTERVAL;
        boolean useColor = config.color();
        long pid = 0;

        for (int i = 0; i < args.length; i++) {
            if (args[i].startsWith("--interval=")) {
                try { interval = Integer.parseInt(args[i].substring(11)); } catch (NumberFormatException ignored) {}
            } else if (args[i].equals("--interval") && i + 1 < args.length) {
                try { interval = Integer.parseInt(args[++i]); } catch (NumberFormatException ignored) {}
            } else if (!args[i].startsWith("--")) {
                try { pid = Long.parseLong(args[i]); } catch (NumberFormatException ignored) {}
            }
        }

        final long targetPid = pid;

        // Set terminal to non-canonical mode for key detection
        System.out.print(HIDE_CURSOR);

        // Track history for sparklines
        double[] heapHistory = new double[30];
        double[] cpuHistory = new double[30];
        int historyIdx = 0;

        try {
            setRawMode(true);

            while (true) {
                JvmSnapshot s = JvmSnapshotCollector.collect(targetPid);

                // Update history (circular buffer)
                int writePos = historyIdx % 30;
                heapHistory[writePos] = s.heapUsagePercent();
                cpuHistory[writePos] = s.processCpuLoad() >= 0 ? s.processCpuLoad() * 100 : 0;
                historyIdx++;

                // Render
                StringBuilder out = new StringBuilder();
                out.append(CLEAR_SCREEN);
                renderDashboard(out, s, heapHistory, cpuHistory, Math.min(historyIdx, 30),
                        historyIdx, interval, useColor);
                System.out.print(out);
                System.out.flush();

                // Wait with key check
                long deadline = System.currentTimeMillis() + interval * 1000L;
                while (System.currentTimeMillis() < deadline) {
                    if (System.in.available() > 0) {
                        int key = System.in.read();
                        if (key == 'q' || key == 'Q' || key == 3) { // q or Ctrl+C
                            return;
                        }
                        if (key == 'r' || key == 'R') break; // force refresh
                    }
                    Thread.sleep(100);
                }
            }
        } catch (InterruptedException | IOException e) {
            // Normal exit
        } finally {
            System.out.print(SHOW_CURSOR);
            setRawMode(false);
            System.out.println();
        }
    }

    private void renderDashboard(StringBuilder sb, JvmSnapshot s,
                                 double[] heapHist, double[] cpuHist, int histLen,
                                 int writePos, int interval, boolean c) {
        String B = AnsiStyle.style(c, AnsiStyle.BOLD);
        String R = AnsiStyle.style(c, AnsiStyle.RESET);
        String D = AnsiStyle.style(c, AnsiStyle.DIM);
        String CY = AnsiStyle.style(c, AnsiStyle.CYAN);
        String GR = AnsiStyle.style(c, AnsiStyle.GREEN);
        String YL = AnsiStyle.style(c, AnsiStyle.YELLOW);
        String RD = AnsiStyle.style(c, AnsiStyle.RED);

        // Header
        sb.append(" ").append(B).append(CY).append("argus watch").append(R)
                .append(D).append("  pid:").append(ProcessHandle.current().pid())
                .append("  ").append(interval).append("s refresh")
                .append("  uptime:").append(formatDuration(s.uptimeMs()))
                .append(R).append("\n");
        sb.append(" ").append("─".repeat(72)).append("\n");

        // Heap
        double heapPct = s.heapUsagePercent();
        sb.append(B).append(" Heap  ").append(R)
                .append(progressBar(heapPct, 20, c))
                .append(String.format("  %s/%s  ", formatBytes(s.heapUsed()), formatBytes(s.heapMax())))
                .append(colorPct(heapPct, c)).append(String.format("%.0f%%", heapPct)).append(R)
                .append("  ").append(sparkline(heapHist, histLen, writePos)).append("\n");

        // Memory pools (Old gen if available)
        for (var pool : s.memoryPools().values()) {
            String name = pool.name().toLowerCase();
            if (name.contains("old") || name.contains("tenured")) {
                double pct = pool.usagePercent();
                sb.append(D).append(" Old   ").append(R)
                        .append(progressBar(pct, 20, c))
                        .append(String.format("  %s/%s  ", formatBytes(pool.used()), formatBytes(pool.max())))
                        .append(colorPct(pct, c)).append(String.format("%.0f%%", pct)).append(R).append("\n");
                break;
            }
        }

        // Metaspace
        for (var pool : s.memoryPools().values()) {
            if (pool.name().toLowerCase().contains("metaspace") && pool.max() > 0) {
                double pct = pool.usagePercent();
                sb.append(D).append(" Meta  ").append(R)
                        .append(progressBar(pct, 20, c))
                        .append(String.format("  %s/%s  ", formatBytes(pool.used()), formatBytes(pool.max())))
                        .append(String.format("%.0f%%", pct)).append("\n");
                break;
            }
        }

        // CPU
        double cpuPct = s.processCpuLoad() >= 0 ? s.processCpuLoad() * 100 : 0;
        sb.append(B).append(" CPU   ").append(R)
                .append(progressBar(cpuPct, 20, c))
                .append(String.format("  %.0f%%", cpuPct))
                .append("  procs:").append(s.availableProcessors())
                .append("  ").append(sparkline(cpuHist, histLen, writePos)).append("\n");

        // GC
        double gcOh = s.gcOverheadPercent();
        String gcColor = gcOh < 3 ? GR : gcOh < 10 ? YL : RD;
        sb.append(B).append(" GC    ").append(R)
                .append(s.gcAlgorithm())
                .append(String.format("  %d collections  %dms total  ", s.totalGcCount(), s.totalGcTimeMs()))
                .append(gcColor).append(String.format("%.1f%% overhead", gcOh)).append(R).append("\n");

        // Threads
        int blocked = s.blockedThreads();
        String blkColor = blocked == 0 ? GR : blocked < 5 ? YL : RD;
        sb.append(B).append(" Thr   ").append(R)
                .append(s.threadCount()).append(" total  ")
                .append(s.threadStates().getOrDefault("RUNNABLE", 0)).append(" run  ")
                .append(s.threadStates().getOrDefault("WAITING", 0)
                        + s.threadStates().getOrDefault("TIMED_WAITING", 0)).append(" wait  ")
                .append(blkColor).append(blocked).append(" block").append(R)
                .append("  ").append(s.deadlockedThreads() > 0
                        ? RD + s.deadlockedThreads() + " DEADLOCK" + R : GR + "0 dead" + R)
                .append("  daemon:").append(s.daemonThreadCount())
                .append("  peak:").append(s.peakThreadCount()).append("\n");

        // Buffers
        for (var buf : s.bufferPools()) {
            if (buf.name().toLowerCase().contains("direct") && buf.count() > 0) {
                sb.append(D).append(" NIO   ").append(R)
                        .append("direct: ").append(buf.count()).append(" bufs ")
                        .append(formatBytes(buf.used())).append("\n");
                break;
            }
        }

        // Classes
        sb.append(D).append(" Cls   ").append(R)
                .append(String.format("%,d loaded  %,d unloaded",
                        s.loadedClassCount(), s.unloadedClassCount())).append("\n");

        // Footer
        sb.append(" ").append("─".repeat(72)).append("\n");
        sb.append(D).append(" q:quit  r:refresh").append(R);
    }

    private static String progressBar(double pct, int width, boolean c) {
        int filled = (int) (pct / 100.0 * width);
        filled = Math.max(0, Math.min(filled, width));
        String color = pct < 60 ? AnsiStyle.style(c, AnsiStyle.GREEN)
                : pct < 85 ? AnsiStyle.style(c, AnsiStyle.YELLOW)
                : AnsiStyle.style(c, AnsiStyle.RED);
        return "[" + color + "\u2588".repeat(filled)
                + AnsiStyle.style(c, AnsiStyle.DIM) + "\u2591".repeat(width - filled)
                + AnsiStyle.style(c, AnsiStyle.RESET) + "]";
    }

    private static String colorPct(double pct, boolean c) {
        return pct < 60 ? AnsiStyle.style(c, AnsiStyle.GREEN)
                : pct < 85 ? AnsiStyle.style(c, AnsiStyle.YELLOW)
                : AnsiStyle.style(c, AnsiStyle.RED);
    }

    private static String sparkline(double[] data, int len, int writePos) {
        if (len == 0) return "";
        String[] blocks = {"▁", "▂", "▃", "▄", "▅", "▆", "▇", "█"};
        double max = 0;
        for (int i = 0; i < len; i++) max = Math.max(max, data[i]);
        if (max == 0) max = 1;
        StringBuilder sb = new StringBuilder();
        int show = Math.min(len, 15);
        // Circular buffer: oldest readable entry is (writePos - show)
        int oldest = (writePos - show + 30) % 30;
        for (int i = 0; i < show; i++) {
            int idx = (int) (data[(oldest + i) % 30] / max * 7);
            sb.append(blocks[Math.max(0, Math.min(idx, 7))]);
        }
        return sb.toString();
    }

    private static String formatBytes(long bytes) {
        if (bytes < 1024) return bytes + "B";
        if (bytes < 1024 * 1024) return (bytes / 1024) + "K";
        if (bytes < 1024L * 1024 * 1024) return (bytes / (1024 * 1024)) + "M";
        return String.format("%.1fG", bytes / (1024.0 * 1024 * 1024));
    }

    private static String formatDuration(long ms) {
        long sec = ms / 1000;
        if (sec < 60) return sec + "s";
        if (sec < 3600) return (sec / 60) + "m" + (sec % 60) + "s";
        return (sec / 3600) + "h" + ((sec % 3600) / 60) + "m";
    }

    private static void setRawMode(boolean raw) {
        try {
            String sttyCmd = raw ? "stty raw -echo < /dev/tty" : "stty cooked echo < /dev/tty";
            new ProcessBuilder("/bin/sh", "-c", sttyCmd)
                    .inheritIO()
                    .start()
                    .waitFor();
        } catch (Exception ignored) {}
    }
}
