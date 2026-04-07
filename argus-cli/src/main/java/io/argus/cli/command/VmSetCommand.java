package io.argus.cli.command;

import io.argus.cli.config.CliConfig;
import io.argus.cli.config.Messages;
import io.argus.cli.provider.ProviderRegistry;
import io.argus.cli.provider.jdk.JcmdExecutor;
import io.argus.cli.render.AnsiStyle;
import io.argus.cli.render.RichRenderer;
import io.argus.core.command.CommandGroup;

import java.io.Console;

/**
 * Sets a VM flag at runtime via jcmd VM.set_flag.
 */
public final class VmSetCommand implements Command {

    private static final int WIDTH = RichRenderer.DEFAULT_WIDTH;

    @Override
    public String name() { return "vmset"; }

    @Override public CommandGroup group() { return CommandGroup.PROCESS; }

    @Override
    public String description(Messages messages) {
        return messages.get("cmd.vmset.desc");
    }

    @Override
    public void execute(String[] args, CliConfig config, ProviderRegistry registry, Messages messages) {
        if (args.length < 2) {
            System.err.println(messages.get("vmset.usage"));
            return;
        }

        long pid;
        try { pid = Long.parseLong(args[0]); }
        catch (NumberFormatException e) { System.err.println(messages.get("error.pid.invalid", args[0])); return; }

        boolean useColor = config.color();
        boolean confirm = false;

        String flagName = null;
        String flagValue = null;

        for (int i = 1; i < args.length; i++) {
            String arg = args[i];
            if (arg.equals("--yes") || arg.equals("--confirm")) {
                confirm = true;
            } else if (arg.contains("=")) {
                int eq = arg.indexOf('=');
                flagName = arg.substring(0, eq);
                flagValue = arg.substring(eq + 1);
            } else if (arg.startsWith("+")) {
                flagName = arg.substring(1);
                flagValue = "true";
            } else if (arg.startsWith("-") && !arg.startsWith("--")) {
                flagName = arg.substring(1);
                flagValue = "false";
            } else if (flagName == null) {
                flagName = arg;
            } else {
                flagValue = arg;
            }
        }

        if (flagName == null || flagValue == null) {
            System.err.println(messages.get("vmset.usage"));
            return;
        }

        // Show what will change
        System.out.print(RichRenderer.brandedHeader(useColor, "vmset", messages.get("desc.vmset")));
        System.out.println(RichRenderer.boxHeader(useColor, messages.get("header.vmset"), WIDTH, "pid:" + pid));
        System.out.println(RichRenderer.emptyLine(WIDTH));

        String changeLine = AnsiStyle.style(useColor, AnsiStyle.BOLD) + flagName + AnsiStyle.style(useColor, AnsiStyle.RESET)
                + " = " + AnsiStyle.style(useColor, AnsiStyle.CYAN) + flagValue + AnsiStyle.style(useColor, AnsiStyle.RESET);
        System.out.println(RichRenderer.boxLine(changeLine, WIDTH));

        // Confirmation
        if (!confirm) {
            System.out.println(RichRenderer.emptyLine(WIDTH));
            String warn = AnsiStyle.style(useColor, AnsiStyle.YELLOW) + "\u26a0 "
                    + messages.get("vmset.warn") + AnsiStyle.style(useColor, AnsiStyle.RESET);
            System.out.println(RichRenderer.boxLine(warn, WIDTH));
            System.out.println(RichRenderer.boxFooter(useColor, null, WIDTH));

            Console console = System.console();
            if (console == null) {
                System.err.println(messages.get("vmset.noconfirm"));
                return;
            }
            System.out.print(messages.get("vmset.proceed"));
            String response = console.readLine();
            if (response == null || !response.trim().toLowerCase().startsWith("y")) {
                System.out.println(messages.get("vmset.cancelled"));
                return;
            }
        }

        // Execute
        try {
            String result = JcmdExecutor.execute(pid, "VM.set_flag " + flagName + " " + flagValue);
            System.out.println();
            String ok = AnsiStyle.style(useColor, AnsiStyle.GREEN) + "\u2714 "
                    + messages.get("vmset.success") + AnsiStyle.style(useColor, AnsiStyle.RESET);
            System.out.println(ok);
            if (!result.isBlank()) {
                System.out.println(result.trim());
            }
        } catch (RuntimeException e) {
            String fail = AnsiStyle.style(useColor, AnsiStyle.RED) + "\u2718 "
                    + messages.get("vmset.failed", e.getMessage()) + AnsiStyle.style(useColor, AnsiStyle.RESET);
            System.err.println(fail);
        }
    }
}
