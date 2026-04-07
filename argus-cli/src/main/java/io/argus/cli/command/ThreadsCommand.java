package io.argus.cli.command;

import io.argus.cli.config.CliConfig;
import io.argus.cli.config.Messages;
import io.argus.cli.model.ThreadResult;
import io.argus.cli.provider.ProviderRegistry;
import io.argus.cli.provider.ThreadProvider;
import io.argus.cli.render.AnsiStyle;
import io.argus.cli.render.RichRenderer;
import io.argus.core.command.CommandGroup;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Shows thread dump summary for a given PID.
 */
public final class ThreadsCommand implements Command {

    private static final int WIDTH = RichRenderer.DEFAULT_WIDTH;
    private static final int BAR_WIDTH = 16;

    @Override
    public String name() {
        return "threads";
    }

    @Override public CommandGroup group() { return CommandGroup.THREADS; }

    @Override
    public String description(Messages messages) {
        return messages.get("cmd.threads.desc");
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
        ThreadProvider provider = registry.findThreadProvider(pid, sourceOverride);
        if (provider == null) {
            System.err.println(messages.get("error.provider.none", pid));
            return;
        }

        ThreadResult result = provider.getThreadDump(pid);

        if (json) {
            printJson(result);
            return;
        }

        System.out.print(RichRenderer.brandedHeader(useColor, "threads", messages.get("desc.threads")));
        System.out.println(RichRenderer.boxHeader(useColor, messages.get("header.threads"),
                WIDTH, "pid:" + pid, "source:" + source));
        System.out.println(RichRenderer.emptyLine(WIDTH));

        StringBuilder totals = new StringBuilder();
        totals.append("Total: ").append(result.totalThreads())
              .append("    Virtual: ").append(result.virtualThreads())
              .append("    Platform: ").append(result.platformThreads());
        if (result.daemonThreads() > 0) {
            totals.append("    Daemon: ").append(result.daemonThreads());
        }
        if (result.peakThreads() > 0) {
            totals.append("    Peak: ").append(result.peakThreads());
        }
        System.out.println(RichRenderer.boxLine(totals.toString(), WIDTH));
        System.out.println(RichRenderer.emptyLine(WIDTH));

        // State distribution bars
        Map<String, Integer> states = result.stateDistribution();
        int total = result.totalThreads() > 0 ? result.totalThreads() : 1;

        // Render states in a consistent order
        Map<String, double[]> thresholds = new LinkedHashMap<>();
        thresholds.put("RUNNABLE",    new double[]{70, 90});
        thresholds.put("WAITING",     new double[]{70, 90});
        thresholds.put("TIMED_WAITING", new double[]{70, 90});
        thresholds.put("BLOCKED",     new double[]{10, 25});

        for (Map.Entry<String, Integer> entry : states.entrySet()) {
            if (!thresholds.containsKey(entry.getKey())) {
                thresholds.put(entry.getKey(), new double[]{70, 90});
            }
        }

        for (Map.Entry<String, double[]> th : thresholds.entrySet()) {
            String state = th.getKey();
            int count = states.getOrDefault(state, 0);
            if (count == 0 && !states.containsKey(state)) continue;

            double pct = (count * 100.0) / total;
            double[] wc = th.getValue();

            String bar = buildStateBar(useColor, pct, BAR_WIDTH, wc[0], wc[1]);
            String warn = (state.equals("BLOCKED") && pct >= wc[0])
                    ? "  " + AnsiStyle.style(useColor, AnsiStyle.YELLOW) + "\u26a0" + AnsiStyle.style(useColor, AnsiStyle.RESET)
                    : "";

            String label = RichRenderer.padRight(state, 14);
            String countStr = RichRenderer.padLeft(String.valueOf(count), 4);
            String pctStr = String.format("(%3.0f%%)", pct);

            System.out.println(RichRenderer.boxLine(
                    label + "  " + bar + "  " + countStr + "  " + pctStr + warn, WIDTH));
        }

        // Deadlock section
        if (!result.deadlocks().isEmpty()) {
            System.out.println(RichRenderer.emptyLine(WIDTH));
            String dlHeader = AnsiStyle.style(useColor, AnsiStyle.YELLOW) + "\u26a0 Deadlock detected: "
                    + result.deadlocks().size() + " thread pair(s)"
                    + AnsiStyle.style(useColor, AnsiStyle.RESET);
            System.out.println(RichRenderer.boxLine(dlHeader, WIDTH));

            for (ThreadResult.DeadlockInfo dl : result.deadlocks()) {
                String dlLine = "  " + RichRenderer.truncate(dl.thread1(), 20) + " \u2194 " + RichRenderer.truncate(dl.thread2(), 20);
                System.out.println(RichRenderer.boxLine(dlLine, WIDTH));
            }
        }

        System.out.println(RichRenderer.boxFooter(useColor, null, WIDTH));
    }

    private static String buildStateBar(boolean useColor, double pct, int barWidth,
                                        double warn, double crit) {
        int filled = (int) Math.round(Math.min(100.0, Math.max(0.0, pct)) / 100.0 * barWidth);
        String color = AnsiStyle.colorByThreshold(useColor, pct, warn, crit);
        StringBuilder sb = new StringBuilder();
        sb.append(color);
        for (int i = 0; i < barWidth; i++) {
            sb.append(i < filled ? '\u2588' : '\u2591');
        }
        sb.append(AnsiStyle.style(useColor, AnsiStyle.RESET));
        return sb.toString();
    }

    private static void printJson(ThreadResult result) {
        StringBuilder sb = new StringBuilder();
        sb.append("{\"totalThreads\":").append(result.totalThreads())
          .append(",\"virtualThreads\":").append(result.virtualThreads())
          .append(",\"platformThreads\":").append(result.platformThreads())
          .append(",\"stateDistribution\":{");
        boolean first = true;
        for (Map.Entry<String, Integer> e : result.stateDistribution().entrySet()) {
            if (!first) sb.append(',');
            sb.append('"').append(e.getKey()).append("\":").append(e.getValue());
            first = false;
        }
        sb.append("},\"deadlocks\":[");
        for (int i = 0; i < result.deadlocks().size(); i++) {
            ThreadResult.DeadlockInfo dl = result.deadlocks().get(i);
            if (i > 0) sb.append(',');
            sb.append("{\"thread1\":\"").append(RichRenderer.escapeJson(dl.thread1())).append('"')
              .append(",\"thread2\":\"").append(RichRenderer.escapeJson(dl.thread2())).append('"')
              .append(",\"lockClass\":\"").append(RichRenderer.escapeJson(dl.lockClass())).append('"')
              .append('}');
        }
        sb.append("]}");
        System.out.println(sb);
    }


}
