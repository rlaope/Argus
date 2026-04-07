package io.argus.cli.command;

import io.argus.cli.config.CliConfig;
import io.argus.cli.config.Messages;
import io.argus.cli.model.GcResult;
import io.argus.cli.model.GcUtilResult;
import io.argus.cli.model.HeapResult;
import io.argus.cli.model.HistoResult;
import io.argus.cli.model.InfoResult;
import io.argus.cli.model.ThreadResult;
import io.argus.cli.provider.GcProvider;
import io.argus.cli.provider.GcUtilProvider;
import io.argus.cli.provider.HeapProvider;
import io.argus.cli.provider.HistoProvider;
import io.argus.cli.provider.InfoProvider;
import io.argus.cli.provider.ProviderRegistry;
import io.argus.cli.provider.ThreadProvider;
import io.argus.cli.render.AnsiStyle;
import io.argus.cli.render.RichRenderer;
import io.argus.core.command.CommandGroup;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Comprehensive JVM diagnostic report — collects all available metrics in one view.
 */
public final class ReportCommand implements Command {

    private static final int WIDTH = RichRenderer.DEFAULT_WIDTH;
    private static final int HISTO_TOP = 3;
    private static final int BAR_WIDTH = 16;

    @Override
    public String name() {
        return "report";
    }

    @Override public CommandGroup group() { return CommandGroup.PROFILING; }

    @Override
    public String description(Messages messages) {
        return messages.get("cmd.report.desc");
    }

