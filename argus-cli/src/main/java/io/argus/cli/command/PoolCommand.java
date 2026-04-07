package io.argus.cli.command;

import io.argus.cli.config.CliConfig;
import io.argus.cli.config.Messages;
import io.argus.cli.model.PoolResult;
import io.argus.cli.provider.PoolProvider;
import io.argus.cli.provider.ProviderRegistry;
import io.argus.cli.render.AnsiStyle;
import io.argus.cli.render.RichRenderer;
import io.argus.core.command.CommandGroup;

import java.util.Map;

/**
 * Shows thread pool analysis grouped by pool name.
 */
public final class PoolCommand implements Command {

    private static final int WIDTH = RichRenderer.DEFAULT_WIDTH;
    private static final int BAR_WIDTH = 12;

    @Override
    public String name() { return "pool"; }

    @Override public CommandGroup group() { return CommandGroup.THREADS; }

    @Override
    public String description(Messages messages) {
        return messages.get("cmd.pool.desc");
    }

    @Override
    public void execute(String[] args, CliConfig config, ProviderRegistry registry, Messages messages) {
        if (args.length == 0) { System.err.println(messages.get("error.pid.required")); return; }

        long pid;
        try { pid = Long.parseLong(args[0]); }
        catch (NumberFormatException e) { System.err.println(messages.get("error.pid.invalid", args[0])); return; }

        String sourceOverride = null;
        boolean json = "json".equals(config.format());
        boolean useColor = config.color();
        int top = 0;
        for (int i = 1; i < args.length; i++) {
            if (args[i].startsWith("--source=")) sourceOverride = args[i].substring(9);
            else if (args[i].equals("--format=json")) json = true;
            else if (args[i].startsWith("--top=")) {
                try { top = Integer.parseInt(args[i].substring(6)); } catch (NumberFormatException ignored) {}
            }
        }

        String source = sourceOverride != null ? sourceOverride : config.defaultSource();
        PoolProvider provider = registry.findPoolProvider(pid, sourceOverride);
        if (provider == null) { System.err.println(messages.get("error.provider.none", pid)); return; }

        PoolResult result = provider.getPoolInfo(pid);

        if (json) { printJson(result); return; }

        System.out.print(RichRenderer.brandedHeader(useColor, "pool", messages.get("desc.pool")));
        System.out.println(RichRenderer.boxHeader(useColor, messages.get("header.pool"), WIDTH, "pid:" + pid, "source:" + source));
        System.out.println(RichRenderer.emptyLine(WIDTH));

        // Summary
        String summary = messages.get("label.threads") + ": " + result.totalThreads()
                + "    " + messages.get("pool.groups") + ": " + result.totalPools();
        System.out.println(RichRenderer.boxLine(summary, WIDTH));
        System.out.println(RichRenderer.emptyLine(WIDTH));

        // Table header
        String bold = AnsiStyle.style(useColor, AnsiStyle.BOLD);
        String reset = AnsiStyle.style(useColor, AnsiStyle.RESET);
        String dim = AnsiStyle.style(useColor, AnsiStyle.DIM);

        String headerLine = bold + RichRenderer.padRight(messages.get("pool.name"), 30)
                + RichRenderer.padLeft(messages.get("label.count"), 6)
                + "  " + messages.get("label.state") + reset;
        System.out.println(RichRenderer.boxLine(headerLine, WIDTH));

        String sep = dim + "\u2500".repeat(WIDTH - 6) + reset;
        System.out.println(RichRenderer.boxLine(sep, WIDTH));

        int shown = 0;
        for (PoolResult.PoolInfo pool : result.pools()) {
            if (top > 0 && shown >= top) break;

            String name = RichRenderer.padRight(RichRenderer.truncate(pool.name(), 28), 30);
            String count = RichRenderer.padLeft(String.valueOf(pool.threadCount()), 6);

            StringBuilder stateStr = new StringBuilder();
            stateStr.append("  ");
            for (Map.Entry<String, Integer> entry : pool.stateDistribution().entrySet()) {
                if (stateStr.length() > 2) stateStr.append(" ");
                String stateColor = getStateColor(useColor, entry.getKey());
                stateStr.append(stateColor)
                        .append(abbreviateState(entry.getKey())).append(":").append(entry.getValue())
                        .append(AnsiStyle.style(useColor, AnsiStyle.RESET));
            }

            System.out.println(RichRenderer.boxLine(name + count + stateStr, WIDTH));
            shown++;
        }

        if (top > 0 && result.pools().size() > top) {
            System.out.println(RichRenderer.boxLine(
                    dim + "  ... " + (result.pools().size() - top) + " more" + reset, WIDTH));
        }

        System.out.println(RichRenderer.boxFooter(useColor, null, WIDTH));
    }

    private static String getStateColor(boolean useColor, String state) {
        return switch (state) {
            case "RUNNABLE" -> AnsiStyle.style(useColor, AnsiStyle.GREEN);
            case "BLOCKED" -> AnsiStyle.style(useColor, AnsiStyle.RED);
            case "WAITING", "TIMED_WAITING" -> AnsiStyle.style(useColor, AnsiStyle.YELLOW);
            default -> "";
        };
    }

    private static String abbreviateState(String state) {
        return switch (state) {
            case "RUNNABLE" -> "RUN";
            case "BLOCKED" -> "BLK";
            case "WAITING" -> "WAIT";
            case "TIMED_WAITING" -> "TWAIT";
            default -> state;
        };
    }

    private static void printJson(PoolResult result) {
        StringBuilder sb = new StringBuilder();
        sb.append("{\"totalThreads\":").append(result.totalThreads());
        sb.append(",\"totalPools\":").append(result.totalPools());
        sb.append(",\"pools\":[");
        for (int i = 0; i < result.pools().size(); i++) {
            PoolResult.PoolInfo p = result.pools().get(i);
            if (i > 0) sb.append(',');
            sb.append("{\"name\":\"").append(RichRenderer.escapeJson(p.name())).append('"');
            sb.append(",\"threadCount\":").append(p.threadCount());
            sb.append(",\"states\":{");
            boolean first = true;
            for (Map.Entry<String, Integer> e : p.stateDistribution().entrySet()) {
                if (!first) sb.append(',');
                sb.append('"').append(e.getKey()).append("\":").append(e.getValue());
                first = false;
            }
            sb.append("}}");
        }
        sb.append("]}");
        System.out.println(sb);
    }
}
