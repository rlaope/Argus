package io.argus.cli.command;

import io.argus.cli.config.CliConfig;
import io.argus.cli.config.Messages;
import io.argus.cli.model.VmFlagResult;
import io.argus.cli.provider.ProviderRegistry;
import io.argus.cli.provider.VmFlagProvider;
import io.argus.cli.render.AnsiStyle;
import io.argus.cli.render.RichRenderer;

import java.util.List;

/**
 * Shows or sets JVM VM flags via jcmd VM.flags / VM.set_flag.
 * Read mode: argus vmflag &lt;pid&gt; [--filter=pattern]
 * Set mode:  argus vmflag &lt;pid&gt; --set FlagName=value
 *            argus vmflag &lt;pid&gt; --set +FlagName   (enable boolean)
 *            argus vmflag &lt;pid&gt; --set -FlagName   (disable boolean)
 */
public final class VmFlagCommand implements Command {

    private static final int WIDTH = RichRenderer.DEFAULT_WIDTH;

    @Override
    public String name() {
        return "vmflag";
    }

    @Override
    public String description(Messages messages) {
        return messages.get("cmd.vmflag.desc");
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
        String filter = null;
        String setExpr = null;

        for (int i = 1; i < args.length; i++) {
            String arg = args[i];
            if (arg.startsWith("--source=")) {
                sourceOverride = arg.substring(9);
            } else if (arg.equals("--format=json")) {
                json = true;
            } else if (arg.startsWith("--filter=")) {
                filter = arg.substring(9);
            } else if (arg.startsWith("--set=")) {
                setExpr = arg.substring(6);
            } else if (arg.equals("--set") && i + 1 < args.length) {
                setExpr = args[++i];
            }
        }

        VmFlagProvider provider = registry.findVmFlagProvider(pid, sourceOverride);
        if (provider == null) {
            System.err.println(messages.get("error.provider.none", pid));
            return;
        }

        if (setExpr != null) {
            executeSet(pid, setExpr, provider, useColor, messages);
        } else {
            VmFlagResult result = provider.getVmFlags(pid);
            if (json) {
                printJson(result, filter);
            } else {
                System.out.print(RichRenderer.brandedHeader(useColor, "vmflag", messages.get("desc.vmflag")));
                printTable(result, pid, provider.source(), useColor, filter, messages);
            }
        }
    }

    private static void executeSet(long pid, String setExpr, VmFlagProvider provider,
                                   boolean useColor, Messages messages) {
        String flagName;
        String flagValue;

        if (setExpr.startsWith("+")) {
            flagName = setExpr.substring(1);
            flagValue = "1";
        } else if (setExpr.startsWith("-")) {
            flagName = setExpr.substring(1);
            flagValue = "0";
        } else {
            int eq = setExpr.indexOf('=');
            if (eq > 0) {
                flagName = setExpr.substring(0, eq).trim();
                flagValue = setExpr.substring(eq + 1).trim();
            } else {
                System.err.println(messages.get("error.vmflag.invalid.set"));
                return;
            }
        }

        String result = provider.setVmFlag(pid, flagName, flagValue);
        System.out.println(AnsiStyle.style(useColor, AnsiStyle.CYAN) + "vmflag"
                + AnsiStyle.style(useColor, AnsiStyle.RESET) + " set " + flagName + "=" + flagValue);
        System.out.println(AnsiStyle.style(useColor, AnsiStyle.DIM) + result
                + AnsiStyle.style(useColor, AnsiStyle.RESET));
    }

    private static void printTable(VmFlagResult result, long pid, String source,
                                   boolean useColor, String filter, Messages messages) {
        List<VmFlagResult.VmFlag> flags = result.flags();
        String filterLower = filter != null ? filter.toLowerCase() : null;

        System.out.println(RichRenderer.boxHeader(useColor, messages.get("header.vmflag"),
                WIDTH, "pid:" + pid, "source:" + source));
        System.out.println(RichRenderer.emptyLine(WIDTH));

        // Column header
        String hdr = AnsiStyle.style(useColor, AnsiStyle.BOLD)
                + RichRenderer.padRight("Flag", 44) + "Value"
                + AnsiStyle.style(useColor, AnsiStyle.RESET);
        System.out.println(RichRenderer.boxLine(hdr, WIDTH));
        System.out.println(RichRenderer.boxLine("\u2500".repeat(Math.min(70, WIDTH - 4)), WIDTH));

        int shown = 0;
        for (VmFlagResult.VmFlag flag : flags) {
            if (filterLower != null) {
                boolean matches = flag.name().toLowerCase().contains(filterLower)
                        || flag.value().toLowerCase().contains(filterLower);
                if (!matches) continue;
            }

            String nameCell = AnsiStyle.style(useColor, AnsiStyle.CYAN)
                    + RichRenderer.truncate(flag.name(), 42)
                    + AnsiStyle.style(useColor, AnsiStyle.RESET);
            int nameVisLen = Math.min(flag.name().length(), 42);
            String paddedName = nameCell + " ".repeat(Math.max(0, 44 - nameVisLen));

            // Color boolean values
            String valueStr = flag.value();
            String coloredValue;
            if ("true".equalsIgnoreCase(valueStr) || "1".equals(valueStr)) {
                coloredValue = AnsiStyle.style(useColor, AnsiStyle.GREEN) + valueStr
                        + AnsiStyle.style(useColor, AnsiStyle.RESET);
            } else if ("false".equalsIgnoreCase(valueStr) || "0".equals(valueStr)) {
                coloredValue = AnsiStyle.style(useColor, AnsiStyle.DIM) + valueStr
                        + AnsiStyle.style(useColor, AnsiStyle.RESET);
            } else {
                coloredValue = valueStr;
            }

            System.out.println(RichRenderer.boxLine(paddedName + coloredValue, WIDTH));
            shown++;
        }

        if (shown == 0) {
            System.out.println(RichRenderer.boxLine(
                    AnsiStyle.style(useColor, AnsiStyle.DIM) + "(no matching flags)"
                            + AnsiStyle.style(useColor, AnsiStyle.RESET), WIDTH));
        }

        System.out.println(RichRenderer.emptyLine(WIDTH));
        String summary = "Total: " + flags.size() + " flags"
                + (filter != null ? "  filter: \"" + filter + "\"  shown: " + shown : "");
        System.out.println(RichRenderer.boxLine(
                AnsiStyle.style(useColor, AnsiStyle.DIM) + summary + AnsiStyle.style(useColor, AnsiStyle.RESET),
                WIDTH));
        System.out.println(RichRenderer.boxFooter(useColor, null, WIDTH));
    }

    private static void printJson(VmFlagResult result, String filter) {
        String filterLower = filter != null ? filter.toLowerCase() : null;
        StringBuilder sb = new StringBuilder();
        sb.append("{\"flags\":[");
        boolean first = true;
        for (VmFlagResult.VmFlag flag : result.flags()) {
            if (filterLower != null) {
                boolean matches = flag.name().toLowerCase().contains(filterLower)
                        || flag.value().toLowerCase().contains(filterLower);
                if (!matches) continue;
            }
            if (!first) sb.append(',');
            sb.append("{\"name\":\"").append(RichRenderer.escapeJson(flag.name())).append('"')
              .append(",\"value\":\"").append(RichRenderer.escapeJson(flag.value())).append("\"}");
            first = false;
        }
        sb.append("]}");
        System.out.println(sb);
    }
}