    @Override
    public void execute(String[] args, CliConfig config, ProviderRegistry registry, Messages messages) {
        if (args.length == 0) {
            System.err.println(messages.get("error.pid.required"));
            return;
        }

        long pid;
        try {
            pid = Long.parseLong(args[0]);
        } catch (NumberFormatException e) {
            System.err.println(messages.get("error.pid.invalid", args[0]));
            return;
        }

        String sourceOverride = null;
        boolean json = "json".equals(config.format());
        boolean useColor = config.color();

        for (int i = 1; i < args.length; i++) {
            String arg = args[i];
            if (arg.startsWith("--source=")) {
                sourceOverride = arg.substring(9);
            } else if (arg.equals("--format=json")) {
                json = true;
            }
        }

        String source = sourceOverride != null ? sourceOverride : config.defaultSource();

        // Fetch all data sources with graceful degradation
        InfoResult infoResult = null;
        HeapResult heapResult = null;
        GcResult gcResult = null;
        GcUtilResult gcUtilResult = null;
        ThreadResult threadResult = null;
        HistoResult histoResult = null;

        InfoProvider infoProvider = registry.findInfoProvider(pid, sourceOverride);
        if (infoProvider != null) {
            try { infoResult = infoProvider.getVmInfo(pid); } catch (Exception ignored) {}
        }

        HeapProvider heapProvider = registry.findHeapProvider(pid, sourceOverride);
        if (heapProvider != null) {
            try { heapResult = heapProvider.getHeapInfo(pid); } catch (Exception ignored) {}
        }

        GcProvider gcProvider = registry.findGcProvider(pid, sourceOverride);
        if (gcProvider != null) {
            try { gcResult = gcProvider.getGcInfo(pid); } catch (Exception ignored) {}
        }

        GcUtilProvider gcUtilProvider = registry.findGcUtilProvider(pid, sourceOverride);
        if (gcUtilProvider != null) {
            try { gcUtilResult = gcUtilProvider.getGcUtil(pid); } catch (Exception ignored) {}
        }

        ThreadProvider threadProvider = registry.findThreadProvider(pid, sourceOverride);
        if (threadProvider != null) {
            try { threadResult = threadProvider.getThreadDump(pid); } catch (Exception ignored) {}
        }

        HistoProvider histoProvider = registry.findHistoProvider(pid, sourceOverride);
        if (histoProvider != null) {
            try { histoResult = histoProvider.getHistogram(pid, HISTO_TOP); } catch (Exception ignored) {}
        }

        if (json) {
            printJson(pid, infoResult, heapResult, gcResult, gcUtilResult, threadResult, histoResult);
            return;
        }

        List<String> warnings = buildWarnings(useColor, heapResult, gcResult, gcUtilResult, threadResult);

        System.out.print(RichRenderer.brandedHeader(useColor, "report", messages.get("desc.report")));
        System.out.println(RichRenderer.boxHeader(useColor, messages.get("header.report"),
                WIDTH, "pid:" + pid, "source:" + source));

        // JVM Info section
        if (infoResult != null) {
            System.out.println(RichRenderer.emptyLine(WIDTH));
            System.out.println(RichRenderer.boxLine(
                    AnsiStyle.style(useColor, AnsiStyle.BOLD) + "  \u25b8 JVM Info"
                    + AnsiStyle.style(useColor, AnsiStyle.RESET), WIDTH));
            String vmLine = "    " + infoResult.vmName() + " " + infoResult.vmVersion()
                    + "    Uptime: " + RichRenderer.formatDuration(infoResult.uptimeMs());
            System.out.println(RichRenderer.boxLine(vmLine, WIDTH));
        }

        // Memory section
        if (heapResult != null) {
            System.out.println(RichRenderer.emptyLine(WIDTH));
            System.out.println(RichRenderer.boxLine(
                    AnsiStyle.style(useColor, AnsiStyle.BOLD) + "  \u25b8 Memory"
                    + AnsiStyle.style(useColor, AnsiStyle.RESET), WIDTH));

            long usedBytes = heapResult.used();
            long committedBytes = heapResult.committed();
            double usedPct = committedBytes > 0 ? (usedBytes * 100.0) / committedBytes : 0.0;
            long freeBytes = committedBytes - usedBytes;

            String bar = RichRenderer.progressBar(useColor, usedPct, BAR_WIDTH);
            String heapLine = "    Heap    " + bar + "  "
                    + RichRenderer.formatBytes(usedBytes) + " / "
                    + RichRenderer.formatBytes(committedBytes)
                    + "  (" + String.format("%.0f%%", usedPct) + ")";
            System.out.println(RichRenderer.boxLine(heapLine, WIDTH));
            System.out.println(RichRenderer.boxLine("    Free    " + RichRenderer.formatBytes(freeBytes), WIDTH));
        }

        // GC section
        if (gcResult != null && gcResult.totalEvents() > 0) {
            System.out.println(RichRenderer.emptyLine(WIDTH));
            System.out.println(RichRenderer.boxLine(
                    AnsiStyle.style(useColor, AnsiStyle.BOLD) + "  \u25b8 GC"
                    + AnsiStyle.style(useColor, AnsiStyle.RESET), WIDTH));

            String gcLine1 = "    Events: " + RichRenderer.formatNumber(gcResult.totalEvents())
                    + "    Pause: " + String.format("%.0fs", gcResult.totalPauseMs() / 1000.0)
                    + "    Overhead: " + String.format("%.1f%%", gcResult.overheadPercent());
            System.out.println(RichRenderer.boxLine(gcLine1, WIDTH));
        }

        if (gcUtilResult != null) {
            if (gcResult == null || gcResult.totalEvents() == 0) {
                System.out.println(RichRenderer.emptyLine(WIDTH));
                System.out.println(RichRenderer.boxLine(
                        AnsiStyle.style(useColor, AnsiStyle.BOLD) + "  \u25b8 GC"
                        + AnsiStyle.style(useColor, AnsiStyle.RESET), WIDTH));
            }
            String gcUtilLine = String.format(
                    "    S0: %.0f%%  S1: %.0f%%  Eden: %.0f%%  Old: %.0f%%  Meta: %.0f%%",
                    gcUtilResult.s0(), gcUtilResult.s1(), gcUtilResult.eden(),
                    gcUtilResult.old(), gcUtilResult.meta());
            System.out.println(RichRenderer.boxLine(gcUtilLine, WIDTH));
        }

        // Threads section
        if (threadResult != null) {
            System.out.println(RichRenderer.emptyLine(WIDTH));
            System.out.println(RichRenderer.boxLine(
                    AnsiStyle.style(useColor, AnsiStyle.BOLD) + "  \u25b8 Threads"
                    + AnsiStyle.style(useColor, AnsiStyle.RESET), WIDTH));

            String threadLine1 = "    Total: " + threadResult.totalThreads()
                    + "    Virtual: " + threadResult.virtualThreads()
                    + "    Platform: " + threadResult.platformThreads();
            System.out.println(RichRenderer.boxLine(threadLine1, WIDTH));

            if (!threadResult.stateDistribution().isEmpty()) {
                StringBuilder sb2 = new StringBuilder("    ");
                boolean first = true;
                for (Map.Entry<String, Integer> entry : threadResult.stateDistribution().entrySet()) {
                    if (!first) sb2.append("  ");
                    sb2.append(entry.getKey()).append(": ").append(entry.getValue());
                    first = false;
                }
                System.out.println(RichRenderer.boxLine(sb2.toString(), WIDTH));
            }
        }

        // Top Heap Objects section
        if (histoResult != null && !histoResult.entries().isEmpty()) {
            System.out.println(RichRenderer.emptyLine(WIDTH));
            System.out.println(RichRenderer.boxLine(
                    AnsiStyle.style(useColor, AnsiStyle.BOLD) + "  \u25b8 Top Heap Objects"
                    + AnsiStyle.style(useColor, AnsiStyle.RESET), WIDTH));

            for (int i = 0; i < histoResult.entries().size(); i++) {
                HistoResult.Entry e = histoResult.entries().get(i);
                String line = "    " + (i + 1) + ". "
                        + RichRenderer.padRight(RichRenderer.humanClassName(e.className()), 20)
                        + "  " + RichRenderer.formatNumber(e.instances()) + " instances"
                        + "   " + RichRenderer.formatBytes(e.bytes());
                System.out.println(RichRenderer.boxLine(line, WIDTH));
            }
        }

        // Warnings section
        if (!warnings.isEmpty()) {
            System.out.println(RichRenderer.emptyLine(WIDTH));
            System.out.println(RichRenderer.boxLine(
                    AnsiStyle.style(useColor, AnsiStyle.BOLD) + "  \u25b8 Warnings"
                    + AnsiStyle.style(useColor, AnsiStyle.RESET), WIDTH));
            for (String warning : warnings) {
                System.out.println(RichRenderer.boxLine("    " + warning, WIDTH));
            }
        }

        System.out.println(RichRenderer.emptyLine(WIDTH));
        System.out.println(RichRenderer.boxFooter(useColor, null, WIDTH));
    }

