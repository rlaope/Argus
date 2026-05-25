package io.argus.cli.command;

import io.argus.cli.config.CliConfig;
import io.argus.cli.config.Messages;
import io.argus.cli.json.JsonOutput;
import io.argus.cli.model.PoolResult;
import io.argus.cli.provider.PoolProvider;
import io.argus.cli.provider.ProviderRegistry;
import io.argus.cli.render.AnsiStyle;
import io.argus.cli.render.RichRenderer;
import io.argus.core.command.CommandGroup;

import java.util.Map;

/**
 * Dispatches the `argus pool` family of subcommands.
 *
 * <ul>
 *   <li>{@code argus pool <pid>} — existing thread-pool grouping table (default).</li>
 *   <li>{@code argus pool jdbc <pid>} — JDBC connection pool state (HikariCP + Tomcat JDBC).</li>
 *   <li>{@code argus pool advise <pid>} — thread-pool sizing advisor via ThreadMXBean sampling.</li>
 * </ul>
 */
public final class PoolCommand implements Command {

    private static final int WIDTH = RichRenderer.DEFAULT_WIDTH;

    @Override
    public String name() { return "pool"; }

    @Override public CommandGroup group() { return CommandGroup.THREADS; }

    @Override
    public String description(Messages messages) {
        return messages.get("cmd.pool.desc");
    }

    @Override
    public void execute(String[] args, CliConfig config, ProviderRegistry registry, Messages messages) {
        if (args.length > 0) {
            String head = args[0];
            if ("--help".equals(head) || "-h".equals(head) || "help".equals(head)) {
                System.out.println(messages.get("cmd.pool.usage"));
                return;
            }
            if ("jdbc".equals(head)) {
                String[] rest = shift(args);
                new PoolJdbcHandler().run(rest, config, messages);
                return;
            }
            if ("advise".equals(head)) {
                String[] rest = shift(args);
                new PoolAdviseHandler().run(rest, config, messages);
                return;
            }
        }
        executeThreadPoolListing(args, config, registry, messages);
    }

    private static String[] shift(String[] args) {
        return java.util.Arrays.copyOfRange(args, 1, args.length);
    }

    private void executeThreadPoolListing(String[] args, CliConfig config,
                                          ProviderRegistry registry, Messages messages) {
        long pid = CommandUtils.parsePidOrExit(args, messages);

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
        PoolProvider provider = Providers.require(registry.find(PoolProvider.class, pid, sourceOverride), pid, messages);

        PoolResult result = provider.getPoolInfo(pid);

        if (json) { JsonOutput.println(result); return; }

        System.out.print(RichRenderer.brandedHeader(useColor, "pool", messages.get("desc.pool")));
        System.out.println(RichRenderer.boxHeader(useColor, messages.get("header.pool"), WIDTH, "pid:" + pid, "source:" + source));
        System.out.println(RichRenderer.emptyLine(WIDTH));

        String summary = messages.get("label.threads") + ": " + result.totalThreads()
                + "    " + messages.get("pool.groups") + ": " + result.totalPools();
        System.out.println(RichRenderer.boxLine(summary, WIDTH));
        System.out.println(RichRenderer.emptyLine(WIDTH));

        String bold = AnsiStyle.style(useColor, AnsiStyle.BOLD);
        String reset = AnsiStyle.style(useColor, AnsiStyle.RESET);
        String dim = AnsiStyle.style(useColor, AnsiStyle.DIM);

        String headerLine = bold + RichRenderer.padRight(messages.get("pool.name"), 30)
                + RichRenderer.padLeft(messages.get("label.count"), 6)
                + "  " + messages.get("label.state") + reset;
        System.out.println(RichRenderer.boxLine(headerLine, WIDTH));

        String sep = dim + "─".repeat(WIDTH - 6) + reset;
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
        switch (state) {
            case "RUNNABLE": return AnsiStyle.style(useColor, AnsiStyle.GREEN);
            case "BLOCKED": return AnsiStyle.style(useColor, AnsiStyle.RED);
            case "WAITING":
            case "TIMED_WAITING": return AnsiStyle.style(useColor, AnsiStyle.YELLOW);
            default: return "";
        }
    }

    private static String abbreviateState(String state) {
        switch (state) {
            case "RUNNABLE": return "RUN";
            case "BLOCKED": return "BLK";
            case "WAITING": return "WAIT";
            case "TIMED_WAITING": return "TWAIT";
            default: return state;
        }
    }

}
