package io.argus.cli.command;

import io.argus.cli.config.CliConfig;
import io.argus.cli.config.Messages;
import io.argus.cli.provider.ProviderRegistry;
import io.argus.cli.provider.jdk.JcmdExecutor;
import io.argus.cli.render.AnsiStyle;
import io.argus.cli.render.RichRenderer;
import io.argus.core.command.CommandGroup;

/**
 * Triggers System.gc() on the target JVM via jcmd GC.run.
 * Optionally runs finalization via GC.run_finalization.
 */
public final class GcRunCommand implements Command {

    private static final int WIDTH = RichRenderer.DEFAULT_WIDTH;

    @Override
    public String name() {
        return "gcrun";
    }

    @Override public CommandGroup group() { return CommandGroup.MEMORY; }

    @Override
    public String description(Messages messages) {
        return messages.get("cmd.gcrun.desc");
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

        boolean json = "json".equals(config.format());
        boolean useColor = config.color();
        boolean runFinalization = false;

        for (int i = 1; i < args.length; i++) {
            if (args[i].equals("--finalize")) {
                runFinalization = true;
            } else if (args[i].equals("--format=json")) {
                json = true;
            }
        }

        if (!JcmdExecutor.isJcmdAvailable()) {
            System.err.println(messages.get("error.provider.none", pid));
            return;
        }

        boolean gcSuccess = false;
        boolean finalizeSuccess = false;
        String gcError = "";
        String finalizeError = "";

        // Trigger GC
        try {
            JcmdExecutor.execute(pid, "GC.run");
            gcSuccess = true;
        } catch (RuntimeException e) {
            gcError = e.getMessage() != null ? e.getMessage() : "unknown error";
        }

        // Trigger finalization if requested
        if (runFinalization) {
            try {
                JcmdExecutor.execute(pid, "GC.run_finalization");
                finalizeSuccess = true;
            } catch (RuntimeException e) {
                finalizeError = e.getMessage() != null ? e.getMessage() : "unknown error";
            }
        }

        if (json) {
            StringBuilder sb = new StringBuilder();
            sb.append("{\"pid\":").append(pid);
            sb.append(",\"gcTriggered\":").append(gcSuccess);
            if (!gcError.isEmpty()) {
                sb.append(",\"gcError\":\"").append(RichRenderer.escapeJson(gcError)).append('"');
            }
            if (runFinalization) {
                sb.append(",\"finalizationTriggered\":").append(finalizeSuccess);
                if (!finalizeError.isEmpty()) {
                    sb.append(",\"finalizationError\":\"").append(RichRenderer.escapeJson(finalizeError)).append('"');
                }
            }
            sb.append('}');
            System.out.println(sb);
            return;
        }

        System.out.print(RichRenderer.brandedHeader(useColor, "gcrun", messages.get("desc.gcrun")));
        System.out.println(RichRenderer.boxHeader(useColor, messages.get("header.gcrun"),
                WIDTH, "pid:" + pid));
        System.out.println(RichRenderer.emptyLine(WIDTH));

        if (gcSuccess) {
            String ok = "  " + AnsiStyle.style(useColor, AnsiStyle.GREEN) + "\u2714 "
                    + messages.get("gcrun.success")
                    + AnsiStyle.style(useColor, AnsiStyle.RESET);
            System.out.println(RichRenderer.boxLine(ok, WIDTH));
        } else {
            String fail = "  " + AnsiStyle.style(useColor, AnsiStyle.RED) + "\u2718 "
                    + messages.get("gcrun.failed") + ": " + gcError
                    + AnsiStyle.style(useColor, AnsiStyle.RESET);
            System.out.println(RichRenderer.boxLine(fail, WIDTH));
        }

        if (runFinalization) {
            if (finalizeSuccess) {
                String ok = "  " + AnsiStyle.style(useColor, AnsiStyle.GREEN) + "\u2714 "
                        + messages.get("gcrun.finalize.success")
                        + AnsiStyle.style(useColor, AnsiStyle.RESET);
                System.out.println(RichRenderer.boxLine(ok, WIDTH));
            } else {
                String fail = "  " + AnsiStyle.style(useColor, AnsiStyle.RED) + "\u2718 "
                        + messages.get("gcrun.finalize.failed") + ": " + finalizeError
                        + AnsiStyle.style(useColor, AnsiStyle.RESET);
                System.out.println(RichRenderer.boxLine(fail, WIDTH));
            }
        }

        System.out.println(RichRenderer.emptyLine(WIDTH));
        System.out.println(RichRenderer.boxFooter(useColor, null, WIDTH));
    }
}