    private static List<String> buildWarnings(boolean useColor, HeapResult heapResult,
            GcResult gcResult, GcUtilResult gcUtilResult, ThreadResult threadResult) {
        List<String> warnings = new ArrayList<>();
        String warn = AnsiStyle.style(useColor, AnsiStyle.YELLOW) + "\u26a0"
                + AnsiStyle.style(useColor, AnsiStyle.RESET);

        if (heapResult != null && heapResult.committed() > 0) {
            double usedPct = (heapResult.used() * 100.0) / heapResult.committed();
            if (usedPct > 85.0) {
                warnings.add(warn + " Heap usage at " + String.format("%.0f%%", usedPct)
                        + " \u2014 consider increasing heap or investigating leaks");
            }
        }

        if (gcUtilResult != null) {
            if (gcUtilResult.old() > 85.0) {
                warnings.add(warn + " Old Gen at " + String.format("%.0f%%", gcUtilResult.old())
                        + " \u2014 consider increasing heap or investigating leaks");
            }
            if (gcUtilResult.meta() > 90.0) {
                warnings.add(warn + " Metaspace at " + String.format("%.0f%%", gcUtilResult.meta())
                        + " \u2014 near limit");
            }
        }

        if (gcResult != null && gcResult.overheadPercent() > 2.0) {
            warnings.add(warn + " GC overhead " + String.format("%.1f%%", gcResult.overheadPercent())
                    + " \u2014 monitor for increases");
        }

        if (threadResult != null && threadResult.totalThreads() > 0) {
            Integer blocked = threadResult.stateDistribution().get("BLOCKED");
            if (blocked != null) {
                double blockedPct = (blocked * 100.0) / threadResult.totalThreads();
                if (blockedPct > 10.0) {
                    warnings.add(warn + " BLOCKED threads at " + String.format("%.0f%%", blockedPct)
                            + " \u2014 possible lock contention");
                }
            }
        }

        return warnings;
    }

