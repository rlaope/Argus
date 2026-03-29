package io.argus.cli.command;

import io.argus.cli.config.CliConfig;
import io.argus.cli.config.Messages;
import io.argus.cli.provider.ProviderRegistry;
import io.argus.cli.provider.jdk.JcmdExecutor;
import io.argus.cli.render.AnsiStyle;
import io.argus.cli.render.RichRenderer;

/**
 * Controls JVM unified logging via jcmd VM.log.
 */
public final class VmLogCommand implements Command {

    private static final int WIDTH = RichRenderer.DEFAULT_WIDTH;

    @Override
    public String name() { return "vmlog"; }

    @Override
    public String description(Messages messages) {
        return messages.get("cmd.vmlog.desc");
    }

    @Override
    public void execute(String[] args, CliConfig config, ProviderRegistry registry, Messages messages) {
        if (args.length == 0) { System.err.println(messages.get("error.pid.required")); return; }

        long pid;
        try { pid = Long.parseLong(args[0]); }
        catch (NumberFormatException e) { System.err.println(messages.get("error.pid.invalid", args[0])); return; }

        boolean useColor = config.color();

        // Determine mode: list (default), or pass-through args to VM.log
        if (args.length == 1) {
            // Show current log configuration
            showLogConfig(pid, useColor, messages);
            return;
        }

        // Build VM.log arguments from remaining args
        StringBuilder vmLogArgs = new StringBuilder("VM.log");
        for (int i = 1; i < args.length; i++) {
            vmLogArgs.append(' ').append(args[i]);
        }

        System.out.print(RichRenderer.brandedHeader(useColor, "vmlog", messages.get("desc.vmlog")));
        System.out.println(RichRenderer.boxHeader(useColor, messages.get("header.vmlog"), WIDTH, "pid:" + pid));
        System.out.println(RichRenderer.emptyLine(WIDTH));

        try {
            String output = JcmdExecutor.execute(pid, vmLogArgs.toString());

            if (!output.isBlank()) {
                for (String line : output.split("\n")) {
                    String trimmed = line.trim();
                    if (trimmed.isEmpty()) continue;
                    System.out.println(RichRenderer.boxLine(trimmed, WIDTH));
                }
            }

            String ok = AnsiStyle.style(useColor, AnsiStyle.GREEN) + "\u2714 "
                    + messages.get("vmlog.success") + AnsiStyle.style(useColor, AnsiStyle.RESET);
            System.out.println(RichRenderer.boxLine(ok, WIDTH));
        } catch (RuntimeException e) {
            String fail = AnsiStyle.style(useColor, AnsiStyle.RED) + "\u2718 " + e.getMessage()
                    + AnsiStyle.style(useColor, AnsiStyle.RESET);
            System.out.println(RichRenderer.boxLine(fail, WIDTH));
        }

        System.out.println(RichRenderer.boxFooter(useColor, null, WIDTH));
    }

    private void showLogConfig(long pid, boolean useColor, Messages messages) {
        System.out.print(RichRenderer.brandedHeader(useColor, "vmlog", messages.get("desc.vmlog")));
        System.out.println(RichRenderer.boxHeader(useColor, messages.get("header.vmlog"), WIDTH, "pid:" + pid));
        System.out.println(RichRenderer.emptyLine(WIDTH));

        try {
            String output = JcmdExecutor.execute(pid, "VM.log list");
            for (String line : output.split("\n")) {
                String trimmed = line.trim();
                if (trimmed.isEmpty()) continue;
                // Highlight tag names
                if (trimmed.startsWith("#")) {
                    String styled = AnsiStyle.style(useColor, AnsiStyle.BOLD) + trimmed
                            + AnsiStyle.style(useColor, AnsiStyle.RESET);
                    System.out.println(RichRenderer.boxLine(styled, WIDTH));
                } else {
                    System.out.println(RichRenderer.boxLine(trimmed, WIDTH));
                }
            }
        } catch (RuntimeException e) {
            String fail = AnsiStyle.style(useColor, AnsiStyle.RED) + "\u2718 " + e.getMessage()
                    + AnsiStyle.style(useColor, AnsiStyle.RESET);
            System.out.println(RichRenderer.boxLine(fail, WIDTH));
        }

        System.out.println(RichRenderer.boxFooter(useColor, null, WIDTH));
    }
}
