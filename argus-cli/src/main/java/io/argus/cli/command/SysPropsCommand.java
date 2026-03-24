package io.argus.cli.command;

import io.argus.cli.config.CliConfig;
import io.argus.cli.config.Messages;
import io.argus.cli.model.SysPropsResult;
import io.argus.cli.provider.ProviderRegistry;
import io.argus.cli.provider.SysPropsProvider;
import io.argus.cli.render.AnsiStyle;
import io.argus.cli.render.RichRenderer;

import java.util.Map;

/**
 * Shows JVM system properties via jcmd VM.system_properties.
 * Supports --filter for case-insensitive search across keys and values.
 */
public final class SysPropsCommand implements Command {

    private static final int WIDTH = RichRenderer.DEFAULT_WIDTH;

    @Override
    public String name() {
        return "sysprops";
    }

    @Override
    public String description(Messages messages) {
        return messages.get("cmd.sysprops.desc");
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

        for (int i = 1; i < args.length; i++) {
            String arg = args[i];
            if (arg.startsWith("--source=")) {
                sourceOverride = arg.substring(9);
            } else if (arg.equals("--format=json")) {
                json = true;
            } else if (arg.startsWith("--filter=")) {
                filter = arg.substring(9);
            }
        }

        SysPropsProvider provider = registry.findSysPropsProvider(pid, sourceOverride);
        if (provider == null) {
            System.err.println(messages.get("error.provider.none", pid));
            return;
        }

        SysPropsResult result = provider.getSystemProperties(pid);

        if (json) {
            printJson(result, filter);
        } else {
            System.out.print(RichRenderer.brandedHeader(useColor, "sysprops", messages.get("desc.sysprops")));
            printTable(result, pid, provider.source(), useColor, filter, messages);
        }
    }

    private static void printTable(SysPropsResult result, long pid, String source,
                                   boolean useColor, String filter, Messages messages) {
        Map<String, String> props = result.properties();

        String filterLower = filter != null ? filter.toLowerCase() : null;

        System.out.println(RichRenderer.boxHeader(useColor, messages.get("header.sysprops"),
                WIDTH, "pid:" + pid, "source:" + source));
        System.out.println(RichRenderer.emptyLine(WIDTH));

        // Column header
        String hdr = AnsiStyle.style(useColor, AnsiStyle.BOLD)
                + RichRenderer.padRight("Key", 40) + "Value"
                + AnsiStyle.style(useColor, AnsiStyle.RESET);
        System.out.println(RichRenderer.boxLine(hdr, WIDTH));
        System.out.println(RichRenderer.boxLine("\u2500".repeat(Math.min(70, WIDTH - 4)), WIDTH));

        int shown = 0;
        for (Map.Entry<String, String> entry : props.entrySet()) {
            String key = entry.getKey();
            String value = entry.getValue();

            if (filterLower != null) {
                boolean matches = key.toLowerCase().contains(filterLower)
                        || value.toLowerCase().contains(filterLower);
                if (!matches) continue;
            }

            String keyCell = AnsiStyle.style(useColor, AnsiStyle.CYAN)
                    + RichRenderer.truncate(key, 38)
                    + AnsiStyle.style(useColor, AnsiStyle.RESET);
            // Pad key column to 40 visible chars
            int keyVisLen = key.length() > 38 ? 39 : key.length();
            String paddedKey = keyCell + " ".repeat(Math.max(0, 40 - keyVisLen));

            String valueStr = value.replace("\n", "\\n");
            String line = paddedKey + RichRenderer.truncate(valueStr, WIDTH - 48);
            System.out.println(RichRenderer.boxLine(line, WIDTH));
            shown++;
        }

        if (shown == 0) {
            System.out.println(RichRenderer.boxLine(
                    AnsiStyle.style(useColor, AnsiStyle.DIM) + "(no matching properties)"
                            + AnsiStyle.style(useColor, AnsiStyle.RESET), WIDTH));
        }

        System.out.println(RichRenderer.emptyLine(WIDTH));
        String summary = "Total: " + props.size() + " properties"
                + (filter != null ? "  filter: \"" + filter + "\"  shown: " + shown : "");
        System.out.println(RichRenderer.boxLine(
                AnsiStyle.style(useColor, AnsiStyle.DIM) + summary + AnsiStyle.style(useColor, AnsiStyle.RESET),
                WIDTH));
        System.out.println(RichRenderer.boxFooter(useColor, null, WIDTH));
    }

    private static void printJson(SysPropsResult result, String filter) {
        String filterLower = filter != null ? filter.toLowerCase() : null;
        StringBuilder sb = new StringBuilder();
        sb.append('{');
        boolean first = true;
        for (Map.Entry<String, String> entry : result.properties().entrySet()) {
            String key = entry.getKey();
            String value = entry.getValue();
            if (filterLower != null) {
                boolean matches = key.toLowerCase().contains(filterLower)
                        || value.toLowerCase().contains(filterLower);
                if (!matches) continue;
            }
            if (!first) sb.append(',');
            sb.append('"').append(RichRenderer.escapeJson(key)).append('"')
              .append(':')
              .append('"').append(RichRenderer.escapeJson(value)).append('"');
            first = false;
        }
        sb.append('}');
        System.out.println(sb);
    }
}