    private static void printJson(long pid, InfoResult info, HeapResult heap, GcResult gc,
            GcUtilResult gcUtil, ThreadResult threads, HistoResult histo) {
        StringBuilder sb = new StringBuilder();
        sb.append("{\"pid\":").append(pid);

        if (info != null) {
            sb.append(",\"info\":{")
              .append("\"vmName\":\"").append(RichRenderer.escapeJson(info.vmName())).append('"')
              .append(",\"vmVersion\":\"").append(RichRenderer.escapeJson(info.vmVersion())).append('"')
              .append(",\"uptimeMs\":").append(info.uptimeMs())
              .append('}');
        }

        if (heap != null) {
            sb.append(",\"heap\":{")
              .append("\"used\":").append(heap.used())
              .append(",\"committed\":").append(heap.committed())
              .append(",\"max\":").append(heap.max())
              .append('}');
        }

        if (gc != null) {
            sb.append(",\"gc\":{")
              .append("\"totalEvents\":").append(gc.totalEvents())
              .append(",\"totalPauseMs\":").append(String.format("%.2f", gc.totalPauseMs()))
              .append(",\"overheadPercent\":").append(String.format("%.2f", gc.overheadPercent()))
              .append('}');
        }

        if (gcUtil != null) {
            sb.append(",\"gcUtil\":{")
              .append("\"s0\":").append(String.format("%.1f", gcUtil.s0()))
              .append(",\"s1\":").append(String.format("%.1f", gcUtil.s1()))
              .append(",\"eden\":").append(String.format("%.1f", gcUtil.eden()))
              .append(",\"old\":").append(String.format("%.1f", gcUtil.old()))
              .append(",\"meta\":").append(String.format("%.1f", gcUtil.meta()))
              .append('}');
        }

        if (threads != null) {
            sb.append(",\"threads\":{")
              .append("\"total\":").append(threads.totalThreads())
              .append(",\"virtual\":").append(threads.virtualThreads())
              .append(",\"platform\":").append(threads.platformThreads())
              .append(",\"states\":{");
            boolean first = true;
            for (Map.Entry<String, Integer> e : threads.stateDistribution().entrySet()) {
                if (!first) sb.append(',');
                sb.append('"').append(RichRenderer.escapeJson(e.getKey())).append("\":").append(e.getValue());
                first = false;
            }
            sb.append("}}");
        }

        if (histo != null) {
            sb.append(",\"topObjects\":[");
            for (int i = 0; i < histo.entries().size(); i++) {
                HistoResult.Entry e = histo.entries().get(i);
                if (i > 0) sb.append(',');
                sb.append("{\"className\":\"").append(RichRenderer.escapeJson(e.className())).append('"')
                  .append(",\"instances\":").append(e.instances())
                  .append(",\"bytes\":").append(e.bytes())
                  .append('}');
            }
            sb.append(']');
        }

        sb.append('}');
        System.out.println(sb);
    }
}
